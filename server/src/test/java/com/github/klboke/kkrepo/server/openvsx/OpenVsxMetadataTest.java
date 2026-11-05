package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenVsxMetadataTest {
  private final OpenVsxMetadata metadata = new OpenVsxMetadata(new ObjectMapper());

  @Test
  void buildsExtensionMetadataWithRepositoryUrls() {
    OpenVsxExtensionView view = new OpenVsxExtensionView(
        "acme", "demo", "1.2.3", null, false, "acme.demo-1.2.3.vsix",
        "Demo", "Demo extension",
        Map.of("displayName", "Demo", "description", "Demo extension", "icon", "icon.png"),
        Instant.parse("2026-01-01T00:00:00Z"), "acme/demo/1.2.3/acme.demo-1.2.3.vsix", 10,
        "sha1", "sha256");

    Map<String, Object> json = metadata.extensionJson(
        "https://repo.test", "openvsx-hosted", List.of(view), view, null);

    assertEquals("acme", json.get("namespace"));
    assertEquals("demo", json.get("name"));
    assertEquals("1.2.3", json.get("version"));
    assertEquals("https://repo.test/openvsx-hosted/api/acme/demo/1.2.3/file/acme.demo-1.2.3.vsix",
        ((Map<?, ?>) json.get("files")).get("download"));
    assertTrue(((Map<?, ?>) json.get("allVersions")).containsKey("1.2.3"));
    assertEquals("https://repo.test/openvsx-hosted/api/acme/demo/versions",
        json.get("allVersionsUrl"));
  }

  @Test
  void buildsItemProjection() {
    OpenVsxExtensionView view = view("1.2.3", null);
    Map<String, Object> json = metadata.itemJson("https://repo.test", "openvsx-hosted", view);

    assertEquals("acme.demo", json.get("itemName"));
    assertEquals("acme", json.get("publisher"));
    assertEquals("1.2.3", json.get("version"));
  }

  @Test
  void selectsLatestStableAndPlatformFallback() {
    OpenVsxExtensionView universal = view("1.0.0", null);
    OpenVsxExtensionView prerelease = view("2.0.0-rc.1", null);
    OpenVsxExtensionView platform = view("1.0.0", "linux-x64");

    assertEquals("1.0.0", metadata.latest(List.of(universal, prerelease), null).version());
    assertEquals("linux-x64",
        metadata.latest(List.of(universal, platform), "linux-x64").targetPlatform());
    assertEquals("1.0.0", metadata.latest(List.of(universal), "win32-x64").version());
    assertNull(metadata.latest(List.of(platform), "win32-x64"));
  }

  @Test
  void findVersionPrefersTheExactPlatformAndDoesNotFallBackToAnotherPlatform() {
    OpenVsxExtensionView universal = view("1.0.0", null);
    OpenVsxExtensionView linux = view("1.0.0", "linux-x64");
    OpenVsxExtensionView darwin = view("1.0.0", "darwin-x64");

    assertEquals("linux-x64", metadata.findVersion(
        List.of(universal, linux, darwin), "1.0.0", "linux-x64").targetPlatform());
    assertEquals("linux-x64", metadata.findVersion(
        List.of(linux, darwin), "1.0.0", "linux-x64").targetPlatform());
    assertNull(metadata.findVersion(List.of(linux, darwin), "1.0.0", "win32-x64"));
    assertEquals("1.0.0", metadata.findVersion(
        List.of(universal, linux), "1.0.0", "darwin-x64").version());
  }

  private static OpenVsxExtensionView view(String version, String platform) {
    return new OpenVsxExtensionView(
        "acme", "demo", version, platform, version.contains("-"),
        "acme.demo-" + version + ".vsix", "Demo", "desc", Map.of(), Instant.EPOCH,
        "acme/demo/" + version + "/acme.demo-" + version + ".vsix", 1, "s1", "s256");
  }
}
