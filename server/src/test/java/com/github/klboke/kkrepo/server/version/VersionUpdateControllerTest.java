package com.github.klboke.kkrepo.server.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.version.LatestReleaseSource.LatestRelease;
import com.github.klboke.kkrepo.server.version.VersionUpdateController.VersionUpdateResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class VersionUpdateControllerTest {
  @Test
  void reportsNewestReleaseWhenSeveralVersionsWereSkipped() {
    String releaseUrl = "https://github.com/klboke/kkRepo/releases/tag/v0.10.0";
    VersionUpdateController controller = controller("v0.10.0", releaseUrl);

    ResponseEntity<VersionUpdateResponse> entity = controller.check("0.8.0");
    VersionUpdateResponse response = Objects.requireNonNull(entity.getBody());

    assertEquals("ok", response.status());
    assertEquals("0.8.0", response.currentVersion());
    assertEquals("0.10.0", response.latestVersion());
    assertTrue(response.updateAvailable());
    assertEquals(releaseUrl, response.releaseUrl());
    assertEquals("no-store", entity.getHeaders().getCacheControl());
  }

  @Test
  void doesNotReportAnUpdateForTheCurrentRelease() {
    VersionUpdateController controller = controller(
        "v0.8.0",
        "https://github.com/klboke/kkRepo/releases/tag/v0.8.0");

    VersionUpdateResponse response = Objects.requireNonNull(controller.check("v0.8.0").getBody());

    assertEquals("ok", response.status());
    assertFalse(response.updateAvailable());
    assertEquals("0.8.0", response.currentVersion());
    assertEquals("0.8.0", response.latestVersion());
  }

  @Test
  void rejectsInvalidCurrentVersionBeforeCallingGitHub() {
    VersionUpdateController controller = new VersionUpdateController(() -> {
      throw new AssertionError("GitHub should not be called");
    });

    ResponseStatusException exception = assertThrows(
        ResponseStatusException.class,
        () -> controller.check("not a version"));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
  }

  @Test
  void reportsGitHubFailuresAsUnavailable() {
    VersionUpdateController controller = new VersionUpdateController(() -> {
      throw new IOException("GitHub unavailable");
    });

    ResponseEntity<VersionUpdateResponse> entity = controller.check("0.8.0");
    VersionUpdateResponse response = Objects.requireNonNull(entity.getBody());

    assertEquals(HttpStatus.OK, entity.getStatusCode());
    assertEquals("unavailable", response.status());
    assertNull(response.currentVersion());
    assertNull(response.latestVersion());
    assertNull(response.updateAvailable());
    assertNull(response.releaseUrl());
    assertEquals("no-store", entity.getHeaders().getCacheControl());
  }

  @Test
  void reportsMalformedGitHubReleaseVersionsAsUnavailable() {
    VersionUpdateController controller = controller(
        "not-a-version",
        "https://github.com/klboke/kkRepo/releases/tag/not-a-version");

    ResponseEntity<VersionUpdateResponse> entity = controller.check("0.8.0");
    VersionUpdateResponse response = Objects.requireNonNull(entity.getBody());

    assertEquals(HttpStatus.OK, entity.getStatusCode());
    assertEquals("unavailable", response.status());
  }

  private VersionUpdateController controller(String version, String releaseUrl) {
    return new VersionUpdateController(
        () -> new LatestRelease(version, URI.create(releaseUrl)));
  }
}
