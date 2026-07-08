package com.github.klboke.kkrepo.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry of the (format, type) recipes kkrepo exposes today. Keep this list explicit:
 * adding a recipe means the corresponding protocol module has at least skeleton coverage.
 *
 * <p>Recipe names follow Nexus conventions on the wire (e.g. {@code maven2-hosted}).
 */
public final class RepositoryRecipes {
  private static final List<RepositoryRecipe> RECIPES = List.of(
      new RepositoryRecipe("maven2-hosted", RepositoryFormat.MAVEN2, RepositoryType.HOSTED),
      new RepositoryRecipe("maven2-proxy", RepositoryFormat.MAVEN2, RepositoryType.PROXY),
      new RepositoryRecipe("maven2-group", RepositoryFormat.MAVEN2, RepositoryType.GROUP),
      new RepositoryRecipe("npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED),
      new RepositoryRecipe("npm-proxy", RepositoryFormat.NPM, RepositoryType.PROXY),
      new RepositoryRecipe("npm-group", RepositoryFormat.NPM, RepositoryType.GROUP),
      new RepositoryRecipe("pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED),
      new RepositoryRecipe("pypi-proxy", RepositoryFormat.PYPI, RepositoryType.PROXY),
      new RepositoryRecipe("pypi-group", RepositoryFormat.PYPI, RepositoryType.GROUP),
      new RepositoryRecipe("cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED),
      new RepositoryRecipe("cargo-proxy", RepositoryFormat.CARGO, RepositoryType.PROXY),
      new RepositoryRecipe("cargo-group", RepositoryFormat.CARGO, RepositoryType.GROUP),
      new RepositoryRecipe("pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED),
      new RepositoryRecipe("pub-proxy", RepositoryFormat.PUB, RepositoryType.PROXY),
      new RepositoryRecipe("pub-group", RepositoryFormat.PUB, RepositoryType.GROUP),
      new RepositoryRecipe("go-proxy", RepositoryFormat.GO, RepositoryType.PROXY),
      new RepositoryRecipe("go-group", RepositoryFormat.GO, RepositoryType.GROUP),
      new RepositoryRecipe("helm-hosted", RepositoryFormat.HELM, RepositoryType.HOSTED),
      new RepositoryRecipe("helm-proxy", RepositoryFormat.HELM, RepositoryType.PROXY),
      new RepositoryRecipe("docker-hosted", RepositoryFormat.DOCKER, RepositoryType.HOSTED),
      new RepositoryRecipe("docker-proxy", RepositoryFormat.DOCKER, RepositoryType.PROXY),
      new RepositoryRecipe("docker-group", RepositoryFormat.DOCKER, RepositoryType.GROUP),
      new RepositoryRecipe("nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED),
      new RepositoryRecipe("nuget-proxy", RepositoryFormat.NUGET, RepositoryType.PROXY),
      new RepositoryRecipe("nuget-group", RepositoryFormat.NUGET, RepositoryType.GROUP),
      new RepositoryRecipe("rubygems-hosted", RepositoryFormat.RUBYGEMS, RepositoryType.HOSTED),
      new RepositoryRecipe("rubygems-proxy", RepositoryFormat.RUBYGEMS, RepositoryType.PROXY),
      new RepositoryRecipe("rubygems-group", RepositoryFormat.RUBYGEMS, RepositoryType.GROUP),
      new RepositoryRecipe("yum-hosted", RepositoryFormat.YUM, RepositoryType.HOSTED),
      new RepositoryRecipe("yum-proxy", RepositoryFormat.YUM, RepositoryType.PROXY),
      new RepositoryRecipe("yum-group", RepositoryFormat.YUM, RepositoryType.GROUP),
      new RepositoryRecipe("raw-hosted", RepositoryFormat.RAW, RepositoryType.HOSTED),
      new RepositoryRecipe("raw-proxy", RepositoryFormat.RAW, RepositoryType.PROXY),
      new RepositoryRecipe("raw-group", RepositoryFormat.RAW, RepositoryType.GROUP));

  private static final Map<String, RepositoryRecipe> BY_NAME = RECIPES.stream()
      .collect(Collectors.toUnmodifiableMap(RepositoryRecipe::name, r -> r));

  private RepositoryRecipes() {
  }

  public static List<RepositoryRecipe> list() {
    return RECIPES;
  }

  public static Optional<RepositoryRecipe> byName(String name) {
    if (name == null) return Optional.empty();
    return Optional.ofNullable(BY_NAME.get(name));
  }
}
