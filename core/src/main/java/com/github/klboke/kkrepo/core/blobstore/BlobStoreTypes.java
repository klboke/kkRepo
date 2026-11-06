package com.github.klboke.kkrepo.core.blobstore;

/** Canonical {@code blob_store.type} discriminators understood by the bundled providers. */
public final class BlobStoreTypes {
  public static final String FILE = "file";
  public static final String S3 = "s3";
  public static final String AZURE = "azure";

  private BlobStoreTypes() {
  }
}
