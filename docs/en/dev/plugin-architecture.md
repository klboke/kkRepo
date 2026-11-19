# Plugin architecture and dependency inversion

- Status: proposed, narrowed after review (2026-11-19)
- Date: 2026-11-11
- Scope (v1): repository formats, storage, remote transport
- Implementation: #508

## Context

kkRepo already separates implementations into Maven modules, but the server remains the
composition root for every format, storage backend and network client. Adding a format or a
storage technology currently means changing the server module, and external code cannot be
loaded without rebuilding the distribution.

The storage SPI is the first successful step of the inversion:

- `core/blobstore/BlobStoreProvider` is a small port (`type`, `supports`, `signature`,
  `create`, `invalidate`).
- `core/blobstore/BlobStoreProviders` resolves a persisted `blob_store` row to a provider.
- `storage-file`, `storage-s3` and `storage-azure` implement the port.
- `server/maven/BlobStorageRegistry` depends on `List<BlobStoreProvider>`, not on a concrete
  factory.

This proposal applies the same pattern to repository formats and network transports, behind a
common registration mechanism.

## Decision: v1 objective

> Establish extension interfaces for repository formats, storage and remote transport, and
> assemble existing implementations through a common registration mechanism. Artifact lifecycle,
> permissions and multi-replica coordination remain host responsibilities. Validate the boundaries
> with one complete format before expanding the extension surface.

v1 is limited to the five core contracts below, uses the existing built-in implementations as their
first consumers, and validates them with Git LFS end to end. Everything else is deferred (see
[Deferred](#deferred)).

## Current coupling points

| Coupling | Location | Consequence |
| --- | --- | --- |
| Closed format enum | `core/RepositoryFormat` (891 references in `server`) | A new format requires editing the enum and every switch. |
| Servlet/Spring in the handler port | `server/routing/RepositoryProtocolHandler`, `RepositoryProtocolRequest` | A plugin cannot stay framework-agnostic; it depends on `ResponseEntity`, `HttpServletRequest` and `MultipartFile`. |
| Wide runtime record | `server/maven/RepositoryRuntime` | The request context mixes configuration with server-only concerns (proxy credentials, NTLM, redirect hosts). |
| Concrete network client | `server/maven/HttpRemoteFetcher` | No transport SPI; proxy services cannot use an alternative implementation. |
| No plugin loader | none | External JARs cannot contribute handlers or providers. |
| Broad persistence contract | `PersistenceStores` (all DAOs) | A plugin would depend on the full JDBC surface and the internal schema. |

## The five core contracts

### 1. Extension interfaces and registration

Three extension points: repository formats (with their protocol handlers), storage and remote
transport. A plugin implements `KkRepoPlugin` and registers through `PluginContext`:

```java
public interface KkRepoPlugin {
    PluginDescriptor descriptor();
    void contribute(PluginContext context);
}

public interface PluginContext {
    void registerRemoteTransport(RemoteTransportProvider provider);
    void registerRepositoryFormat(RepositoryFormatProvider provider);
    void registerRepositoryProtocolHandler(RepositoryProtocolHandler handler);
    void registerBlobStoreProvider(BlobStoreProvider provider);
}
```

- A format descriptor (`RepositoryFormatDescriptor`) carries the `FormatId`, the display name and
  the supported repository types. `FormatId` is an open identifier at the routing boundary; the
  `RepositoryFormat` enum stays for built-in code.
- Handlers are framework-agnostic: `RepositoryProtocolHandler` exchanges portable
  `RepositoryRequest`/`RepositoryResponse` values; no Spring or servlet type crosses the port.
  `PluginProtocolDispatcher` adapts them to the existing routing pipeline.
- Built-in implementations are registered through the same SPI: the runtime discovers classpath
  plugins with `ServiceLoader` (`PluginLoader.loadBuiltins`) before signed external JARs, into the
  same registry.
- Startup validation, all-or-nothing: duplicate plugin ids, format ids, transport types, blob store
  types and protocol routes fail startup, whatever the registration order, and so does an API
  version mismatch. A plugin can add a blob store type but cannot replace a built-in one. Checking
  required host services at registration is still to do.
- No Recipe/Facet framework in v1; Nexus's composition approach is borrowed only for the
  format/handler split.

### 2. Artifact operations owned by the host

Plugins own protocol parsing and protocol metadata. The host owns transactions, blob references,
audit and reclamation, and exposes them as narrow, format-level operations (`PluginHostService`)
around the existing publish, read, query and delete services. Plugins look them up through
`RepositoryContext.service(Class)`.

The Git LFS port is the first example:

```java
public interface GitLfsHostOperations extends PluginHostService {
    long maxObjectBytes();
    GitLfsBatchResult batch(RepositoryContext repository, GitLfsProtocol.Batch batch);
    void verify(RepositoryContext repository, String oid, long size);
    void upload(RepositoryContext repository, String oid, String uploadId, long contentLength,
        InputStream body) throws IOException;
    GitLfsStoredObject find(RepositoryContext repository, String oid);   // runs the download policy
    InputStream open(RepositoryContext repository, GitLfsStoredObject object, GitLfsByteRange range)
        throws IOException;                                              // only objects find() resolved
}
```

No generic JSON database or query DSL in v1: plugins do not receive `PersistenceStores`.

### 3. Context scopes and resource ownership

- `RepositoryContext` carries repository configuration and shared services: the trimmed
  `RepositoryDescriptor`, the portable `RepositoryPolicy`, optional `BlobStorage` and host-service
  lookup. Request identity (caller, client address, public base URL) is resolved by the host from
  the context and never crosses the plugin boundary.
- Handlers are stateless and thread-safe; one instance serves concurrent requests.
- Stream ownership: a handler that declares `streamsRequestBody()` receives the unbuffered client
  stream and must consume or close it; otherwise the runtime buffers the body. A `StreamingBody`
  response is written and closed by the runtime.
- Lifecycle: plugins are initialised at startup and released at shutdown. A configuration change
  recreates the instance or requires a restart; there is no hot reload.

### 4. Common policies enforced by the host

Plugins declare routes (format, repository kind, method, path pattern). Requests keep going through
the existing pipeline before the plugin is called: authentication, authorization (including
format-specific checks such as the Git LFS Batch authorization in the security filter), path
validation, repository policies and outbound-access controls. There are no arbitrary filter or
execution-stage extensions in v1.

### 5. Reuse the multi-replica infrastructure

Plugin state that must be shared lives behind host interfaces (Git LFS upload leases and quotas stay
in the host service), uses the unified cache module and the existing coordination for background
work. Plugin instances must be reconstructible from configuration. There is no plugin-specific
locking, scheduling or event system.

## Surrounding platform

- **Configuration and UI**: plugins declare supported capabilities and unsupported ones are disabled
  explicitly. Browse, search and UI upload integration follow incrementally; no generic dynamic-form
  platform in v1.
- **Versioning and packaging**: `apiVersion` in the descriptor must match the runtime exactly.
  External JARs are loaded at startup only, from `kkrepo.plugins.directory` when
  `kkrepo.plugins.enabled` is set, after signature verification: full PKIX chain against
  `kkrepo.plugins.truststore`, pinned leaf certificate, single trusted signer, child-first
  classloader with `plugin-api` and `core` parent-provided. No hot loading, online unloading,
  inter-plugin dependencies or automatic replacement of built-in implementations.
- **Trust model**: a signed JAR is trusted code with full JVM access. Untrusted code belongs out of
  process, following the `scanner-adapter` pattern.

## Validation: Git LFS end to end

Git LFS is the one complete format of v1:

- `plugin-gitlfs` owns the whole HTTP surface of hosted Git LFS repositories: routes (GET, HEAD,
  POST, PUT, DELETE), path and JSON parsing, the Batch response shape, the error envelope, and
  conditional and range semantics. The former built-in handler is removed; the plugin is a built-in
  registered through the SPI.
- The host keeps per-object permissions, upload leases and quotas, verified blob writes, publication
  transactions, audit, download policy and blob references behind `GitLfsHostOperations`.
- Tests cover repository creation (through `RepositoryService` in the running application),
  publication (Batch upload, streamed PUT, verify), reads (Batch
  download, GET, Range, conditional 304, HEAD), deletion (405 on the protocol, administration API
  for removal) and permission checks (401 with `LFS-Authenticate`, read-only upload denied), through
  the real routing path and the running application. An ArchUnit rule keeps `plugin.gitlfs` and
  `protocol.gitlfs` free of Spring, servlet, server, persistence and loader dependencies.
- Storage: built-in providers and plugin providers share one registry; tests register a plugin
  provider for a new type and reject one that duplicates a built-in type.

## Module graph (v1)

```
core             domain and basic ports (BlobStorage, BlobStoreProvider, RepositoryType)
plugin-api       plugin SPI, neutral request/response, FormatId, transport and host-service ports
plugin-loader    ServiceLoader discovery of built-ins, signed JAR loading and verification
server           Spring Boot runtime: security, routing, dispatcher, registries, host services
storage-*        built-in BlobStoreProvider implementations
protocol-gitlfs  Git LFS protocol model and the GitLfsHostOperations port
plugin-gitlfs    Git LFS protocol handler (built-in plugin)
```

Rules: `core`, `plugin-api`, `protocol-gitlfs` and `plugin-gitlfs` do not depend on Spring, servlet
or JDBC; plugins never depend on the server or the loader.

## Testing and enforcement

- Loader: signed JAR accepted; unsigned, wrong signer, tampered and incompatible API version
  rejected; duplicate id, format, transport, blob store type and route rejected.
- Dispatcher: neutral request/response adaptation, streamed request and response bodies.
- Format: the Git LFS suites above.
- ArchUnit: `plugin-api` free of Spring, servlet, JDBC, JPA, Quartz, the server and the loader; the
  Git LFS rule above.
- Native image: classpath built-ins discovered through `ServiceLoader` are registered in
  `PluginRuntimeHints`; dynamic JAR loading is a JVM feature.

## Deferred

Kept out of v1 until the Java extension contracts are proven; each can reuse the same host
interfaces later:

- JSR-223 scripts, script repository handlers, scripted cleanup rules, plugin maintenance tasks and
  event subscriptions;
- extraction of further formats (the Helm upload, OHPM write and catch-all routes were prototyped
  and are left to follow-up PRs, one format at a time);
- storage adapters shipped as plugins that replace built-in implementations;
- a generic plugin metadata store (`RepositoryMetadataStore`) and plugin-provided migrations;
- adopting `RemoteTransportProvider` in the proxy services (the port exists; migrating
  `HttpRemoteFetcher` and a first proxy service is the next transport step);
- hot loading, unloading, inter-plugin dependencies, a plugin certification framework.

## Risks and open questions

1. **Format enum**: `FormatId` exists at the routing boundary; the 891 `RepositoryFormat`
   references and persisted rows migrate in batches with a compatibility mapping.
2. **Handler migration**: built-in handlers keep the servlet-based `LegacyRepositoryProtocolHandler`
   until each format is moved, one format per PR.
3. **Classloader leaks**: startup-only loading avoids them in v1.
4. **API versioning**: exact `apiVersion` match in v1; a compatibility window can come with the
   first external consumers.
5. **Native image**: external plugins are JVM-only; native distributions use the classpath
   built-ins.

## References

- `core/src/main/java/com/github/klboke/kkrepo/core/blobstore/BlobStoreProvider.java`
- `core/src/main/java/com/github/klboke/kkrepo/core/blobstore/BlobStoreProviders.java`
- `server/src/main/java/com/github/klboke/kkrepo/server/maven/BlobStorageRegistry.java`
- `server/src/main/java/com/github/klboke/kkrepo/server/maven/HttpRemoteFetcher.java`
- `persistence-jdbc/src/main/java/com/github/klboke/kkrepo/persistence/jdbc/api/PersistenceStoreFactories.java`
- `scanner-adapter/` (out-of-process adapter pattern)
- #508 (implementation) and the review of this ADR
