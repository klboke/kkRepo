package com.github.klboke.kkrepo.protocol.conan;

/** Stable media types and capability headers consumed by Conan 2 clients. */
public final class ConanMediaTypes {
  public static final String JSON = "application/json";
  public static final String TEXT = "text/plain";
  public static final String BINARY = "application/octet-stream";
  public static final String CAPABILITIES_HEADER = "X-Conan-Server-Capabilities";
  public static final String CAPABILITIES = "revisions";

  private ConanMediaTypes() {
  }
}
