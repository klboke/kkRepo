package com.github.klboke.kkrepo.protocol.docker;

public final class DockerConstants {
  public static final String API_VERSION_HEADER = "Docker-Distribution-API-Version";
  public static final String API_VERSION = "registry/2.0";
  public static final String CONTENT_DIGEST_HEADER = "Docker-Content-Digest";
  public static final String UPLOAD_UUID_HEADER = "Docker-Upload-UUID";
  public static final String OCI_SUBJECT_HEADER = "OCI-Subject";
  public static final String OCI_FILTERS_APPLIED_HEADER = "OCI-Filters-Applied";

  public static final String MEDIA_TYPE_SCHEMA2_MANIFEST =
      "application/vnd.docker.distribution.manifest.v2+json";
  public static final String MEDIA_TYPE_SCHEMA2_MANIFEST_LIST =
      "application/vnd.docker.distribution.manifest.list.v2+json";
  public static final String MEDIA_TYPE_OCI_MANIFEST =
      "application/vnd.oci.image.manifest.v1+json";
  public static final String MEDIA_TYPE_OCI_INDEX =
      "application/vnd.oci.image.index.v1+json";
  public static final String MEDIA_TYPE_OCI_ARTIFACT =
      "application/vnd.oci.artifact.manifest.v1+json";
  public static final String MEDIA_TYPE_OCI_IMAGE_CONFIG =
      "application/vnd.oci.image.config.v1+json";
  public static final String MEDIA_TYPE_OCI_EMPTY =
      "application/vnd.oci.empty.v1+json";
  public static final String MEDIA_TYPE_JSON = "application/json";

  private DockerConstants() {
  }
}
