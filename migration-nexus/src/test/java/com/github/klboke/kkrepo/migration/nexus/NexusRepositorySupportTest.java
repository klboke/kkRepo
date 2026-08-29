package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import org.junit.jupiter.api.Test;

class NexusRepositorySupportTest {
  @Test
  void acceptsNativeHelmGroupDefinitionsForOrderedConfigurationMigration() {
    var recipe = NexusRepositorySupport.recipe("helm", "group").orElseThrow();

    assertEquals("helm-group", recipe.name());
    assertEquals(RepositoryFormat.HELM, recipe.format());
    assertEquals(RepositoryType.GROUP, recipe.type());
    assertTrue(NexusRepositorySupport.supportedRecipe("HELM", "GROUP"));
  }
}
