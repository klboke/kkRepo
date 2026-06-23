package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RemoteUrlBuilderTest {

  @Test
  void pathCannotOverrideConfiguredRemoteHost() {
    assertEquals(
        "https://repo.example.com/base/http%3A/evil.example/pkg.jar",
        RemoteUrlBuilder.repositoryPathString(
            "https://repo.example.com/base/",
            "http://evil.example/pkg.jar"));

    assertEquals(
        "https://repo.example.com/base/%2F%2Fevil.example/pkg.jar",
        RemoteUrlBuilder.repositoryPathString(
            "https://repo.example.com/base/",
            "//evil.example/pkg.jar"));
  }

  @Test
  void dotSegmentsRemainLiteralPathSegments() {
    assertEquals(
        "https://repo.example.com/base/%2E%2E/secret/%2E/file.txt",
        RemoteUrlBuilder.repositoryPathString(
            "https://repo.example.com/base/",
            "../secret/./file.txt"));
  }

  @Test
  void queryIsAppendedAfterSafePathResolution() {
    assertEquals(
        "https://repo.example.com/npm/-/v1/search?text=left+pad&size=20",
        RemoteUrlBuilder.repositoryPathWithQueryString(
            "https://repo.example.com/npm",
            "-/v1/search",
            "text=left+pad&size=20"));
  }

  @Test
  void existingPercentEscapesStayEncodedForProtocolCompatibility() {
    assertEquals(
        "https://registry.npmjs.org/@scope%2Fname",
        RemoteUrlBuilder.repositoryPathString(
            "https://registry.npmjs.org",
            "@scope%2Fname"));
  }

  @Test
  void protocolPathCharactersStayReadable() {
    assertEquals(
        "https://registry.example.com/v2/library/alpine/manifests/sha256:abcdef",
        RemoteUrlBuilder.repositoryPathString(
            "https://registry.example.com/v2",
            "library/alpine/manifests/sha256:abcdef"));
  }
}
