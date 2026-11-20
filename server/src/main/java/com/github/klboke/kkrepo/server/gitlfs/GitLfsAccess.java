package com.github.klboke.kkrepo.server.gitlfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.VerifiedBlobReader;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Authenticates Batch by operation; content-selector authorization is evaluated per object. */
@Component
public class GitLfsAccess {
  public static final String ANONYMOUS_ATTRIBUTE = GitLfsAccess.class.getName() + ".anonymous";
  public static final String BATCH_ATTRIBUTE = GitLfsAccess.class.getName() + ".batch";
  /** Raw Batch body consumed for authorization, replayed to the protocol handler. */
  public static final String BATCH_BODY_ATTRIBUTE = GitLfsAccess.class.getName() + ".batchBody";
  private final RepositoryDao repositories;
  private final SecurityAuthenticationService authentication;
  private final AccessDecisionService permissions;
  private final ObjectMapper json;
  private final long maxObjectBytes;

  public GitLfsAccess(SecurityAuthenticationService authentication, AccessDecisionService permissions,
      RepositoryDao repositories, ObjectMapper json, @Value("${kkrepo.gitlfs.max-object-bytes:34359738368}") long maxObjectBytes) {
    if (maxObjectBytes < 0 || maxObjectBytes > VerifiedBlobReader.MAX_BYTES) {
      throw new IllegalArgumentException("Invalid Git LFS maximum object size");
    }
    this.repositories = repositories;
    this.authentication = authentication;
    this.permissions = permissions;
    this.json = json;
    this.maxObjectBytes = maxObjectBytes;
  }

  public long maxObjectBytes() { return maxObjectBytes; }

  public boolean authorize(HttpServletRequest request, HttpServletResponse response,
      RepositoryRecord repository, String path) throws IOException {
    try {
      boolean batchRoute = "POST".equals(request.getMethod()) && GitLfsProtocol.BATCH_PATH.equals(path);
      GitLfsProtocol.Batch batch = null;
      if (batchRoute) {
        requireJson(request.getContentType());
        byte[] body = request.getInputStream().readNBytes(GitLfsProtocol.MAX_BODY_BYTES + 1);
        batch = GitLfsProtocol.parseBatch(new ByteArrayInputStream(body), maxObjectBytes);
        request.setAttribute(BATCH_ATTRIBUTE, batch);
        request.setAttribute(BATCH_BODY_ATTRIBUTE, body);
      }
      boolean read = batch == null
          ? "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
          : !batch.upload();
      boolean explicit = authentication.hasPresentedCredentials(request);
      // Repository mutations use client credentials. A browser cookie alone cannot authorize them.
      if (!read && !explicit) throw new GitLfsException(401, "Authentication required");
      Optional<AuthenticatedSubject> subject = authenticate(request);
      if (subject.isEmpty() && read && !explicit) {
        subject = authentication.authenticateAnonymousFresh();
        request.setAttribute(ANONYMOUS_ATTRIBUTE, true);
      }
      if (subject.isEmpty()) throw new GitLfsException(401, "Authentication required");
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject.get());
      // The catalog is a local hot cache. Actions must observe current repository state on any pod.
      repository = repositories.findById(repository.id())
          .orElseThrow(() -> new GitLfsException(404, "Repository does not exist"));
      request.setAttribute(com.github.klboke.kkrepo.server.security.RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, repository);
      if (!repository.online()) throw new GitLfsException(503, "Repository is offline");
      if (!batchRoute && !path.startsWith("info/lfs/locks")) {
        String oid = GitLfsProtocol.objectOid(path);
        if (!allowed(subject.get(), repository.name(), oid, read ? PermissionAction.READ : PermissionAction.ADD)) {
          throw new GitLfsException(explicit ? 403 : 401, "Permission denied");
        }
      }
      return true;
    } catch (GitLfsException error) {
      response.setStatus(error.status());
      response.setContentType(GitLfsProtocol.MEDIA_TYPE);
      response.setHeader("Cache-Control", "no-store");
      if (error.status() == 401) {
        response.setHeader("WWW-Authenticate", "Basic realm=\"Git LFS\"");
        response.setHeader("LFS-Authenticate", "Basic realm=\"Git LFS\"");
      }
      if (!"HEAD".equals(request.getMethod())) {
        json.writeValue(response.getOutputStream(), Map.of("message", error.getMessage()));
      }
      return false;
    }
  }

  public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
    // Explicit invalid credentials must not fall back to a simultaneously supplied session cookie.
    HttpServletRequest credentialsOnly = new HttpServletRequestWrapper(request) {
      @Override public HttpSession getSession(boolean create) { return null; }
      @Override public HttpSession getSession() { return null; }
    };
    return authentication.authenticateFresh(authentication.hasPresentedCredentials(request) ? credentialsOnly : request);
  }

  public boolean allowed(AuthenticatedSubject subject, String repository, String oid, PermissionAction action) {
    return allowedObjects(subject, repository, Set.of(oid), action).contains(oid);
  }

  public Set<String> allowedObjects(AuthenticatedSubject subject, String repository,
      Collection<String> oids, PermissionAction action) {
    var requested = oids.stream().distinct()
        .map(oid -> new RepositoryPermission(repository, RepositoryFormat.GITLFS, oid, action)).toList();
    return permissions.decideAllFresh(subject.permissionSubject(), requested).entrySet().stream()
        .filter(entry -> entry.getValue().allowed()).map(entry -> entry.getKey().pathPattern())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The request to pass down the filter chain after {@link #authorize}: a Batch body consumed for
   * authorization is replayed, bounded, so the protocol handler parses the same bytes.
   */
  public static HttpServletRequest authorizedRequest(HttpServletRequest request) {
    if (!(request.getAttribute(BATCH_BODY_ATTRIBUTE) instanceof byte[] body)) return request;
    return new HttpServletRequestWrapper(request) {
      @Override public ServletInputStream getInputStream() { return new ReplayedBody(body); }
      @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
      }
      @Override public int getContentLength() { return body.length; }
      @Override public long getContentLengthLong() { return body.length; }
    };
  }

  public void requireUpload(GitLfsCaller caller, String repository, String oid, String owner) {
    AuthenticatedSubject fresh = caller.reauthenticate().get()
        .orElseThrow(() -> new GitLfsException(401, "Authentication required"));
    if (!subjectKey(fresh).equals(owner) || !allowed(fresh, repository, oid, PermissionAction.ADD)) {
      throw new GitLfsException(403, "Upload permission denied");
    }
  }

  /** Identity of the client of an authorized Git LFS request. */
  public GitLfsCaller caller(HttpServletRequest request) {
    return new GitLfsCaller(subject(request),
        Boolean.TRUE.equals(request.getAttribute(ANONYMOUS_ATTRIBUTE)),
        ClientAddress.from(request), () -> authenticate(request));
  }

  public static AuthenticatedSubject subject(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject subject) return subject;
    throw new GitLfsException(401, "Authentication required");
  }

  public static String subjectKey(AuthenticatedSubject subject) {
    String identity = String.join("\n", subject.source(), subject.userId(),
        String.valueOf(subject.realmId()), String.valueOf(subject.apiKeyId()),
        String.valueOf(subject.permissionSubject().tokenId()));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  public static void requireJson(String contentType) {
    try {
      if (contentType != null && MediaType.parseMediaType(contentType)
          .isCompatibleWith(MediaType.parseMediaType(GitLfsProtocol.MEDIA_TYPE))) return;
    } catch (IllegalArgumentException ignored) { }
    throw new GitLfsException(415, "Expected " + GitLfsProtocol.MEDIA_TYPE);
  }

  private static final class ReplayedBody extends ServletInputStream {
    private final ByteArrayInputStream input;

    ReplayedBody(byte[] body) { this.input = new ByteArrayInputStream(body); }
    @Override public int read() { return input.read(); }
    @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
    @Override public boolean isFinished() { return input.available() == 0; }
    @Override public boolean isReady() { return true; }
    @Override public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException("Replayed Git LFS Batch bodies are read synchronously");
    }
  }
}
