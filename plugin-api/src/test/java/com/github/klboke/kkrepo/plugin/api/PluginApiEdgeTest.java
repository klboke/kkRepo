package com.github.klboke.kkrepo.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginApiEdgeTest {
  private static final FormatId RAW = new FormatId("raw");

  @Test
  void repositoryContextDefaultsAndEmptyContextAreSafe() {
    RepositoryDescriptor descriptor = descriptor();
    RepositoryContext empty = RepositoryContext.empty(descriptor);

    assertEquals(new RepositoryPolicy("", "", ""), empty.policy());
    assertTrue(empty.blobs().isEmpty());
    assertTrue(empty.service(String.class).isEmpty());
  }

  @Test
  void routeRejectsMissingMethodAndComputesSpecificity() {
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryProtocolRoute(RAW, RepositoryKind.HOSTED, null, "**", 0));
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryProtocolRoute(RAW, RepositoryKind.HOSTED, " ", "**", 0));

    assertEquals(0, RepositoryProtocolRoute.anyPath(
        RAW, RepositoryKind.HOSTED, "GET", 0).pathSpecificity());
    int prefix = RepositoryProtocolRoute.exactPath(
        RAW, RepositoryKind.HOSTED, "GET", "api/**", 0).pathSpecificity();
    assertTrue(prefix >= 1_000 && prefix < 1_000_000);
    int exact = RepositoryProtocolRoute.exactPath(
        RAW, RepositoryKind.HOSTED, "GET", "api/charts", 0).pathSpecificity();
    assertTrue(exact >= 1_000_000);
  }

  @Test
  void responseFactoriesAndValidationCoverEveryBranch() {
    assertTrue(RepositoryResponse.ok(new byte[0]).body().length == 0);
    assertEquals(204, RepositoryResponse.noContent().status());
    assertEquals(201, RepositoryResponse.status(201).status());
    assertEquals(0, new RepositoryResponse(200, null, null).body().length);
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryResponse(99, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryResponse(600, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryResponse(200, null, new byte[] {1}, output -> { }));
  }

  @Test
  void requestValidationAndDefaultsCoverEveryBranch() {
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryRequest(null, "repo", "a", "GET", null, null, null, null, Map.of()));

    RepositoryRequest defaults = new RepositoryRequest(
        descriptor(), " ", null, null, null, null, null, null, null);
    assertEquals("repo", defaults.repositoryName());
    assertEquals("", defaults.path());
    assertEquals("GET", defaults.method());
    assertTrue(defaults.headers().isEmpty());
    assertTrue(defaults.queryParameters().isEmpty());
    assertEquals(0, defaults.body().length);
    assertTrue(defaults.attributes().isEmpty());
    assertEquals("", RepositoryRequest.normalizePath(null));
    assertEquals("a/b", RepositoryRequest.normalizePath(" /a/b "));

    RepositoryDescriptor other = new RepositoryDescriptor(
        2L, "other", RAW, RepositoryKind.HOSTED, "raw", true, 1L,
        null, null, null, null, Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryRequest(descriptor(), "repo", "a", "GET", null, null, null, null,
            RepositoryContext.empty(other), Map.of(), null));

    RepositoryRequest buffered = new RepositoryRequest(
        descriptor(), "repo", "a", "GET", null, null, new byte[] {1}, null, Map.of());
    assertThrows(IllegalArgumentException.class, () -> new RepositoryRequest(
        descriptor(), "repo", "a", "GET", null, null, new byte[] {1}, null, null, Map.of(),
        new ByteArrayInputStream(new byte[0])));
    assertEquals(1, buffered.body().length);
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L, "repo", RAW, RepositoryKind.HOSTED, "raw", true, 1L,
        "ALLOW", "RELEASE", "STRICT", null, Map.of());
  }
}
