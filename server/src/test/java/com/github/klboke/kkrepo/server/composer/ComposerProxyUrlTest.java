package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComposerProxyUrlTest {

  @Test
  void resolvesUpstreamMetadataUrlTemplateIncludingDevSuffix() {
    assertEquals(
        "https://metadata.example/custom/company/example~dev.json?channel=all",
        ComposerProxyService.resolveMetadataUrl(
            runtime(),
            "https://metadata.example/custom/%package%.json?channel=all",
            "company/example",
            true));
    assertEquals(
        "https://repo.packagist.org/custom/company/example.json",
        ComposerProxyService.resolveMetadataUrl(
            runtime(), "custom/%package%.json", "company/example", false));
  }

  @Test
  void preservesListQueryAsQueryInsteadOfEncodingItIntoPath() {
    assertEquals(
        "https://repo.packagist.org/packages/list.json?filter=company%2F*",
        ComposerProxyService.remoteUrl(
            runtime(), "packages/list.json?filter=company%2F*"));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "composer-proxy",
        RepositoryFormat.COMPOSER,
        RepositoryType.PROXY,
        "composer-proxy",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        "https://repo.packagist.org/",
        1440,
        60,
        List.of());
  }
}
