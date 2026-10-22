package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RemoteUrlBuilderTest {

  @Test
  void colonsInTheFirstSegmentRemainRepositoryRelative() {
    for (String path : new String[] {"FreeBSD:14:amd64", "FreeBSD%3A14:amd64",
        "FreeBSD%3A14%3Aamd64"}) {
      assertEquals("https://pkg.example/base/FreeBSD%3A14%3Aamd64/quarterly/meta.txz",
          RemoteUrlBuilder.repositoryPathString("https://pkg.example/base/",
              path + "/quarterly/meta.txz"));
    }
    assertEquals("https://pkg.example/base/14%3Aamd64/meta.txz",
        RemoteUrlBuilder.repositoryPathString("https://pkg.example/base/", "14:amd64/meta.txz"));
    assertEquals("https://pkg.example/base/%3Aamd64/meta.txz",
        RemoteUrlBuilder.repositoryPathString("https://pkg.example/base/", ":amd64/meta.txz"));
  }

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
