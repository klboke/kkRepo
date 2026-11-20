package com.github.klboke.kkrepo.protocol.gitlfs;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata of a stored Git LFS object, used for download headers and validators.
 *
 * @param etag strong validator of the content (the blob SHA-1), or {@code null}
 * @param lastModified last update of the asset, or {@code null}
 */
public record GitLfsStoredObject(String oid, long size, String etag, Instant lastModified) {

  public GitLfsStoredObject {
    Objects.requireNonNull(oid, "oid");
    if (size < 0) {
      throw new IllegalArgumentException("Object size must not be negative");
    }
  }
}
