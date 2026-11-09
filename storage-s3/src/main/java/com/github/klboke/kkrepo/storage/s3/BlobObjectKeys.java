package com.github.klboke.kkrepo.storage.s3;

/**
 * S3-local view of the shared key layout in {@link com.github.klboke.kkrepo.core.BlobObjectKeys},
 * kept so the adapter's call sites stay unchanged.
 */
final class BlobObjectKeys {
  private BlobObjectKeys() {
  }

  static String immutableObjectKey(String prefix, String repository, String sha256) {
    return com.github.klboke.kkrepo.core.BlobObjectKeys.immutableObjectKey(prefix, repository, sha256);
  }
}
