package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CargoSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.UpdateCommand;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryServiceTest {

  @Test
  void updateRejectsBlobStoreChange() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.update("maven-releases",
            new UpdateCommand(true, "v2", null, null, null, null, null, null, null)));

    assertEquals("blobStoreName cannot be changed after repository creation", thrown.getMessage());
    assertNull(repositories.updated);
  }

  @Test
  void updateAllowsSameBlobStoreNameForExistingClients() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("maven-releases",
        new UpdateCommand(false, "default", null, null, null, null, null, null, null));

    assertEquals("default", updated.blobStoreName());
    assertEquals(1L, repositories.updated.blobStoreId());
    assertEquals(false, repositories.updated.online());
  }

  @Test
  void updateKeepsBlobStoreWhenPayloadOmitsIt() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("maven-releases",
        new UpdateCommand(false, null, null, null, null, null, null, null, null));

    assertEquals("default", updated.blobStoreName());
    assertEquals(1L, repositories.updated.blobStoreId());
  }

  @Test
  void updateBumpsRepositoryCacheTokens() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    RepositoryService service = new RepositoryService(
        repositories,
        new StubBlobStoreDao(),
        null,
        new RepositoryRuntimeRegistry(repositories, 0),
        new NexusLikeCacheController(watermark, 60),
        "/repository");

    service.update("maven-releases",
        new UpdateCommand(false, null, null, null, null, null, null, null, null));

    assertEquals(1, watermark.current("repo:10:CONTENT"));
    assertEquals(1, watermark.current("repo:10:METADATA"));
  }

  @Test
  void createRejectsDuplicateDockerConnectorPort() {
    StubRepositoryDao repositories = new StubRepositoryDao(dockerRepository(20L, "docker-hosted-a", 5000));
    RepositoryService service = service(repositories);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.create(new CreateCommand(
            "docker-hosted-b",
            "docker-hosted",
            true,
            "default",
            true,
            new HostedSettings("ALLOW", null, null),
            null,
            null,
            new DockerSettings(true, 5000, null),
            null,
            null)));

    assertEquals(
        "docker.connector.port 5000 is already used by repository docker-hosted-a",
        thrown.getMessage());
  }

  @Test
  void createRejectsEnabledDockerConnectorWithoutPort() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.create(new CreateCommand(
            "docker-hosted",
            "docker-hosted",
            true,
            "default",
            true,
            new HostedSettings("ALLOW", null, null),
            null,
            null,
            new DockerSettings(true, null, null),
            null,
            null)));

    assertEquals("docker.connector.port is required when connector is enabled", thrown.getMessage());
  }

  @Test
  void createAllowsDockerRepositoryWithoutConnectorPortWhenConnectorIsDisabled() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "docker-hosted",
        "docker-hosted",
        true,
        "default",
        true,
        new HostedSettings("ALLOW", null, null),
            null,
            null,
            new DockerSettings(false, null, null),
            null,
            null));

    assertEquals("docker-hosted", created.name());
    assertEquals(false, created.docker().connectorEnabled());
    assertNull(created.docker().connectorPort());
    Map<?, ?> docker = (Map<?, ?>) repositories.repository.attributes().get("docker");
    assertEquals(false, docker.get("connectorEnabled"));
    assertNull(docker.get("connectorPort"));
  }

  @Test
  void createRejectsDockerConnectorPortThatConflictsWithServerPort() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories, 18090, 18091);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.create(new CreateCommand(
            "docker-hosted",
            "docker-hosted",
            true,
            "default",
            true,
            new HostedSettings("ALLOW", null, null),
            null,
            null,
            new DockerSettings(true, 18090, null),
            null,
            null)));

    assertEquals("docker.connector.port 18090 conflicts with server.port", thrown.getMessage());
  }

  @Test
  void createRejectsDockerConnectorPortThatConflictsWithManagementPort() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories, 18090, 18091);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.create(new CreateCommand(
            "docker-hosted",
            "docker-hosted",
            true,
            "default",
            true,
            new HostedSettings("ALLOW", null, null),
            null,
            null,
            new DockerSettings(true, 18091, null),
            null,
            null)));

    assertEquals("docker.connector.port 18091 conflicts with management.server.port", thrown.getMessage());
  }

  @Test
  void updateDisablingDockerConnectorClearsPort() {
    StubRepositoryDao repositories = new StubRepositoryDao(dockerRepository(20L, "docker-hosted", 5000));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("docker-hosted",
        new UpdateCommand(true, null, null, null, null, null,
            new DockerSettings(false, null, null), null, null));

    assertEquals(false, updated.docker().connectorEnabled());
    assertNull(updated.docker().connectorPort());
    Map<?, ?> docker = (Map<?, ?>) repositories.updated.attributes().get("docker");
    assertEquals(false, docker.get("connectorEnabled"));
    assertNull(docker.get("connectorPort"));
  }

  @Test
  void createCargoHostedRepositoryDoesNotExposeRequireAuthenticationHint() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "cargo-hosted",
        "cargo-hosted",
        true,
        "default",
        true,
        new HostedSettings("ALLOW", null, null),
        null,
        null,
        null,
        new CargoSettings(true),
        null));

    assertEquals("cargo-hosted", created.name());
    assertNull(created.cargo());
    assertNull(repositories.repository.attributes().get("cargo"));
  }

  @Test
  void createCargoProxyRepositoryStoresRequireAuthenticationHint() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "cargo-proxy",
        "cargo-proxy",
        true,
        "default",
        true,
        null,
        new ProxySettings("https://index.crates.io/", 1440, 1440, true),
        null,
        null,
        new CargoSettings(true),
        null));

    assertEquals("cargo-proxy", created.name());
    assertEquals(true, created.cargo().requireAuthentication());
    Map<?, ?> cargo = (Map<?, ?>) repositories.repository.attributes().get("cargo");
    assertEquals(true, cargo.get("requireAuthentication"));
  }

  @Test
  void proxyRemotePasswordIsStoredButMaskedInRepositoryView() {
    StubRepositoryDao repositories = new StubRepositoryDao(dockerProxyRepository());
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("docker-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings("http://127.0.0.1:5000", 60, 30, true, "robot", "secret", null),
            null, null, null, null));

    assertEquals("robot", updated.proxy().remoteUsername());
    assertNull(updated.proxy().remotePassword());
    assertEquals(true, updated.proxy().remotePasswordConfigured());
    Map<?, ?> proxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertEquals("secret", proxy.get("remotePassword"));
  }

  @Test
  void proxyRemotePasswordCanBeClearedWithoutSendingPlaintext() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "http://127.0.0.1:5000");
    proxy.put("remoteUsername", "robot");
    proxy.put("remotePassword", "secret");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "docker-proxy");
    attributes.put("proxy", proxy);
    StubRepositoryDao repositories = new StubRepositoryDao(new RepositoryRecord(
        30L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        "http://127.0.0.1:5000",
        null,
        null,
        null,
        true,
        attributes));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("docker-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings(null, null, null, null, null, null, false),
            null, null, null, null));

    assertEquals(false, updated.proxy().remotePasswordConfigured());
    Map<?, ?> storedProxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertNull(storedProxy.get("remotePassword"));
  }

  @Test
  void proxyRemoteBearerTokenIsStoredButMaskedInRepositoryView() {
    StubRepositoryDao repositories = new StubRepositoryDao(dockerProxyRepository());
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("docker-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings("http://127.0.0.1:5000", 60, 30, true,
                null, null, null, "bearer-secret", null),
            null, null, null, null));

    assertNull(updated.proxy().remoteBearerToken());
    assertEquals(true, updated.proxy().remoteBearerTokenConfigured());
    Map<?, ?> proxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertEquals("bearer-secret", proxy.get("remoteBearerToken"));
  }

  @Test
  void proxyRemoteBearerTokenCanBeClearedWithoutSendingPlaintext() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "http://127.0.0.1:5000");
    proxy.put("remoteBearerToken", "bearer-secret");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "docker-proxy");
    attributes.put("proxy", proxy);
    StubRepositoryDao repositories = new StubRepositoryDao(new RepositoryRecord(
        30L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        "http://127.0.0.1:5000",
        null,
        null,
        null,
        true,
        attributes));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("docker-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings(null, null, null, null,
                null, null, null, null, false),
            null, null, null, null));

    assertEquals(false, updated.proxy().remoteBearerTokenConfigured());
    Map<?, ?> storedProxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertNull(storedProxy.get("remoteBearerToken"));
  }

  @Test
  void outboundProxyRoundTripStoresAndMasksPassword() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "maven-proxy",
        "maven2-proxy",
        true,
        "default",
        true,
        null,
        new ProxySettings(
            "https://repo.maven.apache.org/maven2/",
            1440, 1440, true,
            null, null, null,
            null, null,
            "HTTP", "192.168.1.10", 7890,
            "clash-user", "clash-pass",
            null),
        null, null, null, null));

    assertEquals("HTTP", created.proxy().outboundProxyType());
    assertEquals("192.168.1.10", created.proxy().outboundProxyHost());
    assertEquals(7890, created.proxy().outboundProxyPort());
    assertEquals("clash-user", created.proxy().outboundProxyUsername());
    assertNull(created.proxy().outboundProxyPassword());
    assertEquals(true, created.proxy().outboundProxyPasswordConfigured());

    Map<?, ?> proxy = (Map<?, ?>) repositories.repository.attributes().get("proxy");
    assertEquals("HTTP", proxy.get("outboundProxyType"));
    assertEquals("192.168.1.10", proxy.get("outboundProxyHost"));
    assertEquals(7890, proxy.get("outboundProxyPort"));
    assertEquals("clash-user", proxy.get("outboundProxyUsername"));
    assertEquals("clash-pass", proxy.get("outboundProxyPassword"));
  }

  @Test
  void outboundProxyPasswordCanBeClearedWithoutSendingPlaintext() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "https://repo.maven.apache.org/maven2/");
    proxy.put("outboundProxyType", "SOCKS");
    proxy.put("outboundProxyHost", "192.168.1.10");
    proxy.put("outboundProxyPort", 7891);
    proxy.put("outboundProxyUsername", "clash-user");
    proxy.put("outboundProxyPassword", "clash-pass");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "maven2-proxy");
    attributes.put("proxy", proxy);
    StubRepositoryDao repositories = new StubRepositoryDao(new RepositoryRecord(
        1L,
        "maven-proxy",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "https://repo.maven.apache.org/maven2/",
        null,
        null,
        null,
        true,
        attributes));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.update("maven-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings(
                "https://repo.maven.apache.org/maven2/", 1440, 1440, true,
                null, null, null, null, null,
                "SOCKS", "192.168.1.10", 7891, "clash-user", null, false),
            null, null, null, null));

    assertEquals(false, updated.proxy().outboundProxyPasswordConfigured());
    Map<?, ?> storedProxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertNull(storedProxy.get("outboundProxyPassword"));
    assertEquals("clash-user", storedProxy.get("outboundProxyUsername"));
  }

  @Test
  void outboundProxyCanBeClearedBySelectingDirect() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "https://repo.maven.apache.org/maven2/");
    proxy.put("outboundProxyType", "SOCKS");
    proxy.put("outboundProxyHost", "192.168.1.10");
    proxy.put("outboundProxyPort", 7891);
    proxy.put("outboundProxyUsername", "clash-user");
    proxy.put("outboundProxyPassword", "clash-pass");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "maven2-proxy");
    attributes.put("proxy", proxy);
    StubRepositoryDao repositories = new StubRepositoryDao(new RepositoryRecord(
        1L,
        "maven-proxy",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "https://repo.maven.apache.org/maven2/",
        null,
        null,
        null,
        true,
        attributes));
    RepositoryService service = service(repositories);

    // The admin form sends "" for the "Direct (no proxy)" option and null for the other
    // outbound fields; the merge must clear the whole block instead of preserving the old values.
    RepositoryView updated = service.update("maven-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings(
                "https://repo.maven.apache.org/maven2/", 1440, 1440, true,
                null, null, null, null, null,
                "", null, null, null, null, null),
            null, null, null, null));

    assertNull(updated.proxy().outboundProxyType());
    assertNull(updated.proxy().outboundProxyHost());
    assertNull(updated.proxy().outboundProxyPort());
    assertNull(updated.proxy().outboundProxyUsername());
    assertNull(updated.proxy().outboundProxyPassword());
    Map<?, ?> storedProxy = (Map<?, ?>) repositories.updated.attributes().get("proxy");
    assertNull(storedProxy.get("outboundProxyType"));
    assertNull(storedProxy.get("outboundProxyHost"));
    assertNull(storedProxy.get("outboundProxyPort"));
    assertNull(storedProxy.get("outboundProxyUsername"));
    assertNull(storedProxy.get("outboundProxyPassword"));
  }

  @Test
  void outboundProxyUnchangedWhenTypeFieldOmitted() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "https://repo.maven.apache.org/maven2/");
    proxy.put("outboundProxyType", "SOCKS");
    proxy.put("outboundProxyHost", "192.168.1.10");
    proxy.put("outboundProxyPort", 7891);
    proxy.put("outboundProxyUsername", "clash-user");
    proxy.put("outboundProxyPassword", "clash-pass");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "maven2-proxy");
    attributes.put("proxy", proxy);
    StubRepositoryDao repositories = new StubRepositoryDao(new RepositoryRecord(
        1L,
        "maven-proxy",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "https://repo.maven.apache.org/maven2/",
        null,
        null,
        null,
        true,
        attributes));
    RepositoryService service = service(repositories);

    // A null outboundProxyType means "block not touched"; the saved proxy must survive.
    RepositoryView updated = service.update("maven-proxy",
        new UpdateCommand(true, null, null, null,
            new ProxySettings(
                "https://repo.maven.apache.org/maven2/", 1440, 1440, true,
                null, null, null, null, null,
                null, null, null, null, null, null),
            null, null, null, null));

    assertEquals("SOCKS", updated.proxy().outboundProxyType());
    assertEquals("192.168.1.10", updated.proxy().outboundProxyHost());
    assertEquals(7891, updated.proxy().outboundProxyPort());
    assertEquals("clash-user", updated.proxy().outboundProxyUsername());
  }

  @Test
  void outboundProxyWithoutHostIsRejected() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryValidationException ex = assertThrows(RepositoryValidationException.class, () -> service.create(
        new CreateCommand(
            "maven-proxy",
            "maven2-proxy",
            true,
            "default",
            true,
            null,
            new ProxySettings(
                "https://repo.maven.apache.org/maven2/", 1440, 1440, true,
                null, null, null, null, null,
                "HTTP", null, 7890, null, null, null),
            null, null, null, null)));
    assertTrue(ex.getMessage().contains("outboundProxyHost"));
  }

  @Test
  void createPubProxyDefaultsRemoteUrlWhenProxySettingsAreMissing() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "pub-proxy",
        "pub-proxy",
        true,
        "default",
        true,
        null,
        null,
        null,
        null,
        null,
        null));

    assertEquals("https://pub.dev/", created.proxy().remoteUrl());
    assertEquals("https://pub.dev/", repositories.repository.proxyRemoteUrl());
    Map<?, ?> proxy = (Map<?, ?>) repositories.repository.attributes().get("proxy");
    assertEquals("https://pub.dev/", proxy.get("remoteUrl"));
  }

  @Test
  void createPubProxyDefaultsRemoteUrlWhenProxyRemoteIsBlank() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "pub-proxy",
        "pub-proxy",
        true,
        "default",
        true,
        null,
        new ProxySettings("  ", 60, 30, true),
        null,
        null,
        null,
        null));

    assertEquals("https://pub.dev/", created.proxy().remoteUrl());
    assertEquals(60, created.proxy().contentMaxAgeMinutes());
    assertEquals(30, created.proxy().metadataMaxAgeMinutes());
  }

  @Test
  void createPubProxyWithBlankRemoteKeepsOutboundProxyWhenDefaultingRemoteUrl() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "pub-proxy",
        "pub-proxy",
        true,
        "default",
        true,
        null,
        new ProxySettings("  ", 60, 30, true,
            null, null, null, null, null,
            "HTTP", "192.168.1.10", 7890, "clash-user", "clash-pass", null),
        null,
        null,
        null,
        null));

    assertEquals("https://pub.dev/", created.proxy().remoteUrl());
    assertEquals("HTTP", created.proxy().outboundProxyType());
    assertEquals("192.168.1.10", created.proxy().outboundProxyHost());
    assertEquals(7890, created.proxy().outboundProxyPort());
    assertEquals("clash-user", created.proxy().outboundProxyUsername());
    Map<?, ?> proxy = (Map<?, ?>) repositories.repository.attributes().get("proxy");
    assertEquals("https://pub.dev/", proxy.get("remoteUrl"));
    assertEquals("192.168.1.10", proxy.get("outboundProxyHost"));
    assertEquals("clash-pass", proxy.get("outboundProxyPassword"));
  }

  @Test
  void createTerraformProxyWithBlankRemoteKeepsOutboundProxyWhenDefaultingRemoteUrl() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "terraform-proxy",
        "terraform-proxy",
        true,
        "default",
        true,
        null,
        new ProxySettings(null, null, null, null,
            null, null, null, null, null,
            "SOCKS", "192.168.1.20", 7891, null, null, null),
        null,
        null,
        null,
        null));

    assertEquals("https://registry.terraform.io/", created.proxy().remoteUrl());
    assertEquals("SOCKS", created.proxy().outboundProxyType());
    assertEquals("192.168.1.20", created.proxy().outboundProxyHost());
    assertEquals(7891, created.proxy().outboundProxyPort());
  }

  @Test
  void createComposerProxyDefaultsToPackagistWhenProxySettingsAreMissing() {
    StubRepositoryDao repositories = new StubRepositoryDao(repository(1L));
    RepositoryService service = service(repositories);

    RepositoryView created = service.create(new CreateCommand(
        "composer-proxy",
        "composer-proxy",
        true,
        "default",
        true,
        null,
        null,
        null,
        null,
        null,
        null));

    assertEquals("https://repo.packagist.org/", created.proxy().remoteUrl());
    assertEquals("https://repo.packagist.org/", repositories.repository.proxyRemoteUrl());
  }

  @Test
  void replaceMembersAllowsNestedPubGroupMembers() {
    StubRepositoryDao repositories = new StubRepositoryDao(
        pubGroupRepository(10L, "pub-root"),
        pubHostedRepository(11L, "pub-hosted"),
        pubGroupRepository(12L, "pub-nested"));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.replaceMembers("pub-root", List.of("pub-hosted", "pub-nested"));

    assertEquals(List.of("pub-hosted", "pub-nested"), updated.group().memberNames());
    assertEquals(List.of(11L, 12L), repositories.membersByGroupId.get(10L));
  }

  @Test
  void replaceMembersAllowsNestedComposerGroupMembers() {
    StubRepositoryDao repositories = new StubRepositoryDao(
        composerGroupRepository(30L, "composer-root"),
        composerHostedRepository(31L, "composer-hosted"),
        composerGroupRepository(32L, "composer-nested"));
    RepositoryService service = service(repositories);

    RepositoryView updated = service.replaceMembers(
        "composer-root", List.of("composer-hosted", "composer-nested"));

    assertEquals(List.of("composer-hosted", "composer-nested"), updated.group().memberNames());
    assertEquals(List.of(31L, 32L), repositories.membersByGroupId.get(30L));
  }

  @Test
  void replaceMembersStillRejectsNestedGroupsForOtherFormats() {
    StubRepositoryDao repositories = new StubRepositoryDao(
        mavenGroupRepository(20L, "maven-public"),
        mavenGroupRepository(21L, "maven-nested"));
    RepositoryService service = service(repositories);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.replaceMembers("maven-public", List.of("maven-nested")));

    assertTrue(thrown.getMessage().contains("nested-groups=[maven-nested]"));
  }

  @Test
  void replaceMembersRejectsPubGroupCycles() {
    StubRepositoryDao repositories = new StubRepositoryDao(
        pubGroupRepository(10L, "pub-root"),
        pubGroupRepository(12L, "pub-nested"));
    repositories.replaceMembers(12L, List.of(10L));
    RepositoryService service = service(repositories);

    RepositoryValidationException thrown = assertThrows(RepositoryValidationException.class,
        () -> service.replaceMembers("pub-root", List.of("pub-nested")));

    assertTrue(thrown.getMessage().contains("cyclic-groups=[pub-nested]"));
  }

  private static RepositoryService service(StubRepositoryDao repositories) {
    return new RepositoryService(
        repositories,
        new StubBlobStoreDao(),
        new StubSecurityDao(),
        new RepositoryRuntimeRegistry(repositories, 0),
        "/repository");
  }

  private static RepositoryService service(StubRepositoryDao repositories, int serverPort, int managementPort) {
    return new RepositoryService(
        repositories,
        new StubBlobStoreDao(),
        new StubSecurityDao(),
        new RepositoryRuntimeRegistry(repositories, 0),
        "/repository",
        serverPort,
        managementPort);
  }

  private static RepositoryRecord repository(long blobStoreId) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "maven2-hosted");
    return new RepositoryRecord(
        10L,
        "maven-releases",
        RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED,
        "maven2-hosted",
        true,
        blobStoreId,
        null,
        null,
        "RELEASE",
        "STRICT",
        "ALLOW_ONCE",
        true,
        attributes);
  }

  private static RepositoryRecord dockerRepository(long id, String name, int connectorPort) {
    Map<String, Object> docker = new LinkedHashMap<>();
    docker.put("connectorEnabled", true);
    docker.put("connectorPort", connectorPort);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "docker-hosted");
    attributes.put("docker", docker);
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        attributes);
  }

  private static RepositoryRecord dockerProxyRepository() {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "http://127.0.0.1:5000");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "docker-proxy");
    attributes.put("proxy", proxy);
    return new RepositoryRecord(
        30L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        "http://127.0.0.1:5000",
        null,
        null,
        null,
        true,
        attributes);
  }

  private static RepositoryRecord pubHostedRepository(long id, String name) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "pub-hosted");
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.PUB,
        RepositoryType.HOSTED,
        "pub-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        attributes);
  }

  private static RepositoryRecord pubGroupRepository(long id, String name) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "pub-group");
    attributes.put("group", Map.of());
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.PUB,
        RepositoryType.GROUP,
        "pub-group",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        attributes);
  }

  private static RepositoryRecord composerHostedRepository(long id, String name) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "composer-hosted");
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.COMPOSER,
        RepositoryType.HOSTED,
        "composer-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        attributes);
  }

  private static RepositoryRecord composerGroupRepository(long id, String name) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "composer-group");
    attributes.put("group", Map.of());
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.COMPOSER,
        RepositoryType.GROUP,
        "composer-group",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        attributes);
  }

  private static RepositoryRecord mavenGroupRepository(long id, String name) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", "maven2-group");
    attributes.put("group", Map.of());
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        RepositoryType.GROUP,
        "maven2-group",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        attributes);
  }

  private static final class StubRepositoryDao extends RepositoryDaoAdapter {
    private final List<RepositoryRecord> repositories = new ArrayList<>();
    private final Map<Long, List<Long>> membersByGroupId = new LinkedHashMap<>();
    private RepositoryRecord repository;
    private RepositoryRecord updated;
    private long nextId = 100L;

    private StubRepositoryDao(RepositoryRecord... repositories) {
      super(null, null);
      for (RepositoryRecord record : repositories) {
        this.repositories.add(record);
      }
      this.repository = repositories.length == 0 ? null : repositories[0];
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return repositories.stream()
          .filter(row -> row.name().equals(name))
          .findFirst();
    }

    @Override
    public Optional<RepositoryRecord> findById(long id) {
      return repositories.stream()
          .filter(row -> row.id().equals(id))
          .findFirst();
    }

    @Override
    public boolean existsByName(String name) {
      return repositories.stream().anyMatch(row -> row.name().equals(name));
    }

    @Override
    public List<RepositoryRecord> list() {
      return List.copyOf(repositories);
    }

    @Override
    public long insert(RepositoryRecord record) {
      long id = nextId++;
      RepositoryRecord inserted = new RepositoryRecord(
          id,
          record.name(),
          record.format(),
          record.type(),
          record.recipeName(),
          record.online(),
          record.blobStoreId(),
          record.routingRuleId(),
          record.proxyRemoteUrl(),
          record.versionPolicy(),
          record.layoutPolicy(),
          record.writePolicy(),
          record.strictContentTypeValidation(),
          record.attributes());
      repositories.add(inserted);
      repository = inserted;
      return id;
    }

    @Override
    public List<RepositoryRecord> listGroupsContaining(long memberRepositoryId) {
      return membersByGroupId.entrySet().stream()
          .filter(entry -> entry.getValue().contains(memberRepositoryId))
          .map(entry -> findById(entry.getKey()).orElse(null))
          .filter(row -> row != null)
          .toList();
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return membersByGroupId.getOrDefault(groupRepositoryId, List.of()).stream()
          .map(id -> findById(id).orElse(null))
          .filter(row -> row != null)
          .toList();
    }

    @Override
    public Map<Long, List<String>> listAllGroupMembers() {
      Map<Long, List<String>> result = new LinkedHashMap<>();
      for (Map.Entry<Long, List<Long>> entry : membersByGroupId.entrySet()) {
        result.put(entry.getKey(), entry.getValue().stream()
            .map(id -> findById(id).map(RepositoryRecord::name).orElse(null))
            .filter(name -> name != null)
            .toList());
      }
      return result;
    }

    @Override
    public void replaceMembers(long groupRepositoryId, List<Long> memberRepositoryIds) {
      membersByGroupId.put(groupRepositoryId, List.copyOf(memberRepositoryIds));
    }

    @Override
    public void update(RepositoryRecord record) {
      updated = record;
      repositories.removeIf(row -> row.id().equals(record.id()));
      repositories.add(record);
      repository = record;
    }
  }

  private static final class StubBlobStoreDao extends BlobStoreDaoAdapter {
    private final List<BlobStoreRecord> stores = List.of(
        blobStore(1L, "default"),
        blobStore(2L, "v2"));

    private StubBlobStoreDao() {
      super(null, null);
    }

    @Override
    public Optional<BlobStoreRecord> findByName(String name) {
      return stores.stream()
          .filter(store -> store.name().equals(name))
          .findFirst();
    }

    @Override
    public List<BlobStoreRecord> list() {
      return stores;
    }
  }

  private static final class StubSecurityDao extends SecurityDaoAdapter {
    private final List<SecurityPrivilegeRecord> privileges = new ArrayList<>();

    private StubSecurityDao() {
      super(null, null);
    }

    @Override
    public void insertPrivilegeIfAbsent(SecurityPrivilegeRecord record) {
      if (privileges.stream().noneMatch(existing -> existing.privilegeId().equals(record.privilegeId()))) {
        privileges.add(record);
      }
    }

    @Override
    public void removePrivilegeReferences(String privilegeId) {
    }

    @Override
    public int deletePrivilege(String privilegeId) {
      boolean removed = privileges.removeIf(record -> record.privilegeId().equals(privilegeId));
      return removed ? 1 : 0;
    }
  }

  private static BlobStoreRecord blobStore(long id, String name) {
    return new BlobStoreRecord(id, name, "s3", null, null, "bucket", null, Map.of());
  }
}
