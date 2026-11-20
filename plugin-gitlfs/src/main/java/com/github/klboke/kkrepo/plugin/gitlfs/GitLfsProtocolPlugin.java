package com.github.klboke.kkrepo.plugin.gitlfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsByteRange;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsHostOperations;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsStoredObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Git LFS Batch/basic protocol for hosted repositories, registered as a built-in plugin.
 *
 * <p>The plugin owns every Git LFS route: path and JSON parsing, the Batch response, the Git LFS
 * error envelope and headers, and conditional and range downloads. Permissions, leases, quotas,
 * verified writes, transactions, audit, download policy and blob references stay in the host behind
 * {@link GitLfsHostOperations}. Requests reach the plugin only after the host authenticated and
 * authorized them and applied the repository path and routing policies.
 */
public final class GitLfsProtocolPlugin
    implements KkRepoPlugin, RepositoryProtocolHandler, RepositoryFormatProvider {
  static final FormatId FORMAT = new FormatId("gitlfs");
  static final String UPLOAD_HEADER = "X-KkRepo-Lfs-Upload";
  private static final String AUTHENTICATE = "Basic realm=\"Git LFS\"";
  private static final List<RepositoryProtocolRoute> ROUTES = List.of("GET", "HEAD", "POST", "PUT", "DELETE")
      .stream()
      .map(method -> RepositoryProtocolRoute.anyPath(FORMAT, RepositoryKind.HOSTED, method, 0))
      .toList();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "gitlfs-protocol",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("repository.format", "repository.handler"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRepositoryFormat(this);
    context.registerRepositoryProtocolHandler(this);
  }

  @Override
  public FormatId id() {
    return FORMAT;
  }

  @Override
  public RepositoryFormatDescriptor formatDescriptor() {
    return new RepositoryFormatDescriptor(FORMAT, "Git LFS",
        "Git LFS Batch API with the basic transfer adapter", Set.of("hosted"));
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return ROUTES;
  }

  /** Object uploads are streamed to the host; JSON bodies are read with a bounded parser. */
  @Override
  public boolean streamsRequestBody() {
    return true;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    String method = request.method();
    String path = request.path();
    try {
      if (!request.repository().online()) throw new GitLfsException(503, "Repository is offline");
      if (path.startsWith("info/lfs/locks")) {
        throw new GitLfsException(501, "Git LFS file locking is not supported");
      }
      if (method.equals("POST") && GitLfsProtocol.BATCH_PATH.equals(path)) return batch(request);
      String oid = GitLfsProtocol.objectOid(path);
      if (path.endsWith("/verify")) {
        if (!method.equals("POST")) throw new GitLfsException(405, "Method not allowed");
        return verify(request, oid);
      }
      if (method.equals("PUT")) return upload(request, oid);
      if (!method.equals("GET") && !method.equals("HEAD")) {
        throw new GitLfsException(405, "Use the administration API to delete LFS assets");
      }
      return download(request, oid);
    } catch (GitLfsException error) {
      return error(request, error);
    }
  }

  private RepositoryResponse batch(RepositoryRequest request) throws IOException {
    GitLfsHostOperations host = host(request);
    GitLfsProtocol.Batch batch;
    try (InputStream body = request.openBody()) {
      batch = GitLfsProtocol.parseBatch(body, host.maxObjectBytes());
    }
    GitLfsBatchResult result = host.batch(request.context(), batch);
    ObjectNode root = JSON.createObjectNode();
    root.put("transfer", "basic");
    ArrayNode objects = root.putArray("objects");
    for (GitLfsBatchResult.ObjectResult object : result.objects()) {
      objects.add(batchObject(result.objectBaseUrl(), object));
    }
    return json(root);
  }

  private static ObjectNode batchObject(String base, GitLfsBatchResult.ObjectResult object) {
    ObjectNode item = JSON.createObjectNode();
    item.put("oid", object.oid());
    item.put("size", object.size());
    switch (object.outcome()) {
      case ERROR -> {
        ObjectNode error = item.putObject("error");
        error.put("code", object.errorCode());
        error.put("message", object.errorMessage());
      }
      case PRESENT -> {
        // An object that is already stored needs no action.
      }
      case UPLOAD -> {
        ObjectNode actions = item.putObject("actions");
        ObjectNode upload = actions.putObject("upload");
        upload.put("href", base + object.oid());
        upload.putObject("header").put(UPLOAD_HEADER, object.uploadId());
        upload.put("expires_in", object.expiresInSeconds());
        ObjectNode verify = actions.putObject("verify");
        verify.put("href", base + object.oid() + "/verify");
        verify.put("expires_in", object.expiresInSeconds());
        // No Authorization is reflected into actions: clients reuse their credentials for the origin.
        item.put("authenticated", false);
      }
      case DOWNLOAD -> {
        item.putObject("actions").putObject("download").put("href", base + object.oid());
        item.put("authenticated", false);
      }
    }
    return item;
  }

  private RepositoryResponse verify(RepositoryRequest request, String oid) throws IOException {
    if (!GitLfsHttp.isLfsJson(request.header("Content-Type"))) {
      throw new GitLfsException(415, "Expected " + GitLfsProtocol.MEDIA_TYPE);
    }
    GitLfsHostOperations host = host(request);
    GitLfsProtocol.ObjectRequest object;
    try (InputStream body = request.openBody()) {
      object = GitLfsProtocol.parseVerify(body, host.maxObjectBytes());
    }
    if (!oid.equals(object.oid())) throw new GitLfsException(422, "OID does not match verify URL");
    host.verify(request.context(), oid, object.size());
    ObjectNode verified = JSON.createObjectNode();
    verified.put("oid", oid);
    verified.put("size", object.size());
    return json(verified);
  }

  private RepositoryResponse upload(RepositoryRequest request, String oid) throws IOException {
    try (InputStream body = request.openBody()) {
      host(request).upload(request.context(), oid, request.header(UPLOAD_HEADER),
          contentLength(request.header("Content-Length")), body);
    }
    return new RepositoryResponse(200, Map.of("Cache-Control", List.of("no-store")), null);
  }

  private RepositoryResponse download(RepositoryRequest request, String oid) throws IOException {
    GitLfsHostOperations host = host(request);
    GitLfsStoredObject object = host.find(request.context(), oid);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Content-Type", List.of("application/octet-stream"));
    headers.put("Cache-Control", List.of("private, no-cache"));
    headers.put("Accept-Ranges", List.of("bytes"));
    String etag = object.etag() == null ? null : GitLfsHttp.entityTag(object.etag());
    String lastModified = object.lastModified() == null ? null : GitLfsHttp.httpDate(object.lastModified());
    if (etag != null) headers.put("ETag", List.of(etag));
    if (lastModified != null) headers.put("Last-Modified", List.of(lastModified));
    if (GitLfsHttp.notModified(request.header("If-None-Match"), request.header("If-Modified-Since"),
        object.etag(), object.lastModified())) {
      return new RepositoryResponse(304, headers, null);
    }
    headers.put("Content-Length", List.of(Long.toString(object.size())));
    if (request.method().equals("HEAD")) return new RepositoryResponse(200, headers, null);
    String range = request.header("Range");
    String ifRange = request.header("If-Range");
    boolean useRange = range != null && !range.contains(",")
        && (ifRange == null || ifRange.equals(etag) || ifRange.equals(lastModified));
    int status = 200;
    InputStream content;
    if (useRange) {
      GitLfsByteRange bytes = GitLfsHttp.singleRange(range, object.size());
      if (bytes == null) {
        return new RepositoryResponse(416,
            Map.of("Content-Range", List.of("bytes */" + object.size())), null);
      }
      content = host.open(request.context(), object, bytes);
      headers.put("Content-Length", List.of(Long.toString(bytes.length())));
      headers.put("Content-Range",
          List.of("bytes " + bytes.start() + "-" + bytes.end() + "/" + object.size()));
      status = 206;
    } else {
      content = host.open(request.context(), object, null);
    }
    return RepositoryResponse.streaming(status, headers, output -> {
      try (content) {
        content.transferTo(output);
      }
    });
  }

  private static RepositoryResponse error(RepositoryRequest request, GitLfsException error)
      throws IOException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Content-Type", List.of(GitLfsProtocol.MEDIA_TYPE));
    headers.put("Cache-Control", List.of("no-store"));
    if (error.status() == 401) {
      headers.put("LFS-Authenticate", List.of(AUTHENTICATE));
      headers.put("WWW-Authenticate", List.of(AUTHENTICATE));
    }
    if (error.status() == 429 || error.status() == 503) headers.put("Retry-After", List.of("5"));
    if (error.status() == 405) {
      headers.put("Allow", List.of(request.path().endsWith("/verify") ? "POST" : "GET,HEAD,PUT"));
    }
    if (request.method().equals("HEAD")) return new RepositoryResponse(error.status(), headers, null);
    ObjectNode body = JSON.createObjectNode();
    body.put("message", error.getMessage());
    return new RepositoryResponse(error.status(), headers, JSON.writeValueAsBytes(body));
  }

  private static RepositoryResponse json(ObjectNode body) throws IOException {
    return new RepositoryResponse(200, Map.of(
        "Content-Type", List.of(GitLfsProtocol.MEDIA_TYPE),
        "Cache-Control", List.of("no-store")), JSON.writeValueAsBytes(body));
  }

  private static long contentLength(String header) {
    if (header == null) return -1;
    try {
      long length = Long.parseLong(header.trim());
      return length >= 0 ? length : -1;
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static GitLfsHostOperations host(RepositoryRequest request) {
    return request.context().service(GitLfsHostOperations.class)
        .orElseThrow(() -> new IllegalStateException("Git LFS host operations are not available"));
  }
}
