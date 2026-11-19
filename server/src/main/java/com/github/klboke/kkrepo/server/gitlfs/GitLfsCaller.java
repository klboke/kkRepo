package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Identity of the client behind one Git LFS request, resolved by the host from the authenticated
 * request so the hosted service stays independent of the servlet API.
 *
 * @param subject subject authenticated by the security filter
 * @param anonymous whether the security filter fell back to the anonymous subject
 * @param clientAddress resolved client address, recorded with published blobs
 * @param reauthenticate authenticates the presented credentials again, without session fallback
 */
public record GitLfsCaller(
    AuthenticatedSubject subject,
    boolean anonymous,
    String clientAddress,
    Supplier<Optional<AuthenticatedSubject>> reauthenticate) {

  public GitLfsCaller {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(reauthenticate, "reauthenticate");
  }
}
