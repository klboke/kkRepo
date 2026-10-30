package com.github.klboke.kkrepo.protocol.openvsx;

/**
 * Parsed repository-relative Open VSX route.
 *
 * <p>Both the native Open VSX registry API ({@code api/...}) and the VS Code gallery
 * compatibility surface ({@code vscode/...}) are represented here so routing and authorization
 * derive exactly the same identity from a request on every replica.
 */
public record OpenVsxPath(
    Kind kind,
    String rawPath,
    String namespace,
    String extension,
    String version,
    String targetPlatform,
    String filePath,
    String assetType,
    boolean queryV2) {

  public enum Kind {
    ROOT,
    REGISTRY_VERSION,
    SEARCH,
    QUERY,
    NAMESPACE,
    EXTENSION,
    EXTENSION_VERSIONS,
    VERSION_REFERENCES,
    EXTENSION_VERSION,
    FILE,
    PLATFORM_EXTENSION,
    PLATFORM_VERSIONS,
    PLATFORM_VERSION_REFERENCES,
    PLATFORM_VERSION,
    PLATFORM_FILE,
    PUBLISH,
    REVIEWS,
    VSCODE_QUERY,
    VSCODE_LATEST,
    VSCODE_VSPACKAGE,
    VSCODE_ASSET,
    VSCODE_UNPKG,
    VSCODE_ITEM,
    UNKNOWN
  }

  public boolean publish() {
    return kind == Kind.PUBLISH;
  }

  public boolean metadata() {
    return switch (kind) {
      case REGISTRY_VERSION, NAMESPACE, EXTENSION, EXTENSION_VERSIONS, VERSION_REFERENCES,
          EXTENSION_VERSION, PLATFORM_EXTENSION, PLATFORM_VERSIONS, PLATFORM_VERSION_REFERENCES,
          PLATFORM_VERSION, REVIEWS, SEARCH, QUERY, VSCODE_QUERY, VSCODE_LATEST -> true;
      default -> false;
    };
  }

  public boolean file() {
    return kind == Kind.FILE || kind == Kind.PLATFORM_FILE
        || kind == Kind.VSCODE_VSPACKAGE || kind == Kind.VSCODE_ASSET
        || kind == Kind.VSCODE_UNPKG;
  }
}
