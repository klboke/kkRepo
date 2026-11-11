# Plugin architecture and dependency inversion

- Status: proposed
- Date: 2026-11-11
- Scope: storage, repository protocols, network transports, scripts

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

This proposal applies the same pattern to repository formats and network transports, and adds a
plugin loader so providers can be shipped as JARs or JSR-223 scripts.

## Goals

1. The runtime depends only on ports, never on concrete adapters.
2. A repository format can be added by dropping a JAR (or a script) into `plugins/`, without
   changing the server source.
3. A network transport (HTTP client, proxy, mTLS, custom protocol) can be replaced the same way.
4. A storage backend can be shipped as a plugin, using the existing `BlobStoreProvider` port.
5. JSR-223 scripts can implement repository handlers or request/response filters.
6. Every plugin JAR is signed and verified before any class is loaded.
7. Existing built-in formats and storage backends keep working during the migration.

## Non-goals

- A general-purpose application server or arbitrary code hot-swapping.
- Running untrusted native code in-process with a JVM sandbox as the only boundary.
- Plugin-provided database schemas in the first version.
- Changing the database schema for existing built-in formats.

## Current coupling points

| Coupling | Location | Consequence |
| --- | --- | --- |
| Closed format enum | `core/RepositoryFormat` (891 references in `server`) | A new format requires editing the enum and every switch. |
| Servlet/Spring in the handler port | `server/routing/RepositoryProtocolHandler`, `RepositoryProtocolRequest` | A plugin cannot stay framework-agnostic; it depends on `ResponseEntity`, `HttpServletRequest` and `MultipartFile`. |
| Wide runtime record | `server/maven/RepositoryRuntime` | The request context mixes configuration with server-only concerns (proxy credentials, NTLM, redirect hosts). |
| Concrete network client | `server/maven/HttpRemoteFetcher` | No transport SPI; proxy services cannot use an alternative implementation. |
| Direct storage imports | `BlobStorageRegistry` legacy constructors, `server/pom.xml` | The storage SPI exists, but the runtime still compiles against the adapters. |
| No plugin loader | none | External JARs cannot contribute handlers or providers. |
| ArchUnit closes the door | `RepositoryRoutingArchitectureTest.routeHandlersStayInsideTheRoutingLayer` | Handlers outside `server.routing` are rejected. |
| Broad persistence contract | `PersistenceStores` (all DAOs) | A plugin would depend on the full JDBC surface and the internal schema. |

## Target module graph

```
core                 domain + basic ports (BlobStorage, BlobStoreProvider, RepositoryType)
plugin-api           plugin SPI, neutral request/response, FormatId, RemoteFetcher
runtime (server)     Spring Boot, web, security, dispatcher, plugin loader, registries
storage-file         BlobStoreProvider
storage-s3           BlobStoreProvider
storage-azure        BlobStoreProvider
remote-http          RemoteTransportProvider (HttpRemoteFetcher becomes an adapter)
protocol-*           protocol libraries; may become format plugins
script-host          JSR-223 ScriptEngine host
dist                 assembly: runtime + selected plugins
```

Rules:

- `core` and `plugin-api` must not depend on Spring, servlet, JDBC or any adapter.
- `runtime` depends on `core` and `plugin-api`; it must not compile against adapter modules.
- Adapters depend on `plugin-api` and `core`; they never depend on `runtime`.
- The distribution assembles the runtime and the selected adapters/plugins.

## Ports

### Format identity

Replace the closed enum at the routing boundary with an open identifier. Keep the enum for
built-in code until the migration is complete.

```java
public record FormatId(String value) {
    public static FormatId of(RepositoryFormat builtin) {
        return new FormatId(builtin.id());
    }
}
```

`RepositoryProtocolRoute` and the dispatcher key routes by `FormatId`. Built-in handlers use
`FormatId.of(RepositoryFormat.ALPINE)`; a plugin uses `new FormatId("my-format")`. The admin UI
and persistence layer accept the string id; a compatibility mapping keeps existing rows working.

### Repository format provider

Model it on `BlobStoreProvider`: a small, stateless factory with a descriptor.

```java
public interface RepositoryFormatProvider {
    FormatId id();
    RepositoryFormatDescriptor descriptor();
    Collection<RepositoryProtocolHandler> handlers(RepositoryContext context);
}
```

`RepositoryFormatDescriptor` carries the display name, supported repository types, default
configuration schema, capability flags and documentation link. The runtime rejects duplicate
format ids at startup.

### Neutral repository handler

```java
public interface RepositoryProtocolHandler {
    Collection<RepositoryProtocolRoute> routes();
    RepositoryResponse handle(RepositoryRequest request) throws IOException;
}
```

`RepositoryRequest` contains only portable data:

- `RepositoryDescriptor repository`
- `FormatId format`
- `String path`
- `String method`
- `Map<String, List<String>> headers`
- `InputStream body`
- optional multipart part abstraction
- `Map<String, Object> attributes`

`RepositoryResponse` contains a status code, headers and a body (`byte[]`, `InputStream` or
`Resource`). The runtime adapts it to `ResponseEntity`. No Spring or servlet type crosses the
port.

### Repository context

The handler receives the ports it is allowed to use, not the whole server:

```java
public interface RepositoryContext {
    RepositoryDescriptor repository();
    FormatId format();
    RepositoryMetadataStore metadata();
    BlobStorage blobs();
    RemoteFetcher remote();
    SecurityContext security();
    Metrics metrics();
    ObjectMapper json();
}
```

`RepositoryDescriptor` is a trimmed, immutable configuration record. `RepositoryRuntime` stays
in the runtime as an adapter for legacy code and is gradually replaced by the descriptor.

### Network transport provider

```java
public interface RemoteTransportProvider {
    String type();                       // "http", "http-proxy", "custom"
    RemoteFetcher create(RemoteTransportConfig config);
    ConfigSchema configSchema();
    TransportCapabilities capabilities(); // redirects, mTLS, NTLM, streaming, ...
}
```

```java
public interface RemoteFetcher {
    RemoteResponse fetch(RemoteRequest request) throws IOException;
    <T> T fetchWithBodyRetry(RemoteRequest request, ResultHandler<T> handler) throws IOException;
}
```

`RemoteRequest`/`RemoteResponse` are portable records (URL, method, headers, body, timeout,
status, response headers, body stream). `HttpRemoteFetcher` becomes the `http` provider. The
first migration target is a single proxy service (for example WinGet); the rest follow.

### Storage provider

The existing `BlobStoreProvider` is already the target shape. Remaining work:

- remove the legacy factory constructors from `BlobStorageRegistry`;
- remove `storage-*` from the `server` compile dependencies and add them to `dist`;
- expose `BlobStoreProvider` through the plugin context;
- let the admin UI read the provider's config schema instead of hard-coded store types.

### Repository metadata

Third-party plugins must not receive `PersistenceStores`. Provide a namespaced, generic store:

```java
public interface RepositoryMetadataStore {
    Optional<Document> get(String namespace, String key);
    void put(String namespace, String key, Document value);
    void delete(String namespace, String key);
    List<Document> query(String namespace, Query query);
}
```

Built-in formats keep their optimized tables and DAOs. Plugin data lives in a generic table such
as `plugin_metadata(repository_id, namespace, key, json, version)`. Plugin-provided SQL migrations
are out of scope for v1; they can be added later with a namespaced history table.

## Plugin SPI

```java
public interface KkRepoPlugin {
    PluginDescriptor descriptor();
    void contribute(PluginContext context);
}

public interface PluginContext {
    void registerFormat(RepositoryFormatProvider provider);
    void registerProtocolHandler(RepositoryProtocolHandler handler);
    void registerBlobStoreProvider(BlobStoreProvider provider);
    void registerRemoteTransport(RemoteTransportProvider provider);
    void registerMetadataStore(String namespace, RepositoryMetadataStore store);
}
```

Descriptor:

```json
{
  "id": "repository-raw",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "capabilities": ["repository.format"],
  "mainClass": "com.example.raw.RawPlugin"
}
```

Packaging:

```
plugins/repository-raw-1.0.0.jar
  META-INF/kkrepo-plugin.json
  META-INF/services/com.github.klboke.kkrepo.plugin.KkRepoPlugin
```

Discovery uses `ServiceLoader`, already used by `PersistenceStoreFactories` and
`DatabaseDialects`. A plugin does not need Spring. If it wants Spring-managed collaborators, it
registers beans through `PluginContext`; the runtime owns their lifecycle.

Loader rules:

- scan `plugins/*.jar` (and an optional allowlist);
- create an isolated `URLClassLoader` with `plugin-api`, `core` and the JDK parent-first;
- child-first for the plugin's own packages;
- validate `apiVersion`, duplicate ids, duplicate routes and missing capabilities before startup;
- fail fast with a clear message; never partially register a plugin.

Trust model: a JAR is trusted code with full JVM access. For untrusted code, use the
`scanner-adapter` pattern: a separate process with a narrow HTTP/gRPC contract, not a classloader.

## JSR-223 scripts

Scripts are modules described by a `module.json` next to the script:

```json
{
  "id": "raw-policy",
  "language": "js",
  "engine": "graaljs",
  "entrypoint": "handler.js",
  "capabilities": ["repository.handler", "network.filter"],
  "timeoutMs": 2000
}
```

Recommended contract: JSON in / JSON out, so it is portable across engines.

```js
function handle(request) {
  return {
    status: 200,
    headers: { "Content-Type": "application/json" },
    bodyBase64: "..."
  };
}
```

`ScriptHost` loads the engine with `ScriptEngineManager`, creates a restricted context and wraps
the script in a `RepositoryProtocolHandler` or a `RemoteFilter`. `Invocable.getInterface` is a
secondary option for Groovy/JavaScript modules that prefer typed interfaces.

Security and operations:

- GraalJS is the default: `HostAccess.EXPLICIT`, class lookup allowlist, no host filesystem or
  sockets unless the descriptor requests the capability and the runtime grants it.
- Groovy is allowed for trusted scripts; its sandbox is not a security boundary.
- Jython is not recommended (Python 2, stale).
- Scripts are not a security boundary. For untrusted code, run the script in a separate process
  with a capability-scoped API (the `scanner-adapter` model).
- Timeouts and response-size limits are mandatory; one script cannot block a request thread
  indefinitely.
- Hot reload: watch the file, build a new engine version, swap atomically and drain in-flight
  requests. Startup-only loading is the safe default.

Scripts are appropriate for repository handlers, policy decisions and request/response filters.
They are not appropriate for blob streaming, multipart uploads or high-throughput data paths;
those stay in JAR adapters.

## Migration plan

| Phase | Work | Exit criterion |
| --- | --- | --- |
| 0 | This design document; agree on trust model, schema strategy, `FormatId` approach | Review accepted |
| 1 | Add `plugin-api`; move neutral `RepositoryProtocolHandler`, `RepositoryRequest/Response`, `FormatId`; adapt the runtime | Built-in handlers compile through the new port; no behavior change |
| 2 | Add `RemoteTransportProvider`; make `HttpRemoteFetcher` an adapter; migrate one proxy service | One service uses `RemoteFetcher`; existing tests pass |
| 3 | Add `PluginLoader`; extract one simple format (RAW or WINGET) as a plugin JAR | Plugin loaded from `plugins/`; route served; ArchUnit updated |
| 4 | Add `script-host`; sample repository handler; trust model and limits | Script serves a test route; sandbox tests pass |
| 5 | Migrate remaining formats and storage modules; remove `server` compile deps; add `dist` | `server` has no adapter dependencies; Enforcer/ArchUnit green |
| 6 | Persistence plugin SPI (`RepositoryMetadataStore`); optional plugin migrations | A plugin stores and queries its own documents without JDBC |

Each phase is a separate PR. Do not combine the enum migration, the handler port and the plugin
loader in one change.

## Testing and enforcement

- Port TCKs: `RepositoryFormatProvider`, `RemoteTransportProvider`, `RepositoryProtocolHandler`,
  `KkRepoPlugin`, `RepositoryMetadataStore`.
- Loader tests: duplicate id, API version mismatch, missing main class, duplicate route, isolated
  classloader, plugin unload.
- Script tests: JSON contract, timeout, denied capability, hot reload, engine missing.
- ArchUnit:
  - `plugin-api` has no Spring/servlet/JDBC dependencies;
  - `runtime` does not depend on adapter packages;
  - `core` has no Spring;
  - no adapter depends on `runtime`.
- Maven Enforcer `bannedDependencies` in `server` after the migration.
- Native image: document that dynamic JAR loading is JVM-only, or provide a fixed plugin list and
  GraalVM hints. Do not promise native-image support for arbitrary plugins.

## Risks and open questions

1. **Trust model**: are third-party JARs trusted? If not, the plugin API must be process-isolated
   from day one.
2. **Schema**: generic `plugin_metadata` vs plugin-provided Flyway migrations. Recommendation:
   generic store in v1.
3. **Format enum**: introduce `FormatId` at the routing boundary first, then migrate the 891
   references in batches. Keep a compatibility mapping for persisted rows.
4. **Handler API**: replacing `ResponseEntity` with a neutral response is a breaking change for
   built-in handlers; do it behind an adapter and migrate one handler at a time.
5. **Classloader leaks**: startup-only loading in v1; hot reload only for scripts, with a clear
   lifecycle and leak tests.
6. **Native image**: dynamic plugins conflict with GraalVM native image. Decide whether plugins
   are JVM-only or whether the distribution builds a fixed plugin set.
7. **API versioning**: semantic version for `plugin-api`; `apiVersion` in the descriptor; support
   N-1 during the migration window.

## References

- `core/src/main/java/com/github/klboke/kkrepo/core/blobstore/BlobStoreProvider.java`
- `core/src/main/java/com/github/klboke/kkrepo/core/blobstore/BlobStoreProviders.java`
- `server/src/main/java/com/github/klboke/kkrepo/server/maven/BlobStorageRegistry.java`
- `server/src/main/java/com/github/klboke/kkrepo/server/routing/RepositoryProtocolHandler.java`
- `server/src/main/java/com/github/klboke/kkrepo/server/maven/HttpRemoteFetcher.java`
- `persistence-jdbc/src/main/java/com/github/klboke/kkrepo/persistence/jdbc/api/PersistenceStoreFactories.java`
- `scanner-adapter/` (out-of-process adapter pattern)
