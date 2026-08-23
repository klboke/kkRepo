package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.server.conda.CondaBrowsePaths;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService.RepositoryAccessMode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/search/components")
public class ComponentSearchController {
  private static final int DEFAULT_LIMIT = 300;
  private static final int SELECTOR_PAGE_SIZE = 200;
  private static final int MAX_SELECTOR_CANDIDATES = 1_000;

  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final SwiftRegistryDao swiftRegistry;
  private final RepositoryCatalogCache repositoryCatalogCache;
  private AnsibleGalaxyRegistryDao ansibleRegistry;
  private AptRegistryDao aptRegistry;
  private AlpineRegistryDao alpineRegistry;
  private RRegistryDao rRegistry;
  private CondaRegistryDao condaRegistry;

  @Autowired
  public ComponentSearchController(
      ComponentDao componentDao,
      AssetDao assetDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      SwiftRegistryDao swiftRegistry,
      RepositoryCatalogCache repositoryCatalogCache) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.swiftRegistry = swiftRegistry;
    this.repositoryCatalogCache = repositoryCatalogCache;
  }

  @Autowired(required = false)
  void setAnsibleGalaxyRegistry(AnsibleGalaxyRegistryDao ansibleRegistry) {
    this.ansibleRegistry = ansibleRegistry;
  }

  @Autowired(required = false)
  void setAptRegistry(AptRegistryDao aptRegistry) {
    this.aptRegistry = aptRegistry;
  }

  @Autowired(required = false)
  void setAlpineRegistry(AlpineRegistryDao alpineRegistry) {
    this.alpineRegistry = alpineRegistry;
  }

  @Autowired(required = false)
  void setRRegistry(RRegistryDao rRegistry) {
    this.rRegistry = rRegistry;
  }

  @Autowired(required = false)
  void setCondaRegistry(CondaRegistryDao condaRegistry) {
    this.condaRegistry = condaRegistry;
  }

  @GetMapping
  public ComponentSearchResponse search(
      @RequestParam(value = "q", required = false) String keyword,
      @RequestParam(value = "format", required = false) String format,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "distribution", required = false) String distribution,
      @RequestParam(value = "component", required = false) String component,
      @RequestParam(value = "architecture", required = false) String architecture,
      @RequestParam(value = "sourcePackage", required = false) String sourcePackage,
      @RequestParam(value = "checksum", required = false) String checksum,
      HttpServletRequest request) {
    return searchInternal(
        keyword,
        format,
        limit,
        new AptSearchFilters(
            distribution, component, architecture, sourcePackage, checksum),
        request);
  }

  ComponentSearchResponse search(
      String keyword,
      String format,
      Integer limit,
      HttpServletRequest request) {
    return searchInternal(keyword, format, limit, AptSearchFilters.EMPTY, request);
  }

  private ComponentSearchResponse searchInternal(
      String keyword,
      String format,
      Integer limit,
      AptSearchFilters aptFilters,
      HttpServletRequest request) {
    AuthenticatedSubject subject = currentOrAnonymous(request).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    requireSearch(subject);
    int effectiveLimit = limit == null
        ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, DEFAULT_LIMIT));
    RepositoryFormat repositoryFormat = parseFormat(format);
    AptSearchFilters normalizedFilters = aptFilters.normalized();
    if (normalizedFilters.present() && repositoryFormat != RepositoryFormat.APT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "APT coordinate filters require format=apt");
    }
    return searchAuthorized(
        subject, keyword, repositoryFormat, effectiveLimit, normalizedFilters);
  }

  private ComponentSearchResponse searchAuthorized(
      AuthenticatedSubject subject,
      String keyword,
      RepositoryFormat repositoryFormat,
      int effectiveLimit,
      AptSearchFilters filters) {
    RepositoryCatalogCache.RepositoryCatalog catalog = repositoryCatalogCache.snapshot();
    List<RepositoryRecord> searchableRepositories = catalog.records().stream()
        .filter(record -> record.id() != null && record.name() != null && record.format() != null)
        .filter(record -> repositoryFormat == null || record.format() == repositoryFormat)
        .toList();
    List<RepositoryPermission> requestedScopes = searchableRepositories.stream()
        .map(record -> new RepositoryPermission(
            record.name(), record.format(), "", PermissionAction.BROWSE))
        .distinct()
        .toList();
    Map<RepositoryPermission, RepositoryAccessMode> accessModes =
        securityService.repositoryAccessModes(subject.permissionSubject(), requestedScopes);
    SearchAccessScope scope = buildSearchAccessScope(
        searchableRepositories, catalog, accessModes);
    if (scope.empty()) {
      return new ComponentSearchResponse(effectiveLimit, 0, List.of());
    }

    int candidateLimit = filters.present() ? DEFAULT_LIMIT : effectiveLimit;
    List<ComponentSearchRow> visible = new ArrayList<>();
    boolean truncated = false;
    if (!scope.fullRepositoryIds().isEmpty()) {
      componentDao.searchPageByRepositoryIds(
              scope.fullRepositoryIds(), repositoryFormat, keyword, null, candidateLimit).stream()
          .filter(ComponentSearchController::searchVisible)
          .map(row -> withBrowseContext(
              row, scope.fullContext(row.repositoryId()), row.storagePath()))
          .filter(java.util.Objects::nonNull)
          .forEach(visible::add);
    }
    if (!scope.selectorRepositoryIds().isEmpty()) {
      SelectorSearchResult selectorResult = searchSelectorRows(
          subject, scope, keyword, repositoryFormat, candidateLimit, filters.present());
      visible.addAll(selectorResult.rows());
      truncated = selectorResult.truncated();
    }

    Comparator<ComponentSearchRow> newestFirst = Comparator
        .comparing(
            ComponentSearchRow::lastUpdatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(ComponentSearchRow::id, Comparator.reverseOrder());
    List<ComponentSearchItem> items = visible.stream()
        .sorted(newestFirst)
        .map(this::toItem)
        .filter(filters::matches)
        .limit(effectiveLimit)
        .toList();
    return new ComponentSearchResponse(effectiveLimit, items.size(), items, truncated);
  }

  private SelectorSearchResult searchSelectorRows(
      AuthenticatedSubject subject,
      SearchAccessScope scope,
      String keyword,
      RepositoryFormat repositoryFormat,
      int candidateLimit,
      boolean scanForCoordinateFilters) {
    List<ComponentSearchRow> visible = new ArrayList<>();
    ComponentSearchCursor cursor = null;
    int scanned = 0;
    boolean reachedEnd = false;
    while (scanned < MAX_SELECTOR_CANDIDATES
        && (scanForCoordinateFilters || visible.size() < candidateLimit)) {
      int pageLimit = Math.min(SELECTOR_PAGE_SIZE, MAX_SELECTOR_CANDIDATES - scanned);
      List<ComponentSearchRow> page = componentDao.searchPageByRepositoryIds(
          scope.selectorRepositoryIds(), repositoryFormat, keyword, cursor, pageLimit);
      if (page.isEmpty()) {
        reachedEnd = true;
        break;
      }
      scanned += page.size();
      List<ComponentSearchRow> searchablePage = page.stream()
          .filter(ComponentSearchController::searchVisible)
          .toList();
      visible.addAll(authorizeSelectorPage(subject, scope, searchablePage));
      cursor = ComponentSearchCursor.after(page.getLast());
      if (page.size() < pageLimit) {
        reachedEnd = true;
        break;
      }
    }
    boolean truncated = !reachedEnd
        && scanned >= MAX_SELECTOR_CANDIDATES
        && (scanForCoordinateFilters || visible.size() < candidateLimit);
    return new SelectorSearchResult(List.copyOf(visible), truncated);
  }

  private List<ComponentSearchRow> authorizeSelectorPage(
      AuthenticatedSubject subject,
      SearchAccessScope scope,
      List<ComponentSearchRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, List<AssetRecord>> assetsByComponent = new LinkedHashMap<>();
    assetDao.listAssetsByComponents(rows.stream().map(ComponentSearchRow::id).toList())
        .forEach(asset -> {
          if (asset.componentId() != null && asset.path() != null
              && !BrowseAssetVisibility.hidden(asset.format(), asset.path())) {
            assetsByComponent.computeIfAbsent(asset.componentId(), ignored -> new ArrayList<>())
                .add(asset);
          }
        });

    List<RepositoryPermission> pathPermissions = new ArrayList<>();
    for (ComponentSearchRow row : rows) {
      for (BrowseContext context : scope.selectorContexts(row.repositoryId())) {
        for (AssetRecord asset : assetsByComponent.getOrDefault(row.id(), List.of())) {
          if (asset.repositoryId() == row.repositoryId()) {
            pathPermissions.add(pathPermission(context, row.format(), asset.path()));
          }
        }
      }
    }
    Map<RepositoryPermission, AccessDecision> decisions = securityService.decideAll(
        subject.permissionSubject(), pathPermissions);

    List<ComponentSearchRow> allowed = new ArrayList<>();
    for (ComponentSearchRow row : rows) {
      ComponentSearchRow authorized = null;
      for (BrowseContext context : scope.selectorContexts(row.repositoryId())) {
        for (AssetRecord asset : assetsByComponent.getOrDefault(row.id(), List.of())) {
          if (asset.repositoryId() != row.repositoryId()) {
            continue;
          }
          AccessDecision decision = decisions.get(
              pathPermission(context, row.format(), asset.path()));
          if (decision != null && decision.allowed()) {
            authorized = withBrowseContext(row, context, asset.path());
            break;
          }
        }
        if (authorized != null) {
          break;
        }
      }
      if (authorized != null) {
        allowed.add(authorized);
      }
    }
    return allowed;
  }

  private SearchAccessScope buildSearchAccessScope(
      List<RepositoryRecord> records,
      RepositoryCatalogCache.RepositoryCatalog catalog,
      Map<RepositoryPermission, RepositoryAccessMode> accessModes) {
    SearchAccessScope scope = new SearchAccessScope();
    Map<String, RepositoryRecord> recordsByName = new LinkedHashMap<>();
    records.forEach(record -> recordsByName.put(record.name(), record));

    for (RepositoryRecord record : records) {
      RepositoryAccessMode mode = accessModes.getOrDefault(
          repositoryPermission(record), RepositoryAccessMode.DENIED);
      scope.add(record.id(), new BrowseContext(record.name()), mode);
    }
    for (RepositoryRecord group : records) {
      if (group.type() != RepositoryType.GROUP) {
        continue;
      }
      RepositoryAccessMode mode = accessModes.getOrDefault(
          repositoryPermission(group), RepositoryAccessMode.DENIED);
      if (mode == RepositoryAccessMode.DENIED) {
        continue;
      }
      for (Long memberId : groupMemberRepositoryIds(group, catalog, recordsByName)) {
        scope.add(memberId, new BrowseContext(group.name()), mode);
      }
    }
    return scope;
  }

  private static Set<Long> groupMemberRepositoryIds(
      RepositoryRecord group,
      RepositoryCatalogCache.RepositoryCatalog catalog,
      Map<String, RepositoryRecord> recordsByName) {
    Set<Long> members = new LinkedHashSet<>();
    collectGroupMembers(group, group.format(), catalog, recordsByName, new HashSet<>(), members);
    return members;
  }

  private static void collectGroupMembers(
      RepositoryRecord group,
      RepositoryFormat groupFormat,
      RepositoryCatalogCache.RepositoryCatalog catalog,
      Map<String, RepositoryRecord> recordsByName,
      Set<Long> visitedGroups,
      Set<Long> members) {
    if (group.id() == null || !visitedGroups.add(group.id())) {
      return;
    }
    for (String memberName : catalog.membersOf(group.id())) {
      RepositoryRecord member = recordsByName.get(memberName);
      if (member == null || member.id() == null || member.format() != groupFormat) {
        continue;
      }
      members.add(member.id());
      if (member.type() == RepositoryType.GROUP) {
        collectGroupMembers(
            member, groupFormat, catalog, recordsByName, visitedGroups, members);
      }
    }
  }

  private static RepositoryPermission repositoryPermission(RepositoryRecord record) {
    return new RepositoryPermission(
        record.name(), record.format(), "", PermissionAction.BROWSE);
  }

  private static RepositoryPermission pathPermission(
      BrowseContext context,
      RepositoryFormat format,
      String path) {
    return new RepositoryPermission(
        context.repositoryName(), format, path, PermissionAction.BROWSE);
  }

  private static boolean searchVisible(ComponentSearchRow row) {
    return !BrowseAssetVisibility.hidden(row.format(), row.name())
        && !BrowseAssetVisibility.hidden(row.format(), row.storagePath());
  }

  private static ComponentSearchRow withBrowseContext(
      ComponentSearchRow row,
      BrowseContext context,
      String browsePath) {
    if (context == null) {
      return null;
    }
    return new ComponentSearchRow(
        row.id(),
        row.repositoryId(),
        context.repositoryName(),
        row.format(),
        row.namespace(),
        row.name(),
        row.version(),
        row.kind(),
        row.lastUpdatedAt(),
        browsePath);
  }

  private ComponentSearchItem toItem(ComponentSearchRow row) {
    return new ComponentSearchItem(
        row.repositoryName(),
        formatLabel(row.format()),
        row.namespace(),
        row.name(),
        row.version(),
        row.kind(),
        row.lastUpdatedAt(),
        browsePath(row),
        componentDetails(row));
  }

  private Map<String, Object> componentDetails(ComponentSearchRow row) {
    if (row.format() == RepositoryFormat.ANSIBLEGALAXY) {
      return ansibleDetails(row);
    }
    if (row.format() == RepositoryFormat.CONDA) {
      return condaDetails(row);
    }
    if (row.format() == RepositoryFormat.APT) {
      return aptDetails(row);
    }
    if (row.format() == RepositoryFormat.ALPINE) {
      return alpineDetails(row);
    }
    if (row.format() == RepositoryFormat.R) {
      return rDetails(row);
    }
    if (row.format() == RepositoryFormat.HUGGINGFACE) {
      return huggingFaceDetails(row);
    }
    return swiftDetails(row);
  }

  private Map<String, Object> huggingFaceDetails(ComponentSearchRow row) {
    LinkedHashMap<String, Object> details = new LinkedHashMap<>();
    componentDao.findById(row.id()).ifPresent(component -> {
      Map<String, Object> attributes = component.attributes() == null
          ? Map.of() : component.attributes();
      for (String key : List.of(
          "repoId", "commit", "requestedRef", "library", "pipeline", "license",
          "private", "gated")) {
        Object value = attributes.get(key);
        if (value != null) details.put(key, value);
      }
    });
    if (row.storagePath() != null) {
      assetDao.findAssetByPath(row.repositoryId(), row.storagePath()).ifPresent(asset -> {
        Map<String, Object> attributes = asset.attributes() == null
            ? Map.of() : asset.attributes();
        for (String key : List.of(
            "filePath", "fileKind", "gitOid", "lfsSha256", "expectedSize")) {
          Object value = attributes.get(key);
          if (value != null) details.put(key, value);
        }
        details.put("cacheState", "READY");
        details.put("sourceRepository", row.repositoryName());
      });
    }
    return Map.copyOf(details);
  }

  private Map<String, Object> aptDetails(ComponentSearchRow row) {
    if (aptRegistry == null || row.storagePath() == null) {
      return Map.of();
    }
    return aptRegistry.findPackageByPath(row.repositoryId(), row.storagePath())
        .map(record -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("distribution", record.distribution());
          details.put("component", record.component());
          details.put("architecture", record.architecture());
          if (record.sourcePackage() != null) details.put("sourcePackage", record.sourcePackage());
          details.put("filename", record.filename());
          details.put("sha256", record.sha256());
          details.put("size", record.size());
          details.put("sourceKind", record.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          for (String field : List.of("Section", "Priority", "Maintainer", "Description", "Depends")) {
            Object value = record.controlFields().get(field);
            if (value != null) details.put(field.substring(0, 1).toLowerCase(Locale.ROOT)
                + field.substring(1), value);
          }
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> alpineDetails(ComponentSearchRow row) {
    if (alpineRegistry == null || row.storagePath() == null) return Map.of();
    return alpineRegistry.findPackageByPath(row.repositoryId(), row.storagePath())
        .map(record -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("namespace", record.distribution());
          details.put("channel", record.component());
          details.put("repositoryArchitecture", record.architecture());
          details.put("packageArchitecture", record.packageArchitecture());
          details.put("filename", record.filename());
          details.put("identity", record.identity());
          details.put("dataSha256", record.dataSha256());
          details.put("sha256", record.sha256());
          details.put("size", record.size());
          details.put("sourceKind", record.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          for (Map.Entry<String, String> field : Map.of(
              "T", "description",
              "U", "url",
              "L", "license",
              "o", "origin",
              "m", "maintainer",
              "D", "depends",
              "p", "provides",
              "i", "installIf").entrySet()) {
            Object value = record.controlFields().get(field.getKey());
            if (value != null) details.put(field.getValue(), value);
          }
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> rDetails(ComponentSearchRow row) {
    if (rRegistry == null || row.storagePath() == null) return Map.of();
    return rRegistry.findPackageByPath(row.repositoryId(), row.storagePath())
        .map(record -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("namespace", record.distribution());
          details.put("filename", record.filename());
          details.put("md5", record.identity());
          details.put("sha256", record.sha256());
          details.put("size", record.size());
          details.put("sourceKind", record.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          for (String field : List.of(
              "License", "Depends", "Imports", "LinkingTo", "Suggests", "Enhances",
              "NeedsCompilation")) {
            Object value = record.controlFields().get(field);
            if (value != null) details.put(field, value);
          }
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> condaDetails(ComponentSearchRow row) {
    if (condaRegistry == null || row.storagePath() == null) {
      return Map.of();
    }
    Optional<CondaPath> coordinate = CondaBrowsePaths.packagePath(row.storagePath());
    if (coordinate.isEmpty()) {
      return Map.of();
    }
    CondaPath path = coordinate.orElseThrow();
    return condaRegistry.findPackage(
            row.repositoryId(), path.channel(), path.subdir(), path.filename())
        .map(record -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("channel", record.channel());
          details.put("subdir", record.subdir());
          details.put("build", record.build());
          details.put("buildNumber", record.buildNumber());
          details.put("archiveFormat", record.archiveFormat());
          if (record.md5() != null) details.put("md5", record.md5());
          if (record.sha256() != null) details.put("sha256", record.sha256());
          details.put("size", record.size());
          details.put("sourceKind", record.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          Object depends = record.metadata().get("depends");
          if (depends != null) details.put("depends", depends);
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> ansibleDetails(ComponentSearchRow row) {
    if (ansibleRegistry == null || row.namespace() == null || row.name() == null
        || row.version() == null) {
      return Map.of();
    }
    return ansibleRegistry.findVersion(
            row.repositoryId(), row.namespace(), row.name(), row.version())
        .map(version -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("artifactSha256", version.artifactSha256());
          details.put("artifactSize", version.artifactSize());
          details.put("dependencies", version.dependencies());
          if (version.requiresAnsible() != null) {
            details.put("requiresAnsible", version.requiresAnsible());
          }
          details.put("signatureCount", ansibleRegistry.listSignatures(version.id()).size());
          details.put("sourceKind", version.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> swiftDetails(ComponentSearchRow row) {
    if (swiftRegistry == null || row.format() != RepositoryFormat.SWIFT
        || row.namespace() == null || row.name() == null || row.version() == null) {
      return Map.of();
    }
    return swiftRegistry.findRelease(
            row.repositoryId(),
            row.namespace().toLowerCase(Locale.ROOT),
            row.name().toLowerCase(Locale.ROOT),
            row.version())
        .map(release -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("checksum", release.archiveSha256());
          details.put("signatureStatus",
              release.signatureFormat() == null ? "unsigned" : "signed");
          if (release.signatureFormat() != null) {
            details.put("signatureFormat", release.signatureFormat());
          }
          details.put("sourceKind", release.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          List<String> toolsVersions = swiftRegistry.listManifests(release.id()).stream()
              .map(SwiftRegistryDao.Manifest::toolsVersion)
              .filter(value -> value != null && !value.isBlank())
              .distinct()
              .sorted()
              .toList();
          details.put("swiftToolsVersions", toolsVersions);
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private static String browsePath(ComponentSearchRow row) {
    if (row.storagePath() == null || row.storagePath().isBlank()) {
      return null;
    }
    return row.storagePath();
  }

  private static RepositoryFormat parseFormat(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if ("custom".equals(normalized)) {
      return null;
    }
    if ("ansible".equals(normalized)) {
      return RepositoryFormat.ANSIBLEGALAXY;
    }
    try {
      return RepositoryFormat.fromJson(normalized);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unsupported repository format: " + normalized, exception);
    }
  }

  private static String formatLabel(RepositoryFormat format) {
    return format == null ? "" : format.name().toLowerCase(Locale.ROOT);
  }

  private Optional<AuthenticatedSubject> currentOrAnonymous(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isPresent()) {
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());
      return authenticated;
    }
    return authenticationService.authenticateAnonymous();
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return Optional.of(authenticated);
    }
    return Optional.empty();
  }

  private void requireSearch(AuthenticatedSubject subject) {
    AccessDecision decision = securityService.decide(subject.permissionSubject(), "nexus:search:read");
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  private static final class SearchAccessScope {
    private final Map<Long, List<BrowseContext>> full = new LinkedHashMap<>();
    private final Map<Long, List<BrowseContext>> selectors = new LinkedHashMap<>();

    void add(long repositoryId, BrowseContext context, RepositoryAccessMode mode) {
      if (mode == RepositoryAccessMode.FULL) {
        full.computeIfAbsent(repositoryId, ignored -> new ArrayList<>()).add(context);
        selectors.remove(repositoryId);
      } else if (mode == RepositoryAccessMode.CONTENT_SELECTOR
          && !full.containsKey(repositoryId)) {
        selectors.computeIfAbsent(repositoryId, ignored -> new ArrayList<>()).add(context);
      }
    }

    boolean empty() {
      return full.isEmpty() && selectors.isEmpty();
    }

    List<Long> fullRepositoryIds() {
      return List.copyOf(full.keySet());
    }

    List<Long> selectorRepositoryIds() {
      return List.copyOf(selectors.keySet());
    }

    BrowseContext fullContext(long repositoryId) {
      List<BrowseContext> contexts = full.get(repositoryId);
      return contexts == null || contexts.isEmpty() ? null : contexts.getFirst();
    }

    List<BrowseContext> selectorContexts(long repositoryId) {
      return selectors.getOrDefault(repositoryId, List.of());
    }
  }

  private record BrowseContext(String repositoryName) {
  }

  private record SelectorSearchResult(List<ComponentSearchRow> rows, boolean truncated) {
  }

  private record AptSearchFilters(
      String distribution,
      String component,
      String architecture,
      String sourcePackage,
      String checksum) {
    private static final AptSearchFilters EMPTY =
        new AptSearchFilters(null, null, null, null, null);

    AptSearchFilters normalized() {
      String normalizedChecksum = normalized(checksum);
      if (normalizedChecksum != null
          && (normalizedChecksum.length() < 8
              || normalizedChecksum.length() > 64
              || !normalizedChecksum.matches("[0-9a-fA-F]+"))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "APT checksum must be an 8-64 character hexadecimal prefix");
      }
      return new AptSearchFilters(
          normalized(distribution),
          normalized(component),
          normalized(architecture),
          normalized(sourcePackage),
          normalizedChecksum == null ? null : normalizedChecksum.toLowerCase(Locale.ROOT));
    }

    boolean present() {
      return distribution != null || component != null || architecture != null
          || sourcePackage != null || checksum != null;
    }

    boolean matches(ComponentSearchItem item) {
      if (!present()) return true;
      Map<String, Object> details = item.details();
      return matchesExact(distribution, details.get("distribution"))
          && matchesExact(component, details.get("component"))
          && matchesExact(architecture, details.get("architecture"))
          && matchesExact(sourcePackage, details.get("sourcePackage"))
          && matchesChecksum(checksum, details.get("sha256"));
    }

    private static boolean matchesExact(String expected, Object actual) {
      return expected == null
          || (actual != null && expected.equalsIgnoreCase(actual.toString()));
    }

    private static boolean matchesChecksum(String expected, Object actual) {
      return expected == null
          || (actual != null
              && actual.toString().toLowerCase(Locale.ROOT).startsWith(expected));
    }

    private static String normalized(String value) {
      return value == null || value.isBlank() ? null : value.trim();
    }
  }

  public record ComponentSearchResponse(
      int limit,
      int count,
      List<ComponentSearchItem> items,
      boolean truncated) {
    public ComponentSearchResponse(int limit, int count, List<ComponentSearchItem> items) {
      this(limit, count, items, false);
    }
  }

  public record ComponentSearchItem(
      String repository,
      String format,
      String group,
      String name,
      String version,
      String kind,
      Instant lastUpdatedAt,
      String browsePath,
      Map<String, Object> details) {
  }
}
