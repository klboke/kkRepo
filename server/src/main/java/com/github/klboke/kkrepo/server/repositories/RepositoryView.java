package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CargoSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.RawSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.AptSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.AlpineSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.PypiSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.YumSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ConanSettings;

public record RepositoryView(
    Long id,
    String name,
    String recipe,
    RepositoryFormat format,
    RepositoryType type,
    boolean online,
    String blobStoreName,
    boolean strictContentTypeValidation,
    String url,
    HostedSettings hosted,
    ProxySettings proxy,
    RawSettings raw,
    DockerSettings docker,
    CargoSettings cargo,
    GroupSettings group,
    AptSettings apt,
    AlpineSettings alpine,
    PypiSettings pypi,
    YumSettings yum,
    ConanSettings conan) {
  /** Compatibility constructor for callers without Conan manifest overrides. */
  public RepositoryView(
      Long id,
      String name,
      String recipe,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String blobStoreName,
      boolean strictContentTypeValidation,
      String url,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group,
      AptSettings apt,
      AlpineSettings alpine,
      PypiSettings pypi,
      YumSettings yum) {
    this(id, name, recipe, format, type, online, blobStoreName, strictContentTypeValidation, url,
        hosted, proxy, raw, docker, cargo, group, apt, alpine, pypi, yum, null);
  }

  /** Compatibility constructor for callers that predate Yum repodata-depth settings. */
  public RepositoryView(
      Long id,
      String name,
      String recipe,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String blobStoreName,
      boolean strictContentTypeValidation,
      String url,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group,
      AptSettings apt,
      AlpineSettings alpine,
      PypiSettings pypi) {
    this(id, name, recipe, format, type, online, blobStoreName, strictContentTypeValidation, url, hosted,
        proxy, raw, docker, cargo, group, apt, alpine, pypi, null);
  }

  public RepositoryView(
      Long id,
      String name,
      String recipe,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String blobStoreName,
      boolean strictContentTypeValidation,
      String url,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group) {
    this(id, name, recipe, format, type, online, blobStoreName,
        strictContentTypeValidation, url, hosted, proxy, raw, docker, cargo, group,
        null, null, null);
  }

  public RepositoryView(
      Long id,
      String name,
      String recipe,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String blobStoreName,
      boolean strictContentTypeValidation,
      String url,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group,
      AptSettings apt) {
    this(id, name, recipe, format, type, online, blobStoreName,
        strictContentTypeValidation, url, hosted, proxy, raw, docker, cargo, group,
        apt, null, null);
  }

  /** Compatibility constructor for callers that predate PyPI proxy index-path settings. */
  public RepositoryView(
      Long id,
      String name,
      String recipe,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String blobStoreName,
      boolean strictContentTypeValidation,
      String url,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group,
      AptSettings apt,
      AlpineSettings alpine) {
    this(id, name, recipe, format, type, online, blobStoreName,
        strictContentTypeValidation, url, hosted, proxy, raw, docker, cargo, group,
        apt, alpine, null);
  }
}
