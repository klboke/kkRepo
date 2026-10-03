package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.routing.*;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class GitLfsProtocolHandler implements RepositoryProtocolHandler {
  private final GitLfsHostedService service;
  private final BlobStorageRegistry storages;
  private final ArtifactDownloadPolicy downloads;
  private final ObjectMapper json;

  public GitLfsProtocolHandler(GitLfsHostedService service, BlobStorageRegistry storages,
      ArtifactDownloadPolicy downloads, ObjectMapper json) {
    this.service = service;
    this.storages = storages;
    this.downloads = downloads;
    this.json = json;
  }

  @Override public Collection<RepositoryProtocolRoute> routes() {
    return List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)
        .stream().map(method -> RepositoryProtocolRoute.anyPath(RepositoryFormat.GITLFS, RepositoryType.HOSTED, method, 0)).toList();
  }

  @Override public ResponseEntity<?> handle(RepositoryProtocolRequest context) throws IOException {
    var request = context.servletRequest();
    try {
      if (!context.runtime().online()) throw new GitLfsException(503, "Repository is offline");
      String path = context.path();
      if (path.startsWith("info/lfs/locks")) throw new GitLfsException(501, "Git LFS file locking is not supported");
      if (context.method() == HttpMethod.POST && GitLfsProtocol.BATCH_PATH.equals(path)) {
        Object value = request.getAttribute(GitLfsAccess.BATCH_ATTRIBUTE);
        if (!(value instanceof GitLfsProtocol.Batch batch)) throw new GitLfsException(422, "Missing Batch request");
        return json(200, service.batch(context.runtime(), batch, request));
      }
      String oid = GitLfsProtocol.objectOid(path);
      if (path.endsWith("/verify")) {
        if (context.method() != HttpMethod.POST) throw new GitLfsException(405, "Method not allowed");
        return json(200, service.verify(context.runtime(), oid, request));
      }
      if (context.method() == HttpMethod.PUT) {
        service.put(context.runtime(), oid, request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
      }
      if (context.method() != HttpMethod.GET && context.method() != HttpMethod.HEAD) {
        throw new GitLfsException(405, "Use the administration API to delete LFS assets");
      }
      var asset = service.asset(context.runtime().id(), oid);
      var blob = service.blob(asset);
      downloads.beforeReadFromRepository(asset.id(), blob.id(), context.runtime().id());
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
      headers.set(HttpHeaders.CACHE_CONTROL, "private, no-cache");
      headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
      ConditionalResponses.addValidators(headers, blob.sha1(), asset.lastUpdatedAt());
      if (ConditionalResponses.shouldReturnNotModified(request, 200, blob.sha1(), asset.lastUpdatedAt())) {
        return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
      }
      headers.setContentLength(blob.size());
      if (context.method() == HttpMethod.HEAD) return new ResponseEntity<>(null, headers, HttpStatus.OK);
      var storage = storages.forBlobStoreId(blob.blobStoreId());
      var reference = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
      String range = request.getHeader(HttpHeaders.RANGE);
      String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
      boolean useRange = range != null && !range.contains(",") && (ifRange == null
          || ifRange.equals(headers.getETag()) || ifRange.equals(headers.getFirst(HttpHeaders.LAST_MODIFIED)));
      InputStream body;
      int status = 200;
      if (useRange) {
        long start;
        long end;
        try {
          List<HttpRange> ranges = HttpRange.parseRanges(range);
          if (ranges.size() != 1 || blob.size() == 0) throw new IllegalArgumentException("Unsatisfiable range");
          start = ranges.getFirst().getRangeStart(blob.size());
          end = ranges.getFirst().getRangeEnd(blob.size());
          if (start >= blob.size() || end < start) throw new IllegalArgumentException("Unsatisfiable range");
        } catch (IllegalArgumentException invalid) {
          return ResponseEntity.status(416).header(HttpHeaders.CONTENT_RANGE, "bytes */" + blob.size()).build();
        }
        body = storage.getRange(reference, start, end - start + 1)
            .orElseThrow(() -> new GitLfsException(404, "Object content is unavailable"));
        headers.setContentLength(end - start + 1);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + blob.size());
        status = 206;
      } else {
        body = storage.get(reference).orElseThrow(() -> new GitLfsException(404, "Object content is unavailable"));
      }
      StreamingResponseBody streaming = output -> {
        try (body) { body.transferTo(output); }
      };
      return ResponseEntity.status(status).headers(headers).body(streaming);
    } catch (GitLfsException error) {
      var builder = ResponseEntity.status(error.status()).contentType(MediaType.parseMediaType(GitLfsProtocol.MEDIA_TYPE))
          .header(HttpHeaders.CACHE_CONTROL, "no-store");
      if (error.status() == 401) builder.header("LFS-Authenticate", "Basic realm=\"Git LFS\"")
          .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Git LFS\"");
      if (error.status() == 429 || error.status() == 503) builder.header(HttpHeaders.RETRY_AFTER, "5");
      Map<String, Object> body = Map.of("message", error.getMessage());
      if (context.method() == HttpMethod.HEAD) return builder.build();
      if (context.method() == HttpMethod.GET) {
        return builder.body((StreamingResponseBody) output -> json.writeValue(output, body));
      }
      return builder.body(body);
    }
  }

  private static ResponseEntity<?> json(int status, Map<String, Object> body) {
    return ResponseEntity.status(status).contentType(MediaType.parseMediaType(GitLfsProtocol.MEDIA_TYPE))
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
  }
}
