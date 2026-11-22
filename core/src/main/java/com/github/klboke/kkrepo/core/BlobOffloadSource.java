package com.github.klboke.kkrepo.core;

import java.util.Optional;

/** An optional capability carried by a full, immutable blob stream. */
public interface BlobOffloadSource {
  Optional<Target> offloadTarget(long responseLength);

  record Target(long storeId, String objectKey) {}
}
