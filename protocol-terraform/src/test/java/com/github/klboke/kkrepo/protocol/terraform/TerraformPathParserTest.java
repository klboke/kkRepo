package com.github.klboke.kkrepo.protocol.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerraformPathParserTest {
  private final TerraformPathParser parser = new TerraformPathParser();

  @Test
  void parsesOfficialModuleAndProviderRoutes() {
    assertEquals(TerraformPath.Kind.MODULE_VERSIONS,
        parser.parse("v1/modules/acme/network/aws/versions").kind());
    assertEquals(TerraformPath.Kind.MODULE_DOWNLOAD,
        parser.parse("v1/modules/acme/network/aws/1.2.3/download").kind());
    assertEquals(TerraformPath.Kind.MODULE_ARCHIVE,
        parser.parse("v1/modules/acme/network/aws/1.2.3/network.zip").kind());
    TerraformPath upload = parser.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    assertEquals(TerraformPath.Kind.PROVIDER_DOWNLOAD, upload.kind());
    assertEquals("linux", upload.os());
    assertEquals("amd64", upload.arch());
    assertEquals(TerraformPath.Kind.PROVIDER_VERSIONS,
        parser.parse("v1/providers/acme/cloud/versions").kind());
    assertEquals(TerraformPath.Kind.PROVIDER_VERSIONS,
        parser.parse("v1/providers/acme/cloud/versions.json").kind());
    assertEquals(TerraformPath.Kind.PROVIDER_ARCHIVE,
        parser.parse("v1/providers/acme/cloud/1.2.3/package/linux/terraform-provider-cloud_1.2.3_linux_amd64.zip").kind());
    TerraformPath nexusArchive = parser.parse(
        "v1/providers/acme/cloud/1.2.3/download/linux/amd64/terraform-provider-cloud_1.2.3_linux_amd64.zip");
    assertEquals(TerraformPath.Kind.PROVIDER_ARCHIVE, nexusArchive.kind());
    assertEquals("linux", nexusArchive.os());
    assertEquals("amd64", nexusArchive.arch());
    assertEquals("terraform-provider-cloud_1.2.3_linux_amd64.zip", nexusArchive.filename());
    TerraformPath nexusSums = parser.parse(
        "v1/providers/acme/cloud/1.2.3/download/linux/amd64/SHA256SUMS");
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS, nexusSums.kind());
    assertEquals("linux", nexusSums.os());
    assertEquals("amd64", nexusSums.arch());
    assertEquals("SHA256SUMS", nexusSums.filename());
    TerraformPath nexusSignature = parser.parse(
        "v1/providers/acme/cloud/1.2.3/download/linux/amd64/SHA256SUMS.sig");
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE, nexusSignature.kind());
    assertEquals("SHA256SUMS.sig", nexusSignature.filename());
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS,
        parser.parse("v1/providers/acme/cloud/1.2.3/metadata-r2/terraform-provider-cloud_1.2.3_SHA256SUMS").kind());
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE,
        parser.parse("v1/providers/acme/cloud/1.2.3/metadata-r2/"
            + "terraform-provider-cloud_1.2.3_SHA256SUMS.sig").kind());
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS,
        parser.parse("v1/providers/acme/cloud/1.2.3/metadata-proxy/"
            + "terraform-provider-cloud_1.2.3_SHA256SUMS").kind());
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE,
        parser.parse("v1/providers/acme/cloud/1.2.3/metadata-proxy/"
            + "terraform-provider-cloud_1.2.3_SHA256SUMS.sig").kind());
  }

  @Test
  void stripsOneUrlTokenSegmentBeforeProtocolDispatch() {
    var module = parser.parseRequestPath("v1/modules/dGVzdDp0ZXN0/acme/network/aws/versions");
    assertEquals("dGVzdDp0ZXN0", module.credentialSegment());
    assertEquals("v1/modules/acme/network/aws/versions", module.canonicalPath());
    var provider = parser.parseRequestPath(
        "v1/providers/generic-token/acme/cloud/1.2.3/download/linux/amd64");
    assertEquals("generic-token", provider.credentialSegment());
    assertEquals(TerraformPath.Kind.PROVIDER_DOWNLOAD, provider.path().kind());
    var providerChecksum = parser.parseRequestPath(
        "v1/providers/generic-token/acme/cloud/1.2.3/download/linux/amd64/SHA256SUMS");
    assertEquals("generic-token", providerChecksum.credentialSegment());
    assertEquals(
        "v1/providers/acme/cloud/1.2.3/download/linux/amd64/SHA256SUMS",
        providerChecksum.canonicalPath());
    assertEquals(TerraformPath.Kind.PROVIDER_SHA256SUMS, providerChecksum.path().kind());

    var literalPlus = parser.parseRequestPath(
        "v1/modules/dXNlcjpwYXNz+/acme/network/aws/versions");
    assertEquals("dXNlcjpwYXNz+", literalPlus.credentialSegment());
    var encodedPlus = parser.parseRequestPath(
        "v1/providers/generic%2Btoken/acme/cloud/1.2.3/download/linux/amd64");
    assertEquals("generic+token", encodedPlus.credentialSegment());
    var encodedSlash = parser.parseRequestPath(
        "v1/modules/dTo%2F/acme/network/aws/versions");
    assertEquals("dTo/", encodedSlash.credentialSegment());
    assertEquals("v1/modules/acme/network/aws/versions", encodedSlash.canonicalPath());
  }

  @Test
  void stripsProviderVersionsUrlTokenBeforeBuildingProxySuffix() {
    var provider = parser.parseRequestPath(
        "v1/providers/GenericToken.secret/hashicorp/null/versions");
    assertEquals("GenericToken.secret", provider.credentialSegment());
    assertEquals("v1/providers/hashicorp/null/versions", provider.canonicalPath());
    assertEquals(TerraformPath.Kind.PROVIDER_VERSIONS, provider.path().kind());
  }

  @Test
  void rejectsTraversalEncodingAndNonSemanticVersions() {
    for (String path : List.of(
        "v1/modules/acme/%2Fetc/aws/versions",
        "v1/modules/acme/%252Fetc/aws/versions",
        "v1/modules/acme/../aws/versions",
        "v1/modules/acme/network/aws/latest/download")) {
      assertThrows(IllegalArgumentException.class, () -> parser.parse(path), path);
    }
    assertThrows(IllegalArgumentException.class,
        () -> parser.parseRequestPath("v1/modules/token/acme/%2Fetc/aws/versions"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parseRequestPath("bad%2Fpath"));
    assertThrows(IllegalArgumentException.class,
        () -> TerraformPathParser.requireFilename("provider%2Fevil.zip"));
    assertThrows(IllegalArgumentException.class,
        () -> TerraformPathParser.requireFilename("provider%252Fevil.zip"));
    for (String filename : List.of(
        "provider build.zip", "provider#fragment.zip", "provider?query.zip",
        "provider[linux].zip", "provider\u00e9.zip")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> TerraformPathParser.requireFilename(filename),
          filename);
    }
    TerraformPathParser.requireFilename("provider~linux+signed@acme:1.0.zip");
  }

  @Test
  void sortsSemverInsteadOfLexicographically() {
    assertEquals(List.of("10.0.0", "2.0.0", "1.0.0", "1.0.0-rc.2", "1.0.0-rc.1"),
        TerraformVersions.descending(List.of("1.0.0-rc.1", "2.0.0", "1.0.0", "10.0.0", "1.0.0-rc.2")));
  }
}
