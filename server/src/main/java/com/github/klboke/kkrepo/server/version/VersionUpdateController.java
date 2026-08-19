package com.github.klboke.kkrepo.server.version;

import com.github.klboke.kkrepo.server.version.LatestReleaseSource.LatestRelease;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VersionUpdateController {
  private static final Pattern VERSION =
      Pattern.compile("^[0-9]+(?:\\.[0-9]+)*(?:[-+][0-9A-Za-z.-]+)?$");

  private final LatestReleaseSource latestReleaseSource;

  VersionUpdateController(LatestReleaseSource latestReleaseSource) {
    this.latestReleaseSource = latestReleaseSource;
  }

  @GetMapping("/internal/version-update")
  public ResponseEntity<VersionUpdateResponse> check(
      @RequestParam("currentVersion") String currentVersion) {
    String current = normalizeVersion(currentVersion)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid release version"));
    LatestRelease latest;
    try {
      latest = latestReleaseSource.fetch();
    } catch (IOException ignored) {
      return unavailableResponse();
    }
    Optional<String> normalizedLatest = normalizeVersion(latest.version());
    if (normalizedLatest.isEmpty()) {
      return unavailableResponse();
    }
    String latestVersion = normalizedLatest.get();
    boolean updateAvailable =
        new ComparableVersion(latestVersion).compareTo(new ComparableVersion(current)) > 0;
    VersionUpdateResponse response = new VersionUpdateResponse(
        "ok",
        current,
        latestVersion,
        updateAvailable,
        latest.url().toString());
    return noStore(response);
  }

  private ResponseEntity<VersionUpdateResponse> unavailableResponse() {
    return noStore(new VersionUpdateResponse("unavailable", null, null, null, null));
  }

  private ResponseEntity<VersionUpdateResponse> noStore(VersionUpdateResponse response) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(response);
  }

  private Optional<String> normalizeVersion(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > 1
        && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')
        && Character.isDigit(normalized.charAt(1))) {
      normalized = normalized.substring(1);
    }
    if (normalized.length() > 64 || !VERSION.matcher(normalized).matches()) {
      return Optional.empty();
    }
    return Optional.of(normalized);
  }

  public record VersionUpdateResponse(
      String status,
      String currentVersion,
      String latestVersion,
      Boolean updateAvailable,
      String releaseUrl) {}
}
