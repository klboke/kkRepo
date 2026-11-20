package com.github.klboke.kkrepo.server.routing.gitlfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.VerifiedBlobDigests;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.plugin.loader.PluginLoader;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.gitlfs.GitLfsAccess;
import com.github.klboke.kkrepo.server.gitlfs.GitLfsHostedService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.plugin.PluginRuntime;
import com.github.klboke.kkrepo.server.routing.PluginProtocolDispatcher;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.NexusLegacyUiCompatibility;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import com.github.klboke.kkrepo.storage.file.FileBlobStorage;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Git LFS through the production request path: repository security filter and {@link GitLfsAccess},
 * {@link RepositoryProtocolController}, {@link RepositoryProtocolDispatcher}, the plugin dispatcher,
 * the built-in Git LFS plugin discovered through {@link java.util.ServiceLoader}, and the host
 * operations over the real hosted service and a file blob store. Only persistence is in memory.
 */
class GitLfsPluginRoutingTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String LFS = GitLfsProtocol.MEDIA_TYPE;
  private static final String ROOT = "/repository/lfs/";
  private static final String WRITER = "Basic alice";
  private static final String READER = "Basic bob";
  private static final byte[] CONTENT = "Git LFS through the plugin boundary\n".getBytes(StandardCharsets.UTF_8);
  private static final String OID = sha("SHA-256", CONTENT);

  @TempDir Path blobs;
  private final Store store = new Store();
  private final ArtifactDownloadPolicy downloads = mock(ArtifactDownloadPolicy.class);
  private final AtomicLong consumedBeforeStorage = new AtomicLong(-1);
  private final AtomicLong consumedByStorage = new AtomicLong(-1);
  private static final java.util.concurrent.atomic.AtomicReference<CountingInput> CLIENT_BODY =
      new java.util.concurrent.atomic.AtomicReference<>();
  private GitLfsHostedService service;
  private PluginRuntime plugins;
  private MockMvc mvc;

  @BeforeEach
  void wireTheRequestPath() throws Exception {
    RepositoryRecord repository = new RepositoryRecord(1L, "lfs", RepositoryFormat.GITLFS,
        RepositoryType.HOSTED, "gitlfs-hosted", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
    RepositoryDaoAdapter repositories = new RepositoryDaoAdapter() {
      @Override
      public Optional<RepositoryRecord> findByName(String name) {
        return "lfs".equals(name) ? Optional.of(repository) : Optional.empty();
      }

      @Override
      public Optional<RepositoryRecord> findById(long id) {
        return id == 1L ? Optional.of(repository) : Optional.empty();
      }
    };
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    when(authentication.hasPresentedCredentials(any()))
        .thenAnswer(call -> ((HttpServletRequest) call.getArgument(0)).getHeader("Authorization") != null);
    when(authentication.authenticateFresh(any())).thenAnswer(call ->
        authenticated(((HttpServletRequest) call.getArgument(0)).getHeader("Authorization")));
    when(authentication.authenticateAnonymousFresh()).thenReturn(Optional.empty());
    // alice may read and add every object; bob may only read.
    AccessDecisionService permissions = (subject, permission) ->
        "alice".equals(subject.userId())
            || ("bob".equals(subject.userId()) && permission.action() == PermissionAction.READ)
            ? AccessDecision.allow()
            : AccessDecision.deny("not granted");
    GitLfsAccess access = new GitLfsAccess(authentication, permissions, repositories, JSON, 1024 * 1024);
    RepositorySecurityFilter filter = new RepositorySecurityFilter(authentication, permissions,
        repositories, new AssetDaoAdapter(), mock(TerraformRegistryDao.class), new ForwardedHeaderPolicy(""),
        new NexusLegacyUiCompatibility(false));
    ReflectionTestUtils.invokeMethod(filter, "setGitLfsAccess", access);

    BlobStorage files = new FileBlobStorage(new FileBlobStoreConfig(1L, "default", blobs.toString(), blobs));
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    when(storages.forBlobStoreId(1L)).thenReturn(new ObservedStorage(files, consumedBeforeStorage, consumedByStorage));
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = new GitLfsHostedService(store.assets, mock(ComponentDao.class), mock(BrowseNodeDao.class),
        store.uploads, storages, access, transactions, 2);
    var host = new ServerGitLfsHostOperations(service, access, storages, downloads, new ForwardedHeaderPolicy(""));

    plugins = new PluginRuntime(PluginLoader.loadBuiltins(getClass().getClassLoader()));
    var runtimes = new RepositoryRuntimeRegistry(repositories, 0);
    var dispatcher = new RepositoryProtocolDispatcher(runtimes,
        List.of(new PluginProtocolDispatcher(plugins.repositoryProtocolHandlers(), List.of(host))));
    mvc = MockMvcBuilders.standaloneSetup(new RepositoryProtocolController(dispatcher))
        .addFilters(filter).build();
  }

  @AfterEach
  void close() {
    ReflectionTestUtils.invokeMethod(service, "close");
    plugins.close();
  }

  @Test
  void theBuiltInPluginIsDiscoveredThroughTheServiceLoader() {
    assertEquals(List.of("gitlfs-protocol"),
        plugins.loaded().plugins().stream().map(plugin -> plugin.descriptor().id()).toList());
    assertTrue(plugins.repositoryFormat("gitlfs").isPresent());
  }

  @Test
  void uploadVerifyAndDownloadAnObjectThroughThePlugin() throws Exception {
    // Batch upload: one lease, actions on this origin, no credentials reflected.
    MockHttpServletResponse batch = perform(batch(WRITER, "upload", OID, CONTENT.length));
    assertEquals(200, batch.getStatus());
    assertTrue(batch.getContentType().startsWith(LFS));
    assertEquals("no-store", batch.getHeader("Cache-Control"));
    JsonNode object = JSON.readTree(batch.getContentAsByteArray()).path("objects").get(0);
    JsonNode upload = object.path("actions").path("upload");
    assertEquals("http://localhost" + ROOT + OID, upload.path("href").asText());
    assertEquals(900, upload.path("expires_in").asInt());
    assertEquals("http://localhost" + ROOT + OID + "/verify",
        object.path("actions").path("verify").path("href").asText());
    assertFalse(object.path("authenticated").asBoolean(true));
    assertFalse(batch.getContentAsString().contains(WRITER));
    String uploadId = upload.path("header").path("X-KkRepo-Lfs-Upload").asText();

    // PUT: the client stream reaches the blob store unread, then the object is published.
    MockHttpServletResponse put = perform(streamedPut(WRITER, uploadId, CONTENT));
    assertEquals(200, put.getStatus(), put.getContentAsString());
    assertEquals("no-store", put.getHeader("Cache-Control"));
    assertEquals(0, consumedBeforeStorage.get());
    assertEquals(CONTENT.length, consumedByStorage.get());
    AssetRecord published = store.assets.findAssetByPath(1L, OID).orElseThrow();
    assertEquals(CONTENT.length, published.size());
    assertEquals("PUBLISHED", store.uploads.find(uploadId).orElseThrow().state());

    MockHttpServletResponse verified = perform(post(ROOT + OID + "/verify")
        .header("Authorization", WRITER).contentType(LFS)
        .content("{\"oid\":\"" + OID + "\",\"size\":" + CONTENT.length + "}"));
    assertEquals(200, verified.getStatus());
    assertEquals(JSON.readTree("{\"oid\":\"" + OID + "\",\"size\":" + CONTENT.length + "}"),
        JSON.readTree(verified.getContentAsByteArray()));
    assertEquals(415, perform(post(ROOT + OID + "/verify").header("Authorization", WRITER)
        .contentType("application/json").content("{}")).getStatus());

    // Repeated upload Batch: the stored object needs no action and is exactly {oid, size}.
    JsonNode repeated = JSON.readTree(perform(batch(WRITER, "upload", OID, CONTENT.length))
        .getContentAsByteArray()).path("objects").get(0);
    assertEquals(JSON.readTree("{\"oid\":\"" + OID + "\",\"size\":" + CONTENT.length + "}"), repeated);

    // Batch download as a read-only user.
    JsonNode download = JSON.readTree(perform(batch(READER, "download", OID, CONTENT.length))
        .getContentAsByteArray()).path("objects").get(0);
    assertEquals("http://localhost" + ROOT + OID, download.path("actions").path("download").path("href").asText());
    JsonNode missing = JSON.readTree(perform(batch(READER, "download", "0".repeat(64), 1))
        .getContentAsByteArray()).path("objects").get(0);
    assertEquals(404, missing.path("error").path("code").asInt());
    assertFalse(missing.has("actions"));

    String etag = "\"" + sha("SHA-1", CONTENT) + "\"";
    MockHttpServletResponse full = perform(get(ROOT + OID).header("Authorization", READER));
    assertEquals(200, full.getStatus());
    assertArrayEquals(CONTENT, full.getContentAsByteArray());
    assertEquals("application/octet-stream", full.getContentType());
    assertEquals("private, no-cache", full.getHeader("Cache-Control"));
    assertEquals("bytes", full.getHeader("Accept-Ranges"));
    assertEquals(etag, full.getHeader("ETag"));
    assertEquals(String.valueOf(CONTENT.length), full.getHeader("Content-Length"));
    assertTrue(full.getHeader("Last-Modified").endsWith(" GMT"));

    MockHttpServletResponse range = perform(get(ROOT + OID).header("Authorization", READER)
        .header("Range", "bytes=1-4"));
    assertEquals(206, range.getStatus());
    assertEquals("bytes 1-4/" + CONTENT.length, range.getHeader("Content-Range"));
    assertArrayEquals(java.util.Arrays.copyOfRange(CONTENT, 1, 5), range.getContentAsByteArray());
    MockHttpServletResponse suffix = perform(get(ROOT + OID).header("Authorization", READER)
        .header("Range", "bytes=-4"));
    assertArrayEquals(java.util.Arrays.copyOfRange(CONTENT, CONTENT.length - 4, CONTENT.length),
        suffix.getContentAsByteArray());
    MockHttpServletResponse unsatisfiable = perform(get(ROOT + OID).header("Authorization", READER)
        .header("Range", "bytes=999999-"));
    assertEquals(416, unsatisfiable.getStatus());
    assertEquals("bytes */" + CONTENT.length, unsatisfiable.getHeader("Content-Range"));

    MockHttpServletResponse notModified = perform(get(ROOT + OID).header("Authorization", READER)
        .header("If-None-Match", etag));
    assertEquals(304, notModified.getStatus());
    assertEquals(0, notModified.getContentAsByteArray().length);
    assertEquals(etag, notModified.getHeader("ETag"));

    MockHttpServletResponse headResponse = perform(head(ROOT + OID).header("Authorization", READER));
    assertEquals(200, headResponse.getStatus());
    assertEquals(String.valueOf(CONTENT.length), headResponse.getHeader("Content-Length"));
    assertEquals(0, headResponse.getContentAsByteArray().length);

    // Every download decision, including HEAD, 304 and 416, went through the download policy once.
    verify(downloads, times(6)).beforeReadFromRepository(published.id(), published.assetBlobId(), 1L);
  }

  @Test
  void deletionAndLocksAreAnsweredByThePluginWithTheGitLfsEnvelope() throws Exception {
    MockHttpServletResponse deletion = perform(delete(ROOT + OID).header("Authorization", WRITER));
    assertEquals(405, deletion.getStatus());
    assertEquals("GET,HEAD,PUT", deletion.getHeader("Allow"));
    assertTrue(deletion.getContentType().startsWith(LFS));
    assertEquals("Use the administration API to delete LFS assets",
        JSON.readTree(deletion.getContentAsByteArray()).path("message").asText());

    MockHttpServletResponse verifyHead = perform(head(ROOT + OID + "/verify").header("Authorization", READER));
    assertEquals(405, verifyHead.getStatus());
    assertEquals("POST", verifyHead.getHeader("Allow"));
    assertEquals(0, verifyHead.getContentAsByteArray().length);

    MockHttpServletResponse locks = perform(post(ROOT + "info/lfs/locks/verify")
        .header("Authorization", WRITER).contentType(LFS).content("{}"));
    assertEquals(501, locks.getStatus());
    assertEquals("Git LFS file locking is not supported",
        JSON.readTree(locks.getContentAsByteArray()).path("message").asText());
  }

  @Test
  void permissionsStayInTheHostSecurityFilter() throws Exception {
    MockHttpServletResponse anonymous = perform(get(ROOT + OID));
    assertEquals(401, anonymous.getStatus());
    assertEquals("Basic realm=\"Git LFS\"", anonymous.getHeader("LFS-Authenticate"));
    assertEquals("Basic realm=\"Git LFS\"", anonymous.getHeader("WWW-Authenticate"));
    assertEquals(LFS, anonymous.getContentType());
    assertEquals(401, perform(batch(null, "upload", OID, CONTENT.length)).getStatus());

    // A read-only user gets no upload action, and cannot use the writer's action either.
    JsonNode denied = JSON.readTree(perform(batch(READER, "upload", OID, CONTENT.length))
        .getContentAsByteArray()).path("objects").get(0);
    assertEquals(403, denied.path("error").path("code").asInt());
    assertFalse(denied.has("actions"));
    String uploadId = JSON.readTree(perform(batch(WRITER, "upload", OID, CONTENT.length))
        .getContentAsByteArray()).path("objects").get(0)
        .path("actions").path("upload").path("header").path("X-KkRepo-Lfs-Upload").asText();
    MockHttpServletResponse forbidden = perform(streamedPut(READER, uploadId, CONTENT));
    assertEquals(403, forbidden.getStatus());
    assertEquals(-1, consumedBeforeStorage.get());
    assertTrue(store.assets.findAssetByPath(1L, OID).isEmpty());
    verify(downloads, never()).beforeReadFromRepository(anyLong(), anyLong(), anyLong());
  }

  private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
    var result = mvc.perform(request).andReturn();
    if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
    return result.getResponse();
  }

  private static MockHttpServletRequestBuilder batch(String authorization, String operation, String oid,
      long size) throws Exception {
    MockHttpServletRequestBuilder request = post(ROOT + GitLfsProtocol.BATCH_PATH).contentType(LFS)
        .content(JSON.writeValueAsBytes(Map.of("operation", operation,
            "objects", List.of(Map.of("oid", oid, "size", size)))));
    return authorization == null ? request : request.header("Authorization", authorization);
  }

  /** PUT whose body stream counts what was read, with header names as some clients send them. */
  private static RequestBuilder streamedPut(String authorization, String uploadId, byte[] body) {
    return context -> {
      CountingRequest request = new CountingRequest(context, ROOT + OID, body);
      request.addHeader("Authorization", authorization);
      request.addHeader("x-kkrepo-lfs-upload", uploadId);
      request.addHeader("content-length", String.valueOf(body.length));
      request.setContentType("application/octet-stream");
      return request;
    };
  }

  private static Optional<AuthenticatedSubject> authenticated(String authorization) {
    if (WRITER.equals(authorization)) return Optional.of(user("alice"));
    if (READER.equals(authorization)) return Optional.of(user("bob"));
    return Optional.empty();
  }

  private static AuthenticatedSubject user(String user) {
    return new AuthenticatedSubject("default", user, "default", null,
        new PermissionSubject("default", user, Set.of(), null));
  }

  private static String sha(String algorithm, byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static final class CountingRequest extends MockHttpServletRequest {
    private final byte[] body;
    private CountingInput input;

    CountingRequest(ServletContext context, String uri, byte[] body) {
      super(context, "PUT", uri);
      this.body = body;
      setContent(body);
    }

    @Override
    public ServletInputStream getInputStream() {
      if (input == null) {
        input = new CountingInput(new java.io.ByteArrayInputStream(body));
        CLIENT_BODY.set(input);
      }
      return input;
    }
  }

  static final class CountingInput extends ServletInputStream {
    private final InputStream delegate;
    private long consumed;

    CountingInput(InputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) consumed++;
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = delegate.read(bytes, offset, length);
      if (read > 0) consumed += read;
      return read;
    }

    @Override
    public boolean isFinished() {
      try {
        return delegate.available() == 0;
      } catch (IOException unavailable) {
        return true;
      }
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException();
    }
  }

  /** File storage that records how much of the client stream was read before it started. */
  private record ObservedStorage(BlobStorage delegate, AtomicLong consumedBeforeStorage,
      AtomicLong consumedByStorage) implements BlobStorage {
    @Override
    public BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
      return delegate.prepareVerifiedUpload(repository, sha256, size);
    }

    @Override
    public VerifiedBlobDigests uploadVerified(BlobReference target, InputStream content,
        Consumer<String> multipartStarted) {
      CountingInput client = CLIENT_BODY.get();
      consumedBeforeStorage.set(client.consumed);
      try {
        return delegate.uploadVerified(target, content, multipartStarted);
      } finally {
        consumedByStorage.set(client.consumed);
      }
    }

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size,
        String sha256) {
      return delegate.put(repository, logicalPath, content, size, sha256);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return delegate.get(reference);
    }

    @Override
    public Optional<InputStream> getRange(BlobReference reference, long start, long length) {
      return delegate.getRange(reference, start, length);
    }

    @Override
    public boolean exists(BlobReference reference) {
      return delegate.exists(reference);
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return delegate.stat(reference);
    }

    @Override
    public void delete(BlobReference reference) {
      delegate.delete(reference);
    }
  }

  /** In-memory asset and upload-lease persistence with the semantics the hosted service relies on. */
  private static final class Store {
    private final Map<String, AssetRecord> assetsByPath = new ConcurrentHashMap<>();
    private final Map<Long, AssetBlobRecord> blobsById = new ConcurrentHashMap<>();
    private final Map<String, GitLfsDao.Upload> leases = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private final AssetDao assets = mock(AssetDao.class);
    private final GitLfsDao uploads = mock(GitLfsDao.class);

    Store() {
      when(assets.findAssetsByPaths(anyLong(), any())).thenAnswer(call -> {
        Map<String, AssetRecord> found = new java.util.HashMap<>();
        for (Object path : (java.util.Collection<?>) call.getArgument(1)) {
          AssetRecord asset = assetsByPath.get((String) path);
          if (asset != null) found.put((String) path, asset);
        }
        return found;
      });
      when(assets.findAssetByPath(anyLong(), anyString()))
          .thenAnswer(call -> Optional.ofNullable(assetsByPath.get((String) call.getArgument(1))));
      when(assets.findBlobById(anyLong()))
          .thenAnswer(call -> Optional.ofNullable(blobsById.get((Long) call.getArgument(0))));
      when(assets.insertBlobOrFindExisting(any())).thenAnswer(call -> {
        AssetBlobRecord blob = call.getArgument(0);
        AssetBlobRecord stored = new AssetBlobRecord(ids.incrementAndGet(), blob.blobStoreId(), blob.blobRef(),
            blob.blobRefHash(), blob.objectKey(), blob.objectKeyHash(), blob.sha1(), blob.sha256(), blob.md5(),
            blob.size(), blob.contentType(), blob.createdBy(), blob.createdByIp(), blob.blobCreatedAt(),
            blob.blobUpdatedAt(), blob.attributes());
        blobsById.put(stored.id(), stored);
        return stored;
      });
      when(assets.insertAsset(any())).thenAnswer(call -> {
        AssetRecord asset = call.getArgument(0);
        long id = ids.incrementAndGet();
        assetsByPath.put(asset.path(), new AssetRecord(id, asset.repositoryId(), asset.componentId(),
            asset.assetBlobId(), asset.format(), asset.path(), asset.pathHash(), asset.name(), asset.kind(),
            asset.contentType(), asset.size(), null, Instant.parse("2026-01-01T00:00:00Z"), asset.attributes()));
        return id;
      });
      when(uploads.create(anyLong(), anyString(), anyLong(), anyString(), anyLong())).thenAnswer(call -> {
        var upload = new GitLfsDao.Upload(UUID.randomUUID().toString(), call.getArgument(0), call.getArgument(1),
            call.getArgument(2), call.getArgument(3), call.getArgument(4), 1, 1, Instant.now(),
            Instant.now().plusSeconds(900), "READY", null, null, null, null);
        leases.put(upload.id(), upload);
        return upload;
      });
      when(uploads.find(anyString())).thenAnswer(call -> Optional.ofNullable(leases.get((String) call.getArgument(0))));
      when(uploads.claim(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
        var lease = leases.get((String) call.getArgument(0));
        if (lease == null || !lease.state().equals("READY")) return Optional.empty();
        var claimed = state(lease, "UPLOADING", call.getArgument(2), call.getArgument(3));
        leases.put(claimed.id(), claimed);
        return Optional.of(claimed);
      });
      when(uploads.renew(anyString(), anyLong())).thenReturn(true);
      when(uploads.lockForPublication(anyString(), anyLong())).thenReturn(true);
      org.mockito.Mockito.doAnswer(call -> {
        var lease = leases.get((String) call.getArgument(0));
        leases.put(lease.id(), state(lease, "PUBLISHED", lease.blobRef(), lease.objectKey()));
        return null;
      }).when(uploads).published(anyString(), anyLong(), anyLong());
    }

    private static GitLfsDao.Upload state(GitLfsDao.Upload lease, String state, String blobRef, String objectKey) {
      return new GitLfsDao.Upload(lease.id(), lease.repositoryId(), lease.oid(), lease.size(), lease.subjectKey(),
          lease.blobStoreId(), lease.generation(), lease.fence() + 1, lease.repositoryVersion(), lease.expiresAt(),
          state, blobRef, objectKey, lease.multipartId(), lease.cleanupToken());
    }
  }
}
