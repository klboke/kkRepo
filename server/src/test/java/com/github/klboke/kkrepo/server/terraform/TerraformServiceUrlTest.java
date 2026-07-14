package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import org.junit.jupiter.api.Test;

class TerraformServiceUrlTest {
  private static final String BASE = "https://repo.example/repository/terraform-private";

  @Test
  void keepsUrlTokenOnModuleAndProviderFollowUpUrls() {
    assertEquals(
        BASE + "/v1/modules/dXNlcjpwYXNz/acme/network/aws/1.2.3/module.zip",
        TerraformService.publicUrl(
            BASE, "dXNlcjpwYXNz", "v1/modules/acme/network/aws/1.2.3/module.zip"));
    assertEquals(
        BASE + "/v1/providers/dXNlcjpwYXNz/acme/cloud/1.2.3/package/linux/provider.zip",
        TerraformService.publicUrl(
            BASE, "dXNlcjpwYXNz", "v1/providers/acme/cloud/1.2.3/package/linux/provider.zip"));
  }

  @Test
  void leavesCanonicalUrlsAndInternalPathsUnchanged() {
    assertEquals(
        BASE + "/v1/modules/acme/network/aws/versions",
        TerraformService.publicUrl(BASE, null, "v1/modules/acme/network/aws/versions"));
    assertEquals(
        BASE + "/.terraform/routes/value.json",
        TerraformService.publicUrl(BASE, "dXNlcjpwYXNz", ".terraform/routes/value.json"));
  }

  @Test
  void parsesContentDispositionFilenameWithQuotedSeparatorsAndEscapes() {
    assertEquals(
        "terraform-provider-cloud_1.2.3_linux_amd64.zip",
        TerraformService.contentDispositionFilename(
            "attachment; name=archive; filename=terraform-provider-cloud_1.2.3_linux_amd64.zip"));
    assertEquals(
        "provider;linux.zip",
        TerraformService.contentDispositionFilename(
            "attachment; filename=\"provider;linux.zip\"; ignored=\"a;b\""));
    assertEquals(
        "provider-linux.zip",
        TerraformService.contentDispositionFilename(
            "attachment; filename=\"provider\\-linux.zip\""));
  }

  @Test
  void rejectsMissingDuplicateAndMalformedContentDispositionFilename() {
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> TerraformService.contentDispositionFilename("attachment; name=archive"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> TerraformService.contentDispositionFilename(
            "attachment; filename=one.zip; FILENAME=two.zip"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> TerraformService.contentDispositionFilename(
            "attachment; filename=\"unterminated.zip"));
  }

  @Test
  void parsesLongUntrustedWhitespaceInLinearTime() {
    String header = "attachment; filename=" + " ".repeat(100_000) + "provider.zip";
    assertEquals("provider.zip", TerraformService.contentDispositionFilename(header));
  }
}
