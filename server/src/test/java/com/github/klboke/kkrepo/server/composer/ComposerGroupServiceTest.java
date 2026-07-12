package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposerGroupServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void rewritesMemberDistToSameNexusCompatiblePathOnGroup() {
    ComposerHostedService hosted = mock(ComposerHostedService.class);
    ComposerProxyService proxy = mock(ComposerProxyService.class);
    ComposerGroupService group = new ComposerGroupService(new ObjectMapper(), hosted, proxy);
    RepositoryRuntime member = ComposerHostedServiceTest.runtime("private", RepositoryType.HOSTED, List.of());
    RepositoryRuntime runtime = ComposerHostedServiceTest.runtime("all", RepositoryType.GROUP, List.of(member));
    String path = "company/example/1.2.3/company-example-1.2.3.zip";
    var document = new ComposerHostedService.PackageDocument(
        Map.of("packages", Map.of("company/example", List.of(Map.of(
            "version", "1.2.3",
            "dist", Map.of("type", "zip", "url", memberBase(member) + "/" + path))))),
        Instant.EPOCH,
        member.name());
    when(hosted.packageDocument(member, "company/example", false, memberBase(member)))
        .thenReturn(Optional.of(document));

    var result = group.packageDocument(
        runtime, "company/example", false, "https://repo.test/repository/all").orElseThrow();

    Map<String, Object> packages = (Map<String, Object>) result.body().get("packages");
    List<Map<String, Object>> versions = (List<Map<String, Object>>) (List<?>) packages.get("company/example");
    Map<String, Object> dist = (Map<String, Object>) versions.getFirst().get("dist");
    assertEquals("https://repo.test/repository/all/" + path, dist.get("url"));
  }

  @Test
  void firstMemberOwningPackageBlocksLowerMemberVersions() {
    ComposerHostedService hosted = mock(ComposerHostedService.class);
    ComposerProxyService proxy = mock(ComposerProxyService.class);
    ComposerGroupService group = new ComposerGroupService(new ObjectMapper(), hosted, proxy);
    RepositoryRuntime first = ComposerHostedServiceTest.runtime("private", RepositoryType.HOSTED, List.of());
    RepositoryRuntime second = ComposerHostedServiceTest.runtime("public", RepositoryType.HOSTED, List.of());
    RepositoryRuntime runtime = ComposerHostedServiceTest.runtime(
        "all", RepositoryType.GROUP, List.of(first, second));
    var devDocument = new ComposerHostedService.PackageDocument(
        Map.of("packages", Map.of("company/example", List.of(Map.of("version", "dev-main")))),
        Instant.EPOCH,
        first.name());
    var stableDocument = new ComposerHostedService.PackageDocument(
        Map.of("packages", Map.of("company/example", List.of(Map.of("version", "9.9.9")))),
        Instant.EPOCH,
        second.name());
    when(hosted.packageDocument(first, "company/example", false, memberBase(first)))
        .thenReturn(Optional.empty());
    when(hosted.packageDocument(first, "company/example", true, memberBase(first)))
        .thenReturn(Optional.of(devDocument));
    when(hosted.packageDocument(second, "company/example", false, memberBase(second)))
        .thenReturn(Optional.of(stableDocument));

    Optional<ComposerHostedService.PackageDocument> result = group.packageDocument(
        runtime, "company/example", false, "https://repo.test/repository/all");

    assertTrue(result.isEmpty(), "first member owns the package, so lower stable versions must not leak in");
    verify(hosted, never()).packageDocument(second, "company/example", false, memberBase(second));
  }

  private static String memberBase(RepositoryRuntime runtime) {
    return "https://kkrepo.invalid/repository/" + runtime.name();
  }
}
