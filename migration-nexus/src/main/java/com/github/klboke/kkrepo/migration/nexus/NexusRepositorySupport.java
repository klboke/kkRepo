package com.github.klboke.kkrepo.migration.nexus;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipe;
import com.github.klboke.kkrepo.core.RepositoryRecipes;
import com.github.klboke.kkrepo.core.RepositoryType;
import java.util.Locale;
import java.util.Optional;

final class NexusRepositorySupport {
  private NexusRepositorySupport() {
  }

  static boolean supportedRecipe(String format, String type) {
    return recipe(format, type).isPresent();
  }

  static Optional<RepositoryRecipe> recipe(String format, String type) {
    RepositoryFormat repositoryFormat = format(format);
    RepositoryType repositoryType = type(type);
    if (repositoryFormat == null || repositoryType == null) {
      return Optional.empty();
    }
    // Nexus native Composer support is proxy-only. kkrepo hosted/group are product extensions and
    // must never be inferred from an unknown/community-plugin source model.
    if (repositoryFormat == RepositoryFormat.COMPOSER && repositoryType != RepositoryType.PROXY) {
      return Optional.empty();
    }
    return RepositoryRecipes.byName(repositoryFormat.id() + "-" + repositoryType.name().toLowerCase(Locale.ROOT));
  }

  static RepositoryFormat format(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("MAVEN".equals(normalized)) {
      normalized = "MAVEN2";
    }
    try {
      return RepositoryFormat.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static RepositoryType type(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return RepositoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static String unsupportedReason(String format, String type) {
    RepositoryFormat repositoryFormat = format(format);
    RepositoryType repositoryType = type(type);
    if (repositoryFormat == null) {
      return "format is not supported by kkrepo P0/P1 migration";
    }
    if (repositoryType == null) {
      return "repository type is not recognized";
    }
    if (repositoryFormat == RepositoryFormat.GO && repositoryType == RepositoryType.HOSTED) {
      return "go hosted has no target recipe";
    }
    if (repositoryFormat == RepositoryFormat.HELM && repositoryType == RepositoryType.GROUP) {
      return "helm group has no target recipe";
    }
    if (repositoryFormat == RepositoryFormat.COMPOSER && repositoryType != RepositoryType.PROXY) {
      return "Nexus native Composer migration supports proxy repositories only";
    }
    return "recipe is not supported by kkrepo";
  }
}
