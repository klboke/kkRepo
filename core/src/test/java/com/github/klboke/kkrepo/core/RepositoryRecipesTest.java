package com.github.klboke.kkrepo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RepositoryRecipesTest {
  @Test
  void exposesNexusRecipeTriplesForNugetRubygemsAndYum() {
    Map<String, RepositoryRecipe> recipes = RepositoryRecipes.list().stream()
        .collect(Collectors.toMap(RepositoryRecipe::name, recipe -> recipe));

    assertRecipe(recipes, "nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED);
    assertRecipe(recipes, "nuget-proxy", RepositoryFormat.NUGET, RepositoryType.PROXY);
    assertRecipe(recipes, "nuget-group", RepositoryFormat.NUGET, RepositoryType.GROUP);
    assertRecipe(recipes, "rubygems-hosted", RepositoryFormat.RUBYGEMS, RepositoryType.HOSTED);
    assertRecipe(recipes, "rubygems-proxy", RepositoryFormat.RUBYGEMS, RepositoryType.PROXY);
    assertRecipe(recipes, "rubygems-group", RepositoryFormat.RUBYGEMS, RepositoryType.GROUP);
    assertRecipe(recipes, "yum-hosted", RepositoryFormat.YUM, RepositoryType.HOSTED);
    assertRecipe(recipes, "yum-proxy", RepositoryFormat.YUM, RepositoryType.PROXY);
    assertRecipe(recipes, "yum-group", RepositoryFormat.YUM, RepositoryType.GROUP);
    assertRecipe(recipes, "docker-hosted", RepositoryFormat.DOCKER, RepositoryType.HOSTED);
    assertRecipe(recipes, "docker-proxy", RepositoryFormat.DOCKER, RepositoryType.PROXY);
    assertRecipe(recipes, "docker-group", RepositoryFormat.DOCKER, RepositoryType.GROUP);
    assertRecipe(recipes, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED);
    assertRecipe(recipes, "pub-proxy", RepositoryFormat.PUB, RepositoryType.PROXY);
    assertRecipe(recipes, "pub-group", RepositoryFormat.PUB, RepositoryType.GROUP);
    assertRecipe(recipes, "composer-hosted", RepositoryFormat.COMPOSER, RepositoryType.HOSTED);
    assertRecipe(recipes, "composer-proxy", RepositoryFormat.COMPOSER, RepositoryType.PROXY);
    assertRecipe(recipes, "composer-group", RepositoryFormat.COMPOSER, RepositoryType.GROUP);
    assertRecipe(recipes, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    assertRecipe(recipes, "swift-proxy", RepositoryFormat.SWIFT, RepositoryType.PROXY);
    assertRecipe(recipes, "swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
  }

  private static void assertRecipe(
      Map<String, RepositoryRecipe> recipes,
      String name,
      RepositoryFormat format,
      RepositoryType type) {
    assertTrue(recipes.containsKey(name), "missing " + name);
    assertEquals(format, recipes.get(name).format());
    assertEquals(type, recipes.get(name).type());
  }
}
