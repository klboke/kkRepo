package com.github.klboke.kkrepo.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum RepositoryFormat {
  MAVEN2,
  NPM,
  PYPI,
  CARGO,
  PUB,
  GO,
  HELM,
  DOCKER,
  NUGET,
  RUBYGEMS,
  YUM,
  RAW;

  @JsonCreator
  public static RepositoryFormat fromJson(String value) {
    if (value == null) {
      return null;
    }
    return RepositoryFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return id();
  }
}
