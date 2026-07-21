package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseTerraformUploadContractTest {

  @Test
  void terraformUploadRendersAndSubmitsRequiredCoordinates() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(javascript.contains("if (repo.format === \"terraform\")"));
    assertTrue(javascript.contains("id=\"upload-terraform-kind\""));
    assertTrue(javascript.contains("id=\"upload-terraform-namespace\""));
    assertTrue(javascript.contains("id=\"upload-terraform-name\""));
    assertTrue(javascript.contains("id=\"upload-terraform-version\""));
    assertTrue(javascript.contains("data-terraform-kind=\"module\""));
    assertTrue(javascript.contains("data-terraform-kind=\"provider\""));
    assertTrue(javascript.contains("id=\"upload-terraform-protocols\""));
    for (String field : new String[] {
        "kind", "namespace", "name", "version", "system", "os", "arch", "protocols", "asset"
    }) {
      assertTrue(javascript.contains("form.append(\"terraform." + field + "\""), field);
    }
    assertTrue(javascript.contains("updateTerraformUploadKind"));
    assertTrue(javascript.contains("return parts[0] === \"v1\";"));
    assertTrue(javascript.contains("crumbText: entry.path"));
    assertTrue(javascript.contains("\"Terraform registry\""));
    assertTrue(javascript.contains("\"Terraform modules\""));
    assertTrue(javascript.contains("\"Terraform providers\""));
    assertTrue(javascript.contains("treeNodeEntries.set(li, entry)"));
    assertTrue(javascript.contains("treeNodeEntries.get(node) || findCachedTreeEntry(state.path)"));
    assertTrue(javascript.contains("const repoUrl = repositoryBaseUrl().replace(/\\/+$/, \"\")"));
    assertTrue(javascript.contains("crumbIcon: repositoryFormatIcon()"));
    assertTrue(javascript.contains("await activateTreeBranch(entry, toggleExpand)"));
    assertTrue(index.contains("/browse/assets/browse.js?v=20260721-ansiblegalaxy-1"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
