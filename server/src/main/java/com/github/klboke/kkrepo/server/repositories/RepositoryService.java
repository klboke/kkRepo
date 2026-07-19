package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipe;
import com.github.klboke.kkrepo.core.RepositoryRecipes;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.docker.DockerConnectorRuntime;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmGroupPackumentCache;
import com.github.klboke.kkrepo.server.pypi.PypiGroupSimpleIndexCache;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CargoSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.RawSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.UpdateCommand;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache;
import com.github.klboke.kkrepo.server.security.SecurityCatalogCache;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RepositoryService {
  private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,199}$");
  private static final Set<String> WRITE_POLICIES = Set.of("ALLOW", "ALLOW_ONCE", "DENY");
  private static final Set<String> MAVEN_VERSION_POLICIES = Set.of("RELEASE", "SNAPSHOT", "MIXED");
  private static final Set<String> MAVEN_LAYOUT_POLICIES = Set.of("STRICT", "PERMISSIVE");
  private static final Set<String> RAW_CONTENT_DISPOSITIONS = Set.of("INLINE", "ATTACHMENT");

  private final RepositoryDao repositoryDao;
  private final BlobStoreDao blobStoreDao;
  private final SecurityDao securityDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final ProxyNegativeCache proxyNegativeCache;
  private final SecurityAuthorizationCache authorizationCache;
  private final SecurityCatalogCache securityCatalogCache;
  private final OutboundRequestPolicy outboundPolicy;
  private final NpmGroupPackumentCache npmGroupPackumentCache;
  private final PypiGroupSimpleIndexCache pypiGroupSimpleIndexCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final NexusLikeCacheController cacheController;
  private final RepositoryCatalogCache repositoryCatalogCache;
  private final DockerConnectorRuntime dockerConnectorRuntime;
  private final TerraformRegistryDao terraformRegistry;
  private final ProxiedHttpClientFactory proxiedHttpClientFactory;
  private final SwiftRegistryDao swiftRegistry;
  private final String urlPrefix;
  private final int serverPort;
  private final int managementPort;

  @Autowired
  public RepositoryService(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      SecurityDao securityDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      ProxyNegativeCache proxyNegativeCache,
      SecurityAuthorizationCache authorizationCache,
      SecurityCatalogCache securityCatalogCache,
      OutboundRequestPolicy outboundPolicy,
      NpmGroupPackumentCache npmGroupPackumentCache,
      PypiGroupSimpleIndexCache pypiGroupSimpleIndexCache,
      GroupMemberAssetCache groupMemberAssetCache,
      NexusLikeCacheController cacheController,
      RepositoryCatalogCache repositoryCatalogCache,
      DockerConnectorRuntime dockerConnectorRuntime,
      TerraformRegistryDao terraformRegistry,
      ProxiedHttpClientFactory proxiedHttpClientFactory,
      SwiftRegistryDao swiftRegistry,
      @Value("${kkrepo.compatibility.repository-url-prefix:/repository}") String urlPrefix,
      @Value("${server.port:8080}") int serverPort,
      @Value("${management.server.port:${server.port:8080}}") int managementPort) {
    this.repositoryDao = repositoryDao;
    this.blobStoreDao = blobStoreDao;
    this.securityDao = securityDao;
    this.runtimeRegistry = runtimeRegistry;
    this.proxyNegativeCache = proxyNegativeCache;
    this.authorizationCache = authorizationCache;
    this.securityCatalogCache = securityCatalogCache;
    this.outboundPolicy = outboundPolicy;
    this.npmGroupPackumentCache = npmGroupPackumentCache;
    this.pypiGroupSimpleIndexCache = pypiGroupSimpleIndexCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.cacheController = cacheController;
    this.repositoryCatalogCache = repositoryCatalogCache;
    this.dockerConnectorRuntime = dockerConnectorRuntime;
    this.terraformRegistry = terraformRegistry;
    this.proxiedHttpClientFactory = proxiedHttpClientFactory;
    this.swiftRegistry = swiftRegistry;
    this.urlPrefix = urlPrefix;
    this.serverPort = serverPort;
    this.managementPort = managementPort;
  }

  public RepositoryService(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      SecurityDao securityDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      String urlPrefix) {
    this(repositoryDao, blobStoreDao, securityDao, runtimeRegistry, null, null,
        null, OutboundRequestPolicy.allowPrivateForTests(), null, null, null, null, null, null,
        null, null, null, urlPrefix, 8080, 8080);
  }

  public RepositoryService(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      SecurityDao securityDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      NexusLikeCacheController cacheController,
      String urlPrefix) {
    this(repositoryDao, blobStoreDao, securityDao, runtimeRegistry, null, null,
        null, OutboundRequestPolicy.allowPrivateForTests(), null, null, null, cacheController, null, null,
        null, null, null, urlPrefix, 8080, 8080);
  }

  RepositoryService(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      SecurityDao securityDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      ProxiedHttpClientFactory proxiedHttpClientFactory,
      String urlPrefix) {
    this(repositoryDao, blobStoreDao, securityDao, runtimeRegistry, null, null,
        null, OutboundRequestPolicy.allowPrivateForTests(), null, null, null, null, null, null,
        null, proxiedHttpClientFactory, null, urlPrefix, 8080, 8080);
  }

  RepositoryService(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      SecurityDao securityDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      String urlPrefix,
      int serverPort,
      int managementPort) {
    this(repositoryDao, blobStoreDao, securityDao, runtimeRegistry, null, null,
        null, OutboundRequestPolicy.allowPrivateForTests(), null, null, null, null, null, null, null, null, null,
        urlPrefix, serverPort, managementPort);
  }

  @Transactional(readOnly = true)
  public List<RepositoryView> list() {
    if (repositoryCatalogCache != null) {
      var cached = repositoryCatalogCache.current();
      if (cached.isPresent()) {
        RepositoryCatalogCache.RepositoryCatalog catalog = cached.get();
        List<RepositoryView> result = new ArrayList<>(catalog.records().size());
        for (RepositoryRecord record : catalog.records()) {
          result.add(toView(record, catalog.blobStoreNames(), catalog.membersOf(record.id())));
        }
        return result;
      }
    }
    List<RepositoryRecord> records = repositoryDao.list();
    Map<Long, String> blobStoreNames = blobStoreNameIndex();
    List<RepositoryView> result = new ArrayList<>(records.size());
    for (RepositoryRecord record : records) {
      result.add(toView(record, blobStoreNames));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public RepositoryView get(String name) {
    RepositoryRecord record = repositoryDao.findByName(name)
        .orElseThrow(() -> new RepositoryNotFoundException(name));
    return toView(record, blobStoreNameIndex());
  }

  @Transactional
  public RepositoryView create(CreateCommand command) {
    String name = requireName(command.name());
    RepositoryRecipe recipe = requireRecipe(command.recipe());
    if (repositoryDao.existsByName(name)) {
      throw new RepositoryValidationException("Repository name already exists: " + name);
    }

    boolean online = command.online() == null ? true : command.online();
    boolean strict = command.strictContentTypeValidation() == null
        ? true
        : command.strictContentTypeValidation();

    Long blobStoreId = resolveBlobStoreId(command.blobStoreName());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", recipe.name());
    if (recipe.format() == RepositoryFormat.RAW) {
      attributes.put("raw", rawAttributes(command.raw()));
    }
    if (recipe.format() == RepositoryFormat.DOCKER) {
      DockerSettings docker = normalizeDocker(command.docker());
      validateDockerConnectorPort(null, docker);
      attributes.put("docker", dockerAttributes(docker));
    }
    if (usesCargoAuthenticationHint(recipe.format(), recipe.type())) {
      attributes.put("cargo", cargoAttributes(normalizeCargo(command.cargo())));
    }

    String versionPolicy = null;
    String layoutPolicy = null;
    String writePolicy = null;
    String proxyRemoteUrl = null;

    switch (recipe.type()) {
      case HOSTED -> {
        HostedSettings hosted = requireHosted(command.hosted(), recipe.format());
        writePolicy = hosted.writePolicy();
        versionPolicy = hosted.versionPolicy();
        layoutPolicy = hosted.layoutPolicy();
      }
      case PROXY -> {
        ProxySettings proxy = requireProxy(command.proxy(), recipe.format());
        proxyRemoteUrl = proxy.remoteUrl();
        attributes.put("proxy", proxyAttributes(proxy));
      }
      case GROUP -> attributes.put("group", Map.of());
    }

    RepositoryRecord toInsert = new RepositoryRecord(
        null, name, recipe.format(), recipe.type(), recipe.name(), online,
        blobStoreId, null, proxyRemoteUrl, versionPolicy, layoutPolicy, writePolicy,
        strict, attributes);
    long id = repositoryDao.insert(toInsert);

    if (recipe.type() == RepositoryType.GROUP) {
      List<Long> memberIds = resolveMemberIds(name, recipe.format(), command.group());
      repositoryDao.replaceMembers(id, memberIds);
    }

    invalidateRuntimeCache(id, name);
    invalidateRepositoryCacheTokensAfterCommit(id);
    NexusRepositorySecurityContributor.ensureRepositoryPrivileges(securityDao, recipe.format(), name);
    invalidateAuthorizationCacheAfterCommit();
    refreshRepositoryCatalogAfterCommit();
    syncDockerConnectorsAfterCommit(recipe.format());
    return get(name);
  }

  @Transactional
  public RepositoryView update(String name, UpdateCommand command) {
    RepositoryRecord existing = repositoryDao.findByName(name)
        .orElseThrow(() -> new RepositoryNotFoundException(name));
    RepositoryRecipe recipe = RepositoryRecipes.byName(existing.recipeName())
        .orElseThrow(() -> new RepositoryValidationException(
            "Stored recipe is unknown: " + existing.recipeName()));

    boolean online = command.online() == null ? existing.online() : command.online();
    boolean strict = command.strictContentTypeValidation() == null
        ? existing.strictContentTypeValidation()
        : command.strictContentTypeValidation();

    Long blobStoreId = requireUnchangedBlobStore(existing, command.blobStoreName());

    Map<String, Object> attributes = new LinkedHashMap<>(
        existing.attributes() == null ? Map.of() : existing.attributes());
    attributes.put("recipe", recipe.name());
    if (recipe.format() == RepositoryFormat.RAW) {
      RawSettings current = readRawAttributes(existing);
      RawSettings merged = command.raw() == null
          ? current
          : new RawSettings(command.raw().contentDisposition() == null
              ? current.contentDisposition()
              : command.raw().contentDisposition());
      attributes.put("raw", rawAttributes(merged));
    }
    if (recipe.format() == RepositoryFormat.DOCKER) {
      DockerSettings current = readDockerAttributes(existing);
      DockerSettings merged = mergeDocker(current, command.docker());
      validateDockerConnectorPort(existing.id(), merged);
      attributes.put("docker", dockerAttributes(merged));
    }
    if (usesCargoAuthenticationHint(recipe.format(), existing.type())) {
      CargoSettings current = readCargoAttributes(existing);
      CargoSettings merged = mergeCargo(current, command.cargo());
      attributes.put("cargo", cargoAttributes(merged));
    } else {
      attributes.remove("cargo");
    }

    String versionPolicy = existing.versionPolicy();
    String layoutPolicy = existing.layoutPolicy();
    String writePolicy = existing.writePolicy();
    String proxyRemoteUrl = existing.proxyRemoteUrl();

    switch (recipe.type()) {
      case HOSTED -> {
        if (command.hosted() != null) {
          HostedSettings merged = mergeHosted(existing, command.hosted());
          validateHosted(merged, recipe.format());
          writePolicy = merged.writePolicy();
          versionPolicy = merged.versionPolicy();
          layoutPolicy = merged.layoutPolicy();
        }
      }
      case PROXY -> {
        ProxySettings existingProxy = readProxyAttributes(existing);
        ProxySettings merged = requireProxy(
            mergeProxy(existingProxy, existing.proxyRemoteUrl(), command.proxy()), recipe.format());
        proxyRemoteUrl = merged.remoteUrl();
        attributes.put("proxy", proxyAttributes(merged));
      }
      case GROUP -> {
        // nothing extra on the repository row; members are replaced separately if provided
      }
    }

    RepositoryRecord toUpdate = new RepositoryRecord(
        existing.id(), existing.name(), existing.format(), existing.type(), existing.recipeName(),
        online, blobStoreId, existing.routingRuleId(), proxyRemoteUrl,
        versionPolicy, layoutPolicy, writePolicy, strict, attributes);
    repositoryDao.update(toUpdate);
    evictStaleOutboundProxyClient(existing.name(), existing.attributes(), attributes);

    if (recipe.type() == RepositoryType.GROUP && command.group() != null) {
      List<Long> memberIds = resolveMemberIds(name, recipe.format(), command.group());
      repositoryDao.replaceMembers(existing.id(), memberIds);
      invalidateNpmGroupAfterCommit(existing.format(), existing.id());
      invalidatePypiGroupAfterCommit(existing.format(), existing.id());
      invalidateGroupMemberGroupAfterCommit(existing.format(), existing.id());
      invalidateTerraformGroupBindings(existing.format(), existing.id());
      invalidateSwiftGroupBindings(existing.format(), existing.id());
    } else if (recipe.type() != RepositoryType.GROUP) {
      invalidateNpmMemberAfterCommit(existing.format(), existing.id());
      invalidatePypiMemberAfterCommit(existing.format(), existing.id());
      invalidateGroupMemberMemberAfterCommit(existing.format(), existing.id());
      invalidateTerraformContainingGroupBindings(existing.format(), existing.id(), new HashSet<>());
      invalidateSwiftContainingGroupBindings(existing.format(), existing.id(), new HashSet<>());
    }

    invalidateRuntimeCache(existing.id(), name);
    invalidateRepositoryCacheTokensAfterCommit(existing.id());
    refreshRepositoryCatalogAfterCommit();
    syncDockerConnectorsAfterCommit(existing.format());
    return get(name);
  }

  @Transactional
  public void delete(String name) {
    RepositoryRecord existing = repositoryDao.findByName(name)
        .orElseThrow(() -> new RepositoryNotFoundException(name));
    List<RepositoryRecord> groups = repositoryDao.listGroupsContaining(existing.id());
    if (!groups.isEmpty()) {
      throw new RepositoryValidationException(
          "Repository '" + name + "' is a member of group(s): "
              + groups.stream().map(RepositoryRecord::name).toList());
    }
    if (repositoryDao.hasComponents(existing.id())) {
      throw new RepositoryValidationException(
          "Repository '" + name + "' still has components. Empty it before deletion.");
    }
    if (existing.type() == RepositoryType.GROUP) {
      repositoryDao.clearMembers(existing.id());
    }
    int removed = repositoryDao.deleteById(existing.id());
    if (removed == 0) {
      throw new RepositoryNotFoundException(name);
    }
    evictOutboundProxyClient(existing.name(), existing.attributes());
    NexusRepositorySecurityContributor.removeRepositoryPrivileges(securityDao, existing.format(), name);
    invalidateRuntimeCache(existing.id(), name);
    invalidateRepositoryCacheTokensAfterCommit(existing.id());
    invalidateAuthorizationCacheAfterCommit();
    refreshRepositoryCatalogAfterCommit();
    syncDockerConnectorsAfterCommit(existing.format());
  }

  @Transactional
  public RepositoryView replaceMembers(String name, List<String> memberNames) {
    RepositoryRecord existing = repositoryDao.findByName(name)
        .orElseThrow(() -> new RepositoryNotFoundException(name));
    if (existing.type() != RepositoryType.GROUP) {
      throw new RepositoryValidationException("Repository '" + name + "' is not a group");
    }
    List<Long> memberIds = resolveMemberIds(name, existing.format(), new GroupSettings(memberNames));
    repositoryDao.replaceMembers(existing.id(), memberIds);
    invalidateNpmGroupAfterCommit(existing.format(), existing.id());
    invalidatePypiGroupAfterCommit(existing.format(), existing.id());
    invalidateGroupMemberGroupAfterCommit(existing.format(), existing.id());
    invalidateTerraformGroupBindings(existing.format(), existing.id());
    invalidateSwiftGroupBindings(existing.format(), existing.id());
    runtimeRegistry.invalidate(name);
    invalidateRepositoryCacheTokensAfterCommit(existing.id());
    refreshRepositoryCatalogAfterCommit();
    return get(name);
  }

  public List<RepositoryRecipe> recipes() {
    return RepositoryRecipes.list();
  }

  /**
   * Drop the cached runtime for {@code name} and every group whose member set includes it. Group
   * runtimes embed their members' resolved settings, so a change to a hosted/proxy must bust the
   * cached group too — otherwise the group keeps serving the previous member snapshot until TTL.
   */
  private void invalidateRuntimeCache(long repositoryId, String name) {
    runtimeRegistry.invalidate(name);
    if (proxyNegativeCache != null) {
      proxyNegativeCache.invalidateRepository(repositoryId);
    }
    invalidateContainingRuntimeCaches(repositoryId, new HashSet<>());
  }

  /**
   * Evicts the cached outbound-proxy HTTP client owned by this repository when the proxy block
   * changed (or disappeared) across an update, so a rotated credential or a switched-off proxy
   * never keeps a stale connection pool alive until the idle TTL. Compared on the collision-safe
   * cache key, so an untouched block keeps its pooled client. Eviction is scoped to this
   * repository's own client — another repository with identical proxy settings keeps its pool.
   */
  private void evictStaleOutboundProxyClient(
      String repositoryName, Map<String, Object> previousAttributes, Map<String, Object> newAttributes) {
    if (proxiedHttpClientFactory == null) {
      return;
    }
    OutboundProxyConfig previous = OutboundProxyConfig.fromAttributes(proxyChildMap(previousAttributes));
    if (previous == null) {
      return;
    }
    OutboundProxyConfig current = OutboundProxyConfig.fromAttributes(proxyChildMap(newAttributes));
    if (current != null && current.cacheKey().equals(previous.cacheKey())) {
      return;
    }
    proxiedHttpClientFactory.invalidate(repositoryName, previous);
  }

  private void evictOutboundProxyClient(String repositoryName, Map<String, Object> attributes) {
    if (proxiedHttpClientFactory == null) {
      return;
    }
    proxiedHttpClientFactory.invalidate(
        repositoryName, OutboundProxyConfig.fromAttributes(proxyChildMap(attributes)));
  }

  private static Map<?, ?> proxyChildMap(Map<String, Object> attributes) {
    if (attributes == null) {
      return null;
    }
    Object proxy = attributes.get("proxy");
    return proxy instanceof Map<?, ?> map ? map : null;
  }

  private void invalidateContainingRuntimeCaches(long repositoryId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(repositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      runtimeRegistry.invalidate(group.name());
      invalidateContainingRuntimeCaches(groupId, visited);
    }
  }

  private void invalidateTerraformGroupBindings(RepositoryFormat format, long groupRepositoryId) {
    if (format != RepositoryFormat.TERRAFORM || terraformRegistry == null) {
      return;
    }
    terraformRegistry.deleteSourceBindings(groupRepositoryId);
    invalidateTerraformContainingGroupBindings(format, groupRepositoryId, new HashSet<>());
  }

  private void invalidateTerraformContainingGroupBindings(
      RepositoryFormat format, long repositoryId, Set<Long> visited) {
    if (format != RepositoryFormat.TERRAFORM || terraformRegistry == null) {
      return;
    }
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(repositoryId)) {
      if (group.id() == null || !visited.add(group.id())) {
        continue;
      }
      terraformRegistry.deleteSourceBindings(group.id());
      invalidateTerraformContainingGroupBindings(format, group.id(), visited);
    }
  }

  private void invalidateSwiftGroupBindings(RepositoryFormat format, long groupRepositoryId) {
    if (format != RepositoryFormat.SWIFT || swiftRegistry == null) {
      return;
    }
    // The shared revision is the fencing token for group membership snapshots. Bump it before
    // deleting bindings so an in-flight request using the old member order cannot recreate one.
    swiftRegistry.nextRepositoryRevision(groupRepositoryId);
    swiftRegistry.deleteGroupSourceBindings(groupRepositoryId);
    invalidateSwiftContainingGroupBindings(format, groupRepositoryId, new HashSet<>());
  }

  private void invalidateSwiftContainingGroupBindings(
      RepositoryFormat format, long repositoryId, Set<Long> visited) {
    if (format != RepositoryFormat.SWIFT || swiftRegistry == null) {
      return;
    }
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(repositoryId)) {
      if (group.id() == null || !visited.add(group.id())) {
        continue;
      }
      swiftRegistry.nextRepositoryRevision(group.id());
      swiftRegistry.deleteGroupSourceBindings(group.id());
      invalidateSwiftContainingGroupBindings(format, group.id(), visited);
    }
  }

  private DockerSettings normalizeDocker(DockerSettings settings) {
    if (settings == null) {
      return new DockerSettings(false, null, null);
    }
    Integer port = settings.connectorPort();
    Boolean enabled = settings.connectorEnabled();
    if (enabled == null) {
      enabled = port != null;
    }
    if (Boolean.FALSE.equals(enabled)) {
      port = null;
    }
    return new DockerSettings(enabled, port, blankToNull(settings.connectorPublicUrl()));
  }

  @SuppressWarnings("unchecked")
  private DockerSettings readDockerAttributes(RepositoryRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object raw = attrs.get("docker");
    if (!(raw instanceof Map<?, ?> map)) {
      return new DockerSettings(false, null, null);
    }
    return new DockerSettings(
        boolValue(map.get("connectorEnabled")),
        intValue(map.get("connectorPort")),
        blankToNull(map.get("connectorPublicUrl") == null ? null : map.get("connectorPublicUrl").toString()));
  }

  private DockerSettings mergeDocker(DockerSettings current, DockerSettings update) {
    if (update == null) {
      return normalizeDocker(current);
    }
    if (Boolean.FALSE.equals(update.connectorEnabled())) {
      return normalizeDocker(new DockerSettings(false, null,
          update.connectorPublicUrl() == null ? current.connectorPublicUrl() : update.connectorPublicUrl()));
    }
    return normalizeDocker(new DockerSettings(
        update.connectorEnabled() == null ? current.connectorEnabled() : update.connectorEnabled(),
        update.connectorPort() == null ? current.connectorPort() : update.connectorPort(),
        update.connectorPublicUrl() == null ? current.connectorPublicUrl() : update.connectorPublicUrl()));
  }

  private Map<String, Object> dockerAttributes(DockerSettings settings) {
    DockerSettings normalized = normalizeDocker(settings);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("connectorEnabled", normalized.connectorEnabled());
    if (normalized.connectorPort() != null) {
      attrs.put("connectorPort", normalized.connectorPort());
    }
    if (normalized.connectorPublicUrl() != null) {
      attrs.put("connectorPublicUrl", normalized.connectorPublicUrl());
    }
    return attrs;
  }

  private CargoSettings normalizeCargo(CargoSettings settings) {
    if (settings == null) {
      return new CargoSettings(false);
    }
    return new CargoSettings(Boolean.TRUE.equals(settings.requireAuthentication()));
  }

  private CargoSettings readCargoAttributes(RepositoryRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object raw = attrs.get("cargo");
    if (!(raw instanceof Map<?, ?> map)) {
      return new CargoSettings(false);
    }
    return new CargoSettings(boolValue(map.get("requireAuthentication")));
  }

  private CargoSettings mergeCargo(CargoSettings current, CargoSettings update) {
    if (update == null || update.requireAuthentication() == null) {
      return normalizeCargo(current);
    }
    return normalizeCargo(update);
  }

  private Map<String, Object> cargoAttributes(CargoSettings settings) {
    CargoSettings normalized = normalizeCargo(settings);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("requireAuthentication", normalized.requireAuthentication());
    return attrs;
  }

  private void validateDockerConnectorPort(Long existingRepositoryId, DockerSettings settings) {
    if (settings == null) {
      return;
    }
    if (Boolean.TRUE.equals(settings.connectorEnabled()) && settings.connectorPort() == null) {
      throw new RepositoryValidationException("docker.connector.port is required when connector is enabled");
    }
    if (settings.connectorPort() == null) {
      return;
    }
    int port = settings.connectorPort();
    if (port <= 0 || port > 65535) {
      throw new RepositoryValidationException("docker.connector.port must be between 1 and 65535");
    }
    if (port == serverPort) {
      throw new RepositoryValidationException(
          "docker.connector.port " + port + " conflicts with server.port");
    }
    if (port == managementPort) {
      throw new RepositoryValidationException(
          "docker.connector.port " + port + " conflicts with management.server.port");
    }
    for (RepositoryRecord record : repositoryDao.list()) {
      if (existingRepositoryId != null && Objects.equals(existingRepositoryId, record.id())) {
        continue;
      }
      if (record.format() != RepositoryFormat.DOCKER) {
        continue;
      }
      DockerSettings other = readDockerAttributes(record);
      if (Objects.equals(other.connectorPort(), port)) {
        throw new RepositoryValidationException(
            "docker.connector.port " + port + " is already used by repository " + record.name());
      }
    }
  }

  private void invalidateRepositoryCacheTokensAfterCommit(long repositoryId) {
    if (cacheController == null) {
      return;
    }
    invalidateRepositoryCacheTokensAfterCommit(repositoryId, new HashSet<>());
  }

  private void invalidateRepositoryCacheTokensAfterCommit(long repositoryId, Set<Long> visited) {
    if (!visited.add(repositoryId)) {
      return;
    }
    cacheController.invalidateAllAfterCommit(repositoryId);
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(repositoryId)) {
      Long groupId = group.id();
      if (groupId != null) {
        invalidateRepositoryCacheTokensAfterCommit(groupId, visited);
      }
    }
  }

  private void invalidateAuthorizationCacheAfterCommit() {
    if (authorizationCache != null) {
      authorizationCache.invalidateAllAfterCommit();
    }
    if (securityCatalogCache != null) {
      securityCatalogCache.refreshAfterCommit();
    }
  }

  /**
   * Reload the repository catalog snapshot after commit and broadcast a refresh so sibling replicas
   * pick up membership / config changes within the broadcast poll interval instead of via TTL.
   */
  private void refreshRepositoryCatalogAfterCommit() {
    if (repositoryCatalogCache != null) {
      repositoryCatalogCache.refreshAfterCommit();
    }
  }

  private void syncDockerConnectorsAfterCommit(RepositoryFormat format) {
    if (dockerConnectorRuntime == null || format != RepositoryFormat.DOCKER) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      dockerConnectorRuntime.sync();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        dockerConnectorRuntime.sync();
      }
    });
  }

  private void invalidateNpmMemberAfterCommit(RepositoryFormat format, long repositoryId) {
    if (npmGroupPackumentCache != null && format == RepositoryFormat.NPM) {
      npmGroupPackumentCache.invalidateMemberAfterCommit(repositoryId);
    }
  }

  private void invalidateNpmGroupAfterCommit(RepositoryFormat format, long groupId) {
    if (npmGroupPackumentCache != null && format == RepositoryFormat.NPM) {
      npmGroupPackumentCache.invalidateGroupAfterCommit(groupId);
    }
  }

  private void invalidatePypiMemberAfterCommit(RepositoryFormat format, long repositoryId) {
    if (pypiGroupSimpleIndexCache != null && format == RepositoryFormat.PYPI) {
      pypiGroupSimpleIndexCache.invalidateMemberAfterCommit(repositoryId);
    }
  }

  private void invalidatePypiGroupAfterCommit(RepositoryFormat format, long groupId) {
    if (pypiGroupSimpleIndexCache != null && format == RepositoryFormat.PYPI) {
      pypiGroupSimpleIndexCache.invalidateGroupAfterCommit(groupId);
    }
  }

  private void invalidateGroupMemberMemberAfterCommit(RepositoryFormat format, long repositoryId) {
    if (groupMemberAssetCache != null && usesGroupMemberAssetCache(format)) {
      groupMemberAssetCache.invalidateMemberAfterCommit(repositoryId);
    }
  }

  private void invalidateGroupMemberGroupAfterCommit(RepositoryFormat format, long groupId) {
    if (groupMemberAssetCache != null && usesGroupMemberAssetCache(format)) {
      groupMemberAssetCache.invalidateGroupAfterCommit(groupId);
    }
  }

  private static boolean usesGroupMemberAssetCache(RepositoryFormat format) {
    return format == RepositoryFormat.NPM
        || format == RepositoryFormat.PYPI
        || format == RepositoryFormat.DOCKER
        || format == RepositoryFormat.PUB
        || format == RepositoryFormat.COMPOSER;
  }

  private static boolean usesCargoAuthenticationHint(RepositoryFormat format, RepositoryType type) {
    return format == RepositoryFormat.CARGO && (type == RepositoryType.PROXY || type == RepositoryType.GROUP);
  }

  // ---- view assembly --------------------------------------------------------

  private RepositoryView toView(RepositoryRecord record, Map<Long, String> blobStoreNames) {
    List<String> groupMemberNames = record.type() == RepositoryType.GROUP
        ? repositoryDao.listMembers(record.id()).stream().map(RepositoryRecord::name).toList()
        : List.of();
    return toView(record, blobStoreNames, groupMemberNames);
  }

  private RepositoryView toView(
      RepositoryRecord record, Map<Long, String> blobStoreNames, List<String> groupMemberNames) {
    String blobStoreName = record.blobStoreId() == null
        ? null
        : blobStoreNames.get(record.blobStoreId());
    String url = urlPrefix.endsWith("/")
        ? urlPrefix + record.name() + "/"
        : urlPrefix + "/" + record.name() + "/";

    HostedSettings hosted = null;
    ProxySettings proxy = null;
    RawSettings raw = record.format() == RepositoryFormat.RAW ? readRawAttributes(record) : null;
    DockerSettings docker = record.format() == RepositoryFormat.DOCKER ? readDockerAttributes(record) : null;
    CargoSettings cargo = usesCargoAuthenticationHint(record.format(), record.type())
        ? readCargoAttributes(record)
        : null;
    GroupSettings group = null;
    switch (record.type()) {
      case HOSTED -> hosted = new HostedSettings(
          record.writePolicy(), record.versionPolicy(), record.layoutPolicy());
      case PROXY -> proxy = readProxyAttributesOrDefaults(record);
      case GROUP -> group = new GroupSettings(groupMemberNames == null ? List.of() : groupMemberNames);
    }

    return new RepositoryView(
        record.id(), record.name(), record.recipeName(),
        record.format(), record.type(), record.online(),
        blobStoreName, record.strictContentTypeValidation(), url,
        hosted, proxy, raw, docker, cargo, group);
  }

  private Map<Long, String> blobStoreNameIndex() {
    Map<Long, String> index = new LinkedHashMap<>();
    for (BlobStoreRecord record : blobStoreDao.list()) {
      if (record.id() != null) {
        index.put(record.id(), record.name());
      }
    }
    return index;
  }

  // ---- validation -----------------------------------------------------------

  private static String requireName(String name) {
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new RepositoryValidationException(
          "Invalid repository name. Allowed: letters, digits, '.', '_', '-' (max 200 chars).");
    }
    return name;
  }

  private static RepositoryRecipe requireRecipe(String recipeName) {
    return RepositoryRecipes.byName(recipeName)
        .orElseThrow(() -> new RepositoryValidationException(
            "Unknown recipe: " + recipeName
                + ". Known: " + RepositoryRecipes.list().stream().map(RepositoryRecipe::name).toList()));
  }

  private Long resolveBlobStoreId(String blobStoreName) {
    if (blobStoreName == null || blobStoreName.isBlank()) {
      throw new RepositoryValidationException("blobStoreName is required for repositories");
    }
    return blobStoreDao.findByName(blobStoreName)
        .map(BlobStoreRecord::id)
        .orElseThrow(() -> new RepositoryValidationException(
            "Blob store not found: " + blobStoreName));
  }

  private Long requireUnchangedBlobStore(RepositoryRecord existing, String incomingBlobStoreName) {
    if (incomingBlobStoreName == null) {
      return existing.blobStoreId();
    }
    Long incomingBlobStoreId = resolveBlobStoreId(incomingBlobStoreName);
    if (!Objects.equals(incomingBlobStoreId, existing.blobStoreId())) {
      throw new RepositoryValidationException("blobStoreName cannot be changed after repository creation");
    }
    return existing.blobStoreId();
  }

  private static HostedSettings requireHosted(HostedSettings settings, RepositoryFormat format) {
    if (settings == null) {
      throw new RepositoryValidationException("hosted settings are required for hosted repositories");
    }
    validateHosted(settings, format);
    return settings;
  }

  private static void validateHosted(HostedSettings settings, RepositoryFormat format) {
    if (settings.writePolicy() == null || !WRITE_POLICIES.contains(settings.writePolicy())) {
      throw new RepositoryValidationException(
          "hosted.writePolicy must be one of " + WRITE_POLICIES);
    }
    if (format == RepositoryFormat.MAVEN2) {
      if (settings.versionPolicy() == null || !MAVEN_VERSION_POLICIES.contains(settings.versionPolicy())) {
        throw new RepositoryValidationException(
            "hosted.versionPolicy must be one of " + MAVEN_VERSION_POLICIES + " for maven hosted");
      }
      if (settings.layoutPolicy() == null || !MAVEN_LAYOUT_POLICIES.contains(settings.layoutPolicy())) {
        throw new RepositoryValidationException(
            "hosted.layoutPolicy must be one of " + MAVEN_LAYOUT_POLICIES + " for maven hosted");
      }
    }
  }

  private static HostedSettings mergeHosted(RepositoryRecord existing, HostedSettings incoming) {
    return new HostedSettings(
        incoming.writePolicy() == null ? existing.writePolicy() : incoming.writePolicy(),
        incoming.versionPolicy() == null ? existing.versionPolicy() : incoming.versionPolicy(),
        incoming.layoutPolicy() == null ? existing.layoutPolicy() : incoming.layoutPolicy());
  }

  private ProxySettings requireProxy(ProxySettings settings, RepositoryFormat format) {
    String defaultRemote = defaultRemoteUrl(format);
    if (settings == null && defaultRemote != null) {
      settings = new ProxySettings(defaultRemote, null, null, null);
    }
    if (settings == null) {
      throw new RepositoryValidationException("proxy settings are required for proxy repositories");
    }
    if (defaultRemote != null
        && (settings.remoteUrl() == null || settings.remoteUrl().isBlank())) {
      settings = withRemoteUrl(settings, defaultRemote);
    }
    if (format == RepositoryFormat.SWIFT) {
      settings = withRemoteUrl(settings, normalizeSwiftRemoteUrl(settings.remoteUrl()));
    }
    validateProxy(settings, format);
    return normalizeOutboundProxyType(settings);
  }

  /**
   * Rewrites a validated outbound proxy type to its canonical enum name (HTTP/SOCKS) so aliases
   * the validator accepts (e.g. "socks5", lowercase "http") round-trip through the admin UI,
   * whose select only offers the canonical values. Without this, loading an API-created alias
   * leaves the select blank and the next save clears the whole outbound proxy block.
   */
  private static ProxySettings normalizeOutboundProxyType(ProxySettings settings) {
    String raw = settings.outboundProxyType();
    if (raw == null || raw.isBlank()) {
      return settings;
    }
    com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.Type type =
        com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.parseType(raw);
    if (type == null || type.name().equals(raw)) {
      return settings;
    }
    return new ProxySettings(
        settings.remoteUrl(),
        settings.contentMaxAgeMinutes(),
        settings.metadataMaxAgeMinutes(),
        settings.autoBlock(),
        settings.remoteUsername(),
        settings.remotePassword(),
        settings.remotePasswordConfigured(),
        settings.remoteBearerToken(),
        settings.remoteBearerTokenConfigured(),
        type.name(),
        settings.outboundProxyHost(),
        settings.outboundProxyPort(),
        settings.outboundProxyUsername(),
        settings.outboundProxyPassword(),
        settings.outboundProxyPasswordConfigured(),
        settings.minimumReleaseAgeMinutes());
  }

  private static String defaultRemoteUrl(RepositoryFormat format) {
    return switch (format) {
      case PUB -> "https://pub.dev/";
      case COMPOSER -> "https://repo.packagist.org/";
      case TERRAFORM -> "https://registry.terraform.io/";
      case SWIFT -> "https://github.com/";
      default -> null;
    };
  }

  /**
   * Rebuilds settings with a new remote URL while preserving every other field, including the
   * outbound network proxy. Using the compatibility constructor here previously dropped all
   * outbound proxy fields to null whenever a default remote was applied.
   */
  private static ProxySettings withRemoteUrl(ProxySettings settings, String remoteUrl) {
    return new ProxySettings(
        remoteUrl,
        settings.contentMaxAgeMinutes(),
        settings.metadataMaxAgeMinutes(),
        settings.autoBlock(),
        settings.remoteUsername(),
        settings.remotePassword(),
        settings.remotePasswordConfigured(),
        settings.remoteBearerToken(),
        settings.remoteBearerTokenConfigured(),
        settings.outboundProxyType(),
        settings.outboundProxyHost(),
        settings.outboundProxyPort(),
        settings.outboundProxyUsername(),
        settings.outboundProxyPassword(),
        settings.outboundProxyPasswordConfigured(),
        settings.minimumReleaseAgeMinutes());
  }

  private void validateProxy(ProxySettings settings, RepositoryFormat format) {
    if (settings.remoteUrl() == null || settings.remoteUrl().isBlank()) {
      throw new RepositoryValidationException("proxy.remoteUrl is required");
    }
    if (settings.remotePassword() != null && !settings.remotePassword().isBlank()
        && (settings.remoteUsername() == null || settings.remoteUsername().isBlank())) {
      throw new RepositoryValidationException("proxy.remoteUsername is required when proxy.remotePassword is set");
    }
    try {
      outboundPolicy.validateHttpUri(settings.remoteUrl(), "proxy.remoteUrl");
    } catch (SecurityValidationException e) {
      throw new RepositoryValidationException(e.getMessage());
    }
    validateOutboundProxy(settings);
    int minimumReleaseAge = settings.minimumReleaseAgeMinutes() == null
        ? 0
        : settings.minimumReleaseAgeMinutes();
    if (minimumReleaseAge < 0) {
      throw new RepositoryValidationException("proxy.minimumReleaseAgeMinutes must be at least 0");
    }
    if (minimumReleaseAge > 0 && format != RepositoryFormat.NPM) {
      throw new RepositoryValidationException(
          "proxy.minimumReleaseAgeMinutes is only supported for npm proxy repositories");
    }
    if (format == RepositoryFormat.SWIFT) {
      normalizeSwiftRemoteUrl(settings.remoteUrl());
    }
  }

  private void validateOutboundProxy(ProxySettings settings) {
    boolean hasType = settings.outboundProxyType() != null && !settings.outboundProxyType().isBlank();
    boolean hasHost = settings.outboundProxyHost() != null && !settings.outboundProxyHost().isBlank();
    Integer port = settings.outboundProxyPort();
    if (!hasType && !hasHost && port == null
        && (settings.outboundProxyUsername() == null || settings.outboundProxyUsername().isBlank())
        && (settings.outboundProxyPassword() == null || settings.outboundProxyPassword().isBlank())) {
      return;
    }
    if (!hasHost) {
      throw new RepositoryValidationException("proxy.outboundProxyHost is required when an outbound proxy is configured");
    }
    if (port == null || port < 1 || port > 65535) {
      throw new RepositoryValidationException("proxy.outboundProxyPort must be between 1 and 65535");
    }
    com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.Type type =
        com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.parseType(settings.outboundProxyType());
    if (type == null) {
      throw new RepositoryValidationException(
          "proxy.outboundProxyType must be HTTP or SOCKS (was: " + settings.outboundProxyType() + ")");
    }
    if ((settings.outboundProxyPassword() != null && !settings.outboundProxyPassword().isBlank())
        && (settings.outboundProxyUsername() == null || settings.outboundProxyUsername().isBlank())) {
      throw new RepositoryValidationException(
          "proxy.outboundProxyUsername is required when proxy.outboundProxyPassword is set");
    }
  }

  private static String normalizeSwiftRemoteUrl(String remoteUrl) {
    String candidate = remoteUrl == null || remoteUrl.isBlank() ? "https://github.com/" : remoteUrl.trim();
    try {
      URI uri = new URI(candidate);
      String path = uri.getPath();
      boolean githubRoot = "https".equalsIgnoreCase(uri.getScheme())
          && "github.com".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null
          && (path == null || path.isBlank() || "/".equals(path));
      if (!githubRoot) {
        throw new RepositoryValidationException(
            "Swift proxy.remoteUrl must be the GitHub base URL https://github.com/");
      }
      return "https://github.com/";
    } catch (URISyntaxException e) {
      throw new RepositoryValidationException(
          "Swift proxy.remoteUrl must be the GitHub base URL https://github.com/");
    }
  }

  private static ProxySettings mergeProxy(ProxySettings existing, String existingRemoteUrl, ProxySettings incoming) {
    if (incoming == null) {
      return existing == null
          ? new ProxySettings(existingRemoteUrl, null, null, null)
          : existing;
    }
    ProxySettings base = existing == null
        ? new ProxySettings(existingRemoteUrl, null, null, null)
        : existing;

    // The outbound proxy is one logical block governed by outboundProxyType:
    //  - null/absent type  => caller did not touch the block => preserve the saved values;
    //  - blank type ("")   => "Direct (no proxy)" selected    => clear the whole block, including
    //                        host/port/username/password, so upstream fetches stop being proxied;
    //  - non-blank type    => caller is setting/changing the proxy => take the incoming values and
    //                        let validateOutboundProxy reject invalid types / missing host+port.
    String mergedOutboundProxyType;
    String mergedOutboundProxyHost;
    Integer mergedOutboundProxyPort;
    String mergedOutboundProxyUsername;
    String mergedOutboundProxyPassword;
    if (incoming.outboundProxyType() == null) {
      mergedOutboundProxyType = base.outboundProxyType();
      mergedOutboundProxyHost = base.outboundProxyHost();
      mergedOutboundProxyPort = base.outboundProxyPort();
      mergedOutboundProxyUsername = base.outboundProxyUsername();
      mergedOutboundProxyPassword = base.outboundProxyPassword();
    } else if (incoming.outboundProxyType().isBlank()) {
      mergedOutboundProxyType = null;
      mergedOutboundProxyHost = null;
      mergedOutboundProxyPort = null;
      mergedOutboundProxyUsername = null;
      mergedOutboundProxyPassword = null;
    } else {
      mergedOutboundProxyType = incoming.outboundProxyType();
      mergedOutboundProxyHost = blankToNull(incoming.outboundProxyHost());
      mergedOutboundProxyPort = incoming.outboundProxyPort();
      mergedOutboundProxyUsername = blankToNull(incoming.outboundProxyUsername());
      mergedOutboundProxyPassword = mergedOutboundProxyPassword(base, incoming);
    }

    return new ProxySettings(
        incoming.remoteUrl() == null ? base.remoteUrl() : incoming.remoteUrl(),
        incoming.contentMaxAgeMinutes() == null ? base.contentMaxAgeMinutes() : incoming.contentMaxAgeMinutes(),
        incoming.metadataMaxAgeMinutes() == null ? base.metadataMaxAgeMinutes() : incoming.metadataMaxAgeMinutes(),
        incoming.autoBlock() == null ? base.autoBlock() : incoming.autoBlock(),
        incoming.remoteUsername() == null ? base.remoteUsername() : blankToNull(incoming.remoteUsername()),
        mergedProxyPassword(base, incoming),
        null,
        mergedProxyBearerToken(base, incoming),
        null,
        mergedOutboundProxyType,
        mergedOutboundProxyHost,
        mergedOutboundProxyPort,
        mergedOutboundProxyUsername,
        mergedOutboundProxyPassword,
        null,
        incoming.minimumReleaseAgeMinutes() == null
            ? base.minimumReleaseAgeMinutes()
            : incoming.minimumReleaseAgeMinutes());
  }

  private static String mergedOutboundProxyPassword(ProxySettings base, ProxySettings incoming) {
    if (incoming.outboundProxyPassword() != null && !incoming.outboundProxyPassword().isBlank()) {
      return incoming.outboundProxyPassword();
    }
    if (Boolean.FALSE.equals(incoming.outboundProxyPasswordConfigured())) {
      return null;
    }
    return base.outboundProxyPassword();
  }

  private static String mergedProxyPassword(ProxySettings base, ProxySettings incoming) {
    if (incoming.remotePassword() != null && !incoming.remotePassword().isBlank()) {
      return incoming.remotePassword();
    }
    if (Boolean.FALSE.equals(incoming.remotePasswordConfigured())) {
      return null;
    }
    return base.remotePassword();
  }

  private static String mergedProxyBearerToken(ProxySettings base, ProxySettings incoming) {
    if (incoming.remoteBearerToken() != null && !incoming.remoteBearerToken().isBlank()) {
      return incoming.remoteBearerToken();
    }
    if (Boolean.FALSE.equals(incoming.remoteBearerTokenConfigured())) {
      return null;
    }
    return base.remoteBearerToken();
  }

  private static Map<String, Object> proxyAttributes(ProxySettings proxy) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("remoteUrl", proxy.remoteUrl());
    if (proxy.contentMaxAgeMinutes() != null) map.put("contentMaxAgeMinutes", proxy.contentMaxAgeMinutes());
    if (proxy.metadataMaxAgeMinutes() != null) map.put("metadataMaxAgeMinutes", proxy.metadataMaxAgeMinutes());
    if (proxy.minimumReleaseAgeMinutes() != null) {
      map.put("minimumReleaseAgeMinutes", proxy.minimumReleaseAgeMinutes());
    }
    if (proxy.autoBlock() != null) map.put("autoBlock", proxy.autoBlock());
    if (proxy.remoteUsername() != null && !proxy.remoteUsername().isBlank()) {
      map.put("remoteUsername", proxy.remoteUsername());
    }
    if (proxy.remotePassword() != null && !proxy.remotePassword().isBlank()) {
      map.put("remotePassword", proxy.remotePassword());
    }
    if (proxy.remoteBearerToken() != null && !proxy.remoteBearerToken().isBlank()) {
      map.put("remoteBearerToken", proxy.remoteBearerToken());
    }
    if (proxy.outboundProxyType() != null && !proxy.outboundProxyType().isBlank()) {
      map.put("outboundProxyType", proxy.outboundProxyType());
    }
    if (proxy.outboundProxyHost() != null && !proxy.outboundProxyHost().isBlank()) {
      map.put("outboundProxyHost", proxy.outboundProxyHost());
    }
    if (proxy.outboundProxyPort() != null) {
      map.put("outboundProxyPort", proxy.outboundProxyPort());
    }
    if (proxy.outboundProxyUsername() != null && !proxy.outboundProxyUsername().isBlank()) {
      map.put("outboundProxyUsername", proxy.outboundProxyUsername());
    }
    if (proxy.outboundProxyPassword() != null && !proxy.outboundProxyPassword().isBlank()) {
      map.put("outboundProxyPassword", proxy.outboundProxyPassword());
    }
    return map;
  }

  private static Map<String, Object> rawAttributes(RawSettings raw) {
    RawSettings effective = raw == null ? new RawSettings("ATTACHMENT") : raw;
    String disposition = normalizeRawContentDisposition(effective.contentDisposition());
    return Map.of("contentDisposition", disposition);
  }

  private static RawSettings readRawAttributes(RepositoryRecord record) {
    Map<String, Object> attrs = record.attributes();
    Object raw = attrs == null ? null : attrs.get("raw");
    if (raw instanceof Map<?, ?> rawMap) {
      return new RawSettings(normalizeRawContentDisposition(stringValue(
          rawMap.get("contentDisposition"), "ATTACHMENT")));
    }
    return new RawSettings("ATTACHMENT");
  }

  private static String normalizeRawContentDisposition(String value) {
    String normalized = value == null || value.isBlank()
        ? "ATTACHMENT"
        : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!RAW_CONTENT_DISPOSITIONS.contains(normalized)) {
      throw new RepositoryValidationException(
          "raw.contentDisposition must be one of " + RAW_CONTENT_DISPOSITIONS);
    }
    return normalized;
  }

  private static ProxySettings readProxyAttributes(RepositoryRecord record) {
    Map<String, Object> attrs = record.attributes();
    Object raw = attrs == null ? null : attrs.get("proxy");
    if (!(raw instanceof Map<?, ?> proxyMap)) {
      return new ProxySettings(record.proxyRemoteUrl(), null, null, null);
    }
    return new ProxySettings(
        stringValue(proxyMap.get("remoteUrl"), record.proxyRemoteUrl()),
        intValue(proxyMap.get("contentMaxAgeMinutes")),
        intValue(proxyMap.get("metadataMaxAgeMinutes")),
        boolValue(proxyMap.get("autoBlock")),
        blankToNull(proxyMap.get("remoteUsername") == null ? null : proxyMap.get("remoteUsername").toString()),
        blankToNull(proxyMap.get("remotePassword") == null ? null : proxyMap.get("remotePassword").toString()),
        null,
        blankToNull(proxyMap.get("remoteBearerToken") == null ? null : proxyMap.get("remoteBearerToken").toString()),
        null,
        stringOrNull(proxyMap.get("outboundProxyType")),
        blankToNull(stringOrNull(proxyMap.get("outboundProxyHost"))),
        intValue(proxyMap.get("outboundProxyPort")),
        blankToNull(stringOrNull(proxyMap.get("outboundProxyUsername"))),
        blankToNull(stringOrNull(proxyMap.get("outboundProxyPassword"))),
        null,
        intValue(proxyMap.get("minimumReleaseAgeMinutes")));
  }

  private static ProxySettings readProxyAttributesOrDefaults(RepositoryRecord record) {
    ProxySettings parsed = readProxyAttributes(record);
    ProxySettings effective = parsed == null
        ? new ProxySettings(record.proxyRemoteUrl(), null, null, null)
        : parsed;
    String outboundPassword = effective.outboundProxyPassword();
    return new ProxySettings(
        effective.remoteUrl(),
        effective.contentMaxAgeMinutes(),
        effective.metadataMaxAgeMinutes(),
        effective.autoBlock(),
        effective.remoteUsername(),
        null,
        effective.remotePassword() != null && !effective.remotePassword().isBlank(),
        null,
        effective.remoteBearerToken() != null && !effective.remoteBearerToken().isBlank(),
        effective.outboundProxyType(),
        effective.outboundProxyHost(),
        effective.outboundProxyPort(),
        effective.outboundProxyUsername(),
        null,
        outboundPassword != null && !outboundPassword.isBlank(),
        effective.minimumReleaseAgeMinutes() == null ? 0 : effective.minimumReleaseAgeMinutes());
  }

  private static String stringOrNull(Object value) {
    return value == null ? null : value.toString();
  }

  private List<Long> resolveMemberIds(String groupName, RepositoryFormat format, GroupSettings group) {
    if (group == null || group.memberNames() == null) {
      return List.of();
    }
    List<String> names = group.memberNames();
    Set<String> seen = new LinkedHashSet<>();
    List<Long> ids = new ArrayList<>(names.size());
    Set<String> badFormat = new HashSet<>();
    Set<String> nested = new HashSet<>();
    Set<String> cycles = new HashSet<>();
    Set<String> missing = new HashSet<>();
    for (String memberName : names) {
      if (memberName == null || memberName.isBlank()) continue;
      if (memberName.equals(groupName)) {
        throw new RepositoryValidationException("Group cannot include itself: " + groupName);
      }
      if (!seen.add(memberName)) continue;
      RepositoryRecord member = repositoryDao.findByName(memberName).orElse(null);
      if (member == null) {
        missing.add(memberName);
        continue;
      }
      if (member.format() != format) {
        badFormat.add(memberName);
        continue;
      }
      if (member.type() == RepositoryType.GROUP && !supportsNestedGroups(format)) {
        nested.add(memberName);
        continue;
      }
      if (member.type() == RepositoryType.GROUP
          && groupWouldCreateCycle(member, groupName, format, new HashSet<>())) {
        cycles.add(memberName);
        continue;
      }
      ids.add(member.id());
    }
    if (!missing.isEmpty() || !badFormat.isEmpty() || !nested.isEmpty() || !cycles.isEmpty()) {
      StringBuilder sb = new StringBuilder("Invalid group members for '").append(groupName).append("':");
      if (!missing.isEmpty()) sb.append(" missing=").append(missing);
      if (!badFormat.isEmpty()) sb.append(" wrong-format=").append(badFormat);
      if (!nested.isEmpty()) sb.append(" nested-groups=").append(nested);
      if (!cycles.isEmpty()) sb.append(" cyclic-groups=").append(cycles);
      throw new RepositoryValidationException(sb.toString());
    }
    return ids;
  }

  private boolean groupWouldCreateCycle(
      RepositoryRecord candidateGroup,
      String targetGroupName,
      RepositoryFormat format,
      Set<Long> visited) {
    Long candidateId = candidateGroup.id();
    if (candidateId == null || !visited.add(candidateId)) {
      return false;
    }
    for (RepositoryRecord member : repositoryDao.listMembers(candidateId)) {
      if (targetGroupName.equals(member.name())) {
        return true;
      }
      if (member.type() == RepositoryType.GROUP
          && member.format() == format
          && groupWouldCreateCycle(member, targetGroupName, format, visited)) {
        return true;
      }
    }
    return false;
  }

  private static boolean supportsNestedGroups(RepositoryFormat format) {
    return format == RepositoryFormat.PUB || format == RepositoryFormat.COMPOSER
        || format == RepositoryFormat.TERRAFORM || format == RepositoryFormat.SWIFT;
  }

  private static String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static Integer intValue(Object value) {
    if (value == null) return null;
    if (value instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Boolean boolValue(Object value) {
    if (value == null) return null;
    if (value instanceof Boolean b) return b;
    return Boolean.parseBoolean(value.toString());
  }
}
