package com.github.klboke.kkrepo.protocol.winget;

/** Protocol constants for the WinGet REST source API. */
public final class WingetProtocol {
  /** WinGet clients negotiate JSON; the schema does not define a vendor media type. */
  public static final String MEDIA_TYPE = "application/json";

  /**
   * Contract versions implemented by the response schemas we emit.
   *
   * <p>1.10.0 is the newest version in the WinGet 1.10 client contract and the newest schema
   * published by the reference implementation. Later schemas are additive, and unknown request
   * and manifest fields are preserved verbatim by {@link WingetManifestParser}.
   */
  public static final String SUPPORTED_VERSIONS =
      "[\"1.0.0\",\"1.1.0\",\"1.4.0\",\"1.5.0\",\"1.6.0\",\"1.7.0\",\"1.9.0\",\"1.10.0\"]";

  public static final String API_PREFIX = "api/";
  public static final String INFORMATION_PATH = "api/information";
  public static final String MANIFEST_SEARCH_PATH = "api/manifestSearch";
  public static final String PACKAGE_MANIFESTS_PATH = "api/packageManifests";
  public static final String AUTHENTICATION_HEADER = "Windows-Package-Manager";

  private WingetProtocol() {}
}
