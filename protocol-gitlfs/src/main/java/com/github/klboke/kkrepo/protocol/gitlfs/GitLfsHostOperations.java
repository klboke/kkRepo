package com.github.klboke.kkrepo.protocol.gitlfs;

import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Host operations behind the Git LFS protocol plugin.
 *
 * <p>The plugin owns the HTTP surface: routes, path and JSON parsing, the Batch response shape, the
 * error envelope, and conditional and range semantics. The host owns what must stay consistent
 * across replicas and formats: per-object permissions, upload leases and quotas, verified blob
 * writes, publication transactions, audit, download policy and blob references. Authentication and
 * the Batch-level authorization stay in the host security filter, before the plugin is called.
 *
 * <p>Each call receives the {@link RepositoryContext} of the current request. The host resolves the
 * caller identity, client address and public base URL from it; none of them crosses the plugin
 * boundary. Failures a client must see are reported as {@link GitLfsException}.
 */
public interface GitLfsHostOperations extends PluginHostService {

  /** Largest object size, in bytes, that the host accepts in Batch and verify requests. */
  long maxObjectBytes();

  /**
   * Decides every Batch object: per-object permission, write policy, stored-size consistency,
   * upload leases for missing objects and download availability for stored ones.
   *
   * @throws GitLfsException when the whole request is rejected, for example 401 when an anonymous
   *     caller may read none of the objects, or when the Batch is not the one the security filter
   *     authorized
   */
  GitLfsBatchResult batch(RepositoryContext repository, GitLfsProtocol.Batch batch);

  /** Confirms that {@code oid} is published with {@code size} bytes (404 or 422 otherwise). */
  void verify(RepositoryContext repository, String oid, long size);

  /**
   * Streams the bytes of an upload action issued by {@link #batch}. The host re-checks the lease
   * owner and permission, verifies size and SHA-256 while streaming, then publishes the object.
   *
   * @param uploadId value of the {@code X-KkRepo-Lfs-Upload} action header, or {@code null}
   * @param contentLength declared request length, or {@code -1} when unknown
   * @param body unbuffered request body
   */
  void upload(RepositoryContext repository, String oid, String uploadId, long contentLength,
      InputStream body) throws IOException;

  /**
   * Resolves a stored object for a download, after the host download policy allowed the read. The
   * policy runs once per call, including for HEAD and conditional requests.
   */
  GitLfsStoredObject find(RepositoryContext repository, String oid);

  /**
   * Opens the content of an object that {@link #find} resolved for the same request.
   *
   * @param range bytes to read, or {@code null} for the whole object
   * @throws GitLfsException 404 when the content is unavailable
   */
  InputStream open(RepositoryContext repository, GitLfsStoredObject object, GitLfsByteRange range)
      throws IOException;

  /**
   * Offers an authorized full-object download to a trusted gateway. Returns true when the host
   * handed the response off; the plugin must then return headers only (no streaming body).
   * Implementations may mutate {@code headers} to remove Content-Length and add the offload header.
   */
  default boolean offload(RepositoryContext repository, int status,
      Map<String, List<String>> headers, InputStream body) {
    return false;
  }
}
