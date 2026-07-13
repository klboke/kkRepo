package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;

public final class HashColumns {
  private HashColumns() {
  }

  public static byte[] componentCoordinateHash(String namespace, String name, String version) {
    return PersistenceHashes.componentCoordinateHash(namespace, name, version);
  }

  public static byte[] pathHash(String path) {
    return PersistenceHashes.pathHash(path);
  }

  public static byte[] blobRefHash(String blobRef) {
    return PersistenceHashes.blobRefHash(blobRef);
  }

  public static byte[] objectKeyHash(String objectKey) {
    return PersistenceHashes.objectKeyHash(objectKey);
  }

  public static byte[] sha256(String... parts) {
    return PersistenceHashes.sha256(parts);
  }
}
