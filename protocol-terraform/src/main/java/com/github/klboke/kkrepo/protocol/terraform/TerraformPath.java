package com.github.klboke.kkrepo.protocol.terraform;

/** A validated Terraform Registry Protocol route relative to a repository root. */
public record TerraformPath(
    Kind kind,
    String namespace,
    String name,
    String system,
    String version,
    String os,
    String arch,
    String filename,
    String credentialSegment,
    String rawPath) {

  public enum Kind {
    MODULE_VERSIONS,
    MODULE_DOWNLOAD,
    MODULE_ARCHIVE,
    PROVIDER_VERSIONS,
    PROVIDER_DOWNLOAD,
    PROVIDER_ARCHIVE,
    PROVIDER_SHA256SUMS,
    PROVIDER_SHA256SUMS_SIGNATURE,
    UNKNOWN
  }

  public boolean module() {
    return kind == Kind.MODULE_VERSIONS || kind == Kind.MODULE_DOWNLOAD || kind == Kind.MODULE_ARCHIVE;
  }

  public boolean provider() {
    return !module() && kind != Kind.UNKNOWN;
  }

  public String coordinate() {
    return namespace + "/" + name;
  }
}
