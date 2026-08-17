package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.CleanupFamilyCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtectionLookup;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsage;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.nuget.NugetPath;
import com.github.klboke.kkrepo.protocol.nuget.NugetPathParser;
import com.github.klboke.kkrepo.protocol.nuget.NugetPaths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CleanupSubjectScanner {
  private static final NugetPathParser NUGET_PATHS = new NugetPathParser();

  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final DockerRegistryDao dockerRegistryDao;
  private final CleanupPolicyCapabilities capabilities;
  private final CleanupPolicyDao cleanupDao;
  private final CleanupRuntimeProperties runtimeProperties;
  private final CleanupUsageTrackingService usageTracking;
  private HuggingFaceRegistryDao huggingFaceRegistry;

  @Autowired
  public CleanupSubjectScanner(
      ComponentDao componentDao,
      AssetDao assetDao,
      DockerRegistryDao dockerRegistryDao,
      CleanupPolicyCapabilities capabilities,
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties runtimeProperties,
      CleanupUsageTrackingService usageTracking) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.dockerRegistryDao = dockerRegistryDao;
    this.capabilities = capabilities;
    this.cleanupDao = cleanupDao;
    this.runtimeProperties = runtimeProperties;
    this.usageTracking = usageTracking;
  }

  CleanupSubjectScanner(
      ComponentDao componentDao,
      AssetDao assetDao,
      CleanupPolicyCapabilities capabilities) {
    this(componentDao, assetDao, null, capabilities, null, null, null);
  }

  @Autowired
  void setHuggingFaceRegistry(HuggingFaceRegistryDao huggingFaceRegistry) {
    this.huggingFaceRegistry = huggingFaceRegistry;
  }

  public ScanResult scan(
      RepositoryRecord repository,
      Map<String, Object> rawCriteria,
      int scanLimit,
      Instant cutoff) {
    return scan(
        repository,
        rawCriteria,
        scanLimit,
        cutoff,
        initialCursor(0, repository.id(), repository.format(), 0, 0));
  }

  public ScanResult scan(
      RepositoryRecord repository,
      Map<String, Object> rawCriteria,
      int scanLimit,
      Instant cutoff,
      CleanupScanCursor requestedCursor) {
    CleanupTargetRepositories.requireSupported(repository.type());
    CleanupCriteria criteria = CleanupCriteria.parse(rawCriteria);
    int effectiveLimit = Math.max(1, scanLimit);
    String safetyStatus = usageSafetyStatus(repository.id(), criteria, cutoff);
    CleanupScanCursor cursor = normalizeCursor(repository, requestedCursor);
    if (repository.format() == RepositoryFormat.DOCKER) {
      return scanDocker(
          repository, criteria, effectiveLimit, cutoff, safetyStatus, cursor);
    }
    SubjectPage page = scanPage(repository, cursor, effectiveLimit);
    List<Subject> subjects = page.subjects();
    String incompleteFamily = page.incompleteFamily();

    Map<String, Map<Long, Integer>> versionRanks = versionRanks(
        repository.format(), subjects, criteria.retainCount());
    List<MatchedSubject> matchedSubjects = new ArrayList<>();
    for (Subject subject : subjects) {
      if (incompleteFamily != null && incompleteFamily.equals(subject.familyKey())) {
        continue;
      }
      Integer rank = versionRanks.getOrDefault(subject.familyKey(), Map.of())
          .get(subject.identityId());
      if (criteria.retainCount() != null
          && (rank == null || rank <= criteria.retainCount())) {
        continue;
      }
      if (!criteria.matchesPattern(subject.simpleName())
          && !criteria.matchesPattern(subject.displayName())) {
        continue;
      }
      if (!criteria.matchesPublishedAt(subject.publishedAt(), cutoff)
          || !criteria.matchesLastDownloadedAt(
              subject.lastDownloadedAt(),
              usageCutoff(cutoff))) {
        continue;
      }
      if (safetyStatus != null) continue;
      matchedSubjects.add(new MatchedSubject(subject, criteria.matchReason(rank)));
    }
    List<Candidate> candidates = attachProtections(repository.id(), matchedSubjects, cutoff);
    return new ScanResult(
        subjects.size(),
        page.truncated(),
        incompleteFamily,
        List.copyOf(candidates),
        safetyStatus,
        cursor,
        page.nextCursor(),
        page.cursorWarning());
  }

  private SubjectPage scanPage(
      RepositoryRecord repository, CleanupScanCursor cursor, int scanLimit) {
    if ("ASSET".equals(cursor.phase())) {
      List<AssetWithBlob> fetched = assetDao.listUnboundAssetWithBlobPage(
          repository.id(), cursor.subjectId(), scanLimit + 1);
      List<AssetWithBlob> selectedRows = fetched.stream().limit(scanLimit).toList();
      List<Subject> subjects = assetSubjects(repository, selectedRows);
      if (fetched.size() > scanLimit) {
        return new SubjectPage(
            subjects,
            true,
            null,
            assetCursor(cursor, selectedRows.getLast().asset().id()),
            null);
      }
      return new SubjectPage(
          subjects, false, null, wrapCursor(cursor, "COMPONENT"), null);
    }

    CleanupFamilyCursor afterFamily = cursor.componentName() == null
        ? null
        : new CleanupFamilyCursor(
            cursor.componentNamespace(), cursor.componentName(), cursor.componentKind());
    List<ComponentRecord> fetched = componentDao.listCleanupPage(
        repository.id(), afterFamily, scanLimit + 1);
    List<ComponentRecord> components = fetched.stream().limit(scanLimit).toList();
    List<Subject> subjects = new ArrayList<>(componentSubjects(repository, components));
    if (fetched.size() > scanLimit) {
      ComponentRecord lookahead = fetched.get(scanLimit);
      String incompleteFamily = null;
      String cursorWarning = null;
      ComponentRecord resumeAfter = components.getLast();
      if (sameFamily(resumeAfter, lookahead)) {
        incompleteFamily = familyKey(resumeAfter);
        int firstIncomplete = components.size() - 1;
        while (firstIncomplete > 0
            && sameFamily(components.get(firstIncomplete - 1), resumeAfter)) {
          firstIncomplete--;
        }
        if (firstIncomplete == 0) {
          cursorWarning = "cleanup family exceeds scanLimitPerRepository and was skipped "
              + "for this cursor cycle: " + incompleteFamily;
        } else {
          resumeAfter = components.get(firstIncomplete - 1);
        }
      }
      return new SubjectPage(
          List.copyOf(subjects),
          true,
          incompleteFamily,
          componentCursor(cursor, resumeAfter),
          cursorWarning);
    }

    int remaining = scanLimit - subjects.size();
    List<AssetWithBlob> unbound = assetDao.listUnboundAssetWithBlobPage(
        repository.id(), 0, Math.max(1, remaining + 1));
    if (remaining == 0) {
      if (unbound.isEmpty()) {
        return new SubjectPage(
            List.copyOf(subjects), false, null, wrapCursor(cursor, "COMPONENT"), null);
      }
      return new SubjectPage(
          List.copyOf(subjects), true, null, assetCursor(cursor, 0), null);
    }
    List<AssetWithBlob> selectedRows = unbound.stream().limit(remaining).toList();
    subjects.addAll(assetSubjects(repository, selectedRows));
    if (unbound.size() > remaining) {
      return new SubjectPage(
          List.copyOf(subjects),
          true,
          null,
          assetCursor(cursor, selectedRows.getLast().asset().id()),
          null);
    }
    return new SubjectPage(
        List.copyOf(subjects), false, null, wrapCursor(cursor, "COMPONENT"), null);
  }

  private Subject componentSubject(
      RepositoryFormat format, ComponentRecord component, List<AssetRecord> assets) {
    return componentSubject(format, component, assets, false);
  }

  private Subject componentSubject(
      RepositoryFormat format,
      ComponentRecord component,
      List<AssetRecord> assets,
      boolean lockRelatedAssets) {
    List<AssetRecord> subjectAssets = expandRelatedAssets(format, assets, lockRelatedAssets);
    Map<Long, CleanupUsage> usage = assetUsage(
        subjectAssets.stream().map(AssetRecord::id).toList());
    return componentSubject(format, component, subjectAssets, usage);
  }

  private Subject componentSubject(
      RepositoryFormat format,
      ComponentRecord component,
      List<AssetRecord> subjectAssets,
      Map<Long, CleanupUsage> usage) {
    Instant lastDownloadedAt = subjectAssets.stream()
        .map(asset -> effectiveLastDownloaded(asset, usage.get(asset.id())))
        .filter(java.util.Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
    Instant publishedAt = subjectAssets.stream()
        .map(AssetRecord::lastUpdatedAt)
        .filter(java.util.Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(component.lastUpdatedAt());
    long bytes = subjectAssets.stream()
        .map(AssetRecord::size)
        .filter(java.util.Objects::nonNull)
        .mapToLong(Long::longValue)
        .sum();
    String displayName = displayName(component);
    String key = "component:" + component.id();
    return new Subject(
        component.id(),
        "COMPONENT",
        key,
        PersistenceHashes.sha256(key),
        familyKey(component),
        component.name(),
        displayName,
        component.version(),
        cleanupPath(format, component, subjectAssets),
        lastDownloadedAt,
        publishedAt,
        subjectAssets.size(),
        bytes,
        subjectAssets.stream().map(AssetRecord::id).toList(),
        contentToken(component, subjectAssets),
        usageRevision(subjectAssets, usage));
  }

  /** Generated metadata/index rows are repair outputs, not independently cleanable releases. */
  private static boolean isCleanupComponent(
      RepositoryRecord repository,
      ComponentRecord component,
      List<AssetRecord> assets) {
    if (assets == null || assets.isEmpty()) return false;
    if (repository.type() != RepositoryType.HOSTED) return true;
    return switch (repository.format()) {
      case MAVEN2 -> component.version() != null
          && !component.version().isBlank()
          && assets.stream().anyMatch(asset ->
              "artifact".equals(asset.kind()) || "pom".equals(asset.kind()));
      case NPM -> assets.stream().anyMatch(asset -> "tarball".equals(asset.kind()));
      case PYPI -> assets.stream().anyMatch(asset -> "package".equals(asset.kind()));
      case HELM -> assets.stream().anyMatch(asset ->
          "PACKAGE".equals(asset.kind()) || "PROVENANCE".equals(asset.kind()));
      case NUGET -> assets.stream().anyMatch(asset ->
          NUGET_PATHS.parse(asset.path()).kind() == NugetPath.Kind.FLAT_CONTAINER_PACKAGE);
      case RUBYGEMS -> assets.stream().anyMatch(asset -> isRubyGemPackage(asset.path()));
      case YUM -> assets.stream().anyMatch(asset -> isRpmPackage(asset.path()));
      default -> true;
    };
  }

  private static boolean isStandaloneCleanupAsset(
      RepositoryRecord repository, AssetRecord asset) {
    if (asset == null) return false;
    if (repository.type() != RepositoryType.HOSTED) return true;
    return switch (repository.format()) {
      case RAW -> true;
      case RUBYGEMS -> isRubyGemPackage(asset.path());
      case YUM -> isRpmPackage(asset.path());
      default -> false;
    };
  }

  private List<AssetRecord> expandRelatedAssets(
      RepositoryFormat format, List<AssetRecord> assets, boolean lockRelatedAssets) {
    if (format != RepositoryFormat.NUGET || assets == null || assets.isEmpty()) {
      return assets == null ? List.of() : assets;
    }
    LinkedHashMap<Long, AssetRecord> related = new LinkedHashMap<>();
    assets.forEach(asset -> related.put(asset.id(), asset));
    for (AssetRecord asset : List.copyOf(related.values())) {
      NugetPath path = NUGET_PATHS.parse(asset.path());
      if (path.packageId() == null || path.version() == null
          || (path.kind() != NugetPath.Kind.FLAT_CONTAINER_PACKAGE
              && path.kind() != NugetPath.Kind.FLAT_CONTAINER_NUSPEC)) {
        continue;
      }
      addRelatedAsset(
          related,
          asset.repositoryId(),
          NugetPaths.flatContainerPackage(path.packageId(), path.version()),
          lockRelatedAssets);
      addRelatedAsset(
          related,
          asset.repositoryId(),
          NugetPaths.flatContainerNuspec(path.packageId(), path.version()),
          lockRelatedAssets);
    }
    return List.copyOf(related.values());
  }

  private void addRelatedAsset(
      Map<Long, AssetRecord> related,
      long repositoryId,
      String path,
      boolean lockRelatedAssets) {
    assetDao.findAssetByPath(repositoryId, path).ifPresent(asset -> {
      AssetRecord selected = lockRelatedAssets
          ? assetDao.findAssetByIdForUpdate(asset.id()).orElse(asset)
          : asset;
      related.put(selected.id(), selected);
    });
  }

  private List<Subject> componentSubjects(
      RepositoryRecord repository, List<ComponentRecord> components) {
    if (components.isEmpty()) return List.of();
    Map<Long, List<AssetRecord>> assetsByComponent = new LinkedHashMap<>();
    components.forEach(component -> assetsByComponent.put(component.id(), new ArrayList<>()));
    assetDao.listAssetsByComponents(components.stream().map(ComponentRecord::id).toList())
        .forEach(asset -> {
          if (asset.componentId() != null) {
            assetsByComponent.computeIfAbsent(asset.componentId(), ignored -> new ArrayList<>())
                .add(asset);
          }
        });

    Map<String, AssetRecord> nugetRelated = repository.format() == RepositoryFormat.NUGET
        ? loadNugetRelatedAssets(repository.id(), assetsByComponent.values())
        : Map.of();
    Map<Long, List<AssetRecord>> expanded = new LinkedHashMap<>();
    for (ComponentRecord component : components) {
      List<AssetRecord> assets = assetsByComponent.getOrDefault(component.id(), List.of());
      expanded.put(component.id(), expandRelatedAssets(assets, nugetRelated));
    }
    Map<Long, CleanupUsage> usage = assetUsage(expanded.values().stream()
        .flatMap(List::stream)
        .map(AssetRecord::id)
        .distinct()
        .toList());
    List<Subject> subjects = new ArrayList<>(components.size());
    for (ComponentRecord component : components) {
      List<AssetRecord> assets = expanded.getOrDefault(component.id(), List.of());
      if (repository.format() == RepositoryFormat.HUGGINGFACE
          && huggingFaceRegistry != null
          && huggingFaceRegistry.isRevisionProtected(repository.id(), component.id())) {
        continue;
      }
      if (isCleanupComponent(repository, component, assets)) {
        subjects.add(componentSubject(repository.format(), component, assets, usage));
      }
    }
    return List.copyOf(subjects);
  }

  private Map<String, AssetRecord> loadNugetRelatedAssets(
      long repositoryId, java.util.Collection<List<AssetRecord>> componentAssets) {
    List<String> relatedPaths = new ArrayList<>();
    componentAssets.stream().flatMap(List::stream).forEach(asset -> {
      NugetPath path = NUGET_PATHS.parse(asset.path());
      if (path.packageId() == null || path.version() == null
          || (path.kind() != NugetPath.Kind.FLAT_CONTAINER_PACKAGE
              && path.kind() != NugetPath.Kind.FLAT_CONTAINER_NUSPEC)) {
        return;
      }
      relatedPaths.add(NugetPaths.flatContainerPackage(path.packageId(), path.version()));
      relatedPaths.add(NugetPaths.flatContainerNuspec(path.packageId(), path.version()));
    });
    return assetDao.findAssetsByPaths(repositoryId, relatedPaths);
  }

  private static List<AssetRecord> expandRelatedAssets(
      List<AssetRecord> assets, Map<String, AssetRecord> relatedByPath) {
    if (relatedByPath.isEmpty()) return List.copyOf(assets);
    LinkedHashMap<Long, AssetRecord> expanded = new LinkedHashMap<>();
    assets.forEach(asset -> expanded.put(asset.id(), asset));
    for (AssetRecord asset : assets) {
      NugetPath path = NUGET_PATHS.parse(asset.path());
      if (path.packageId() == null || path.version() == null
          || (path.kind() != NugetPath.Kind.FLAT_CONTAINER_PACKAGE
              && path.kind() != NugetPath.Kind.FLAT_CONTAINER_NUSPEC)) {
        continue;
      }
      addRelatedAsset(expanded, relatedByPath,
          NugetPaths.flatContainerPackage(path.packageId(), path.version()));
      addRelatedAsset(expanded, relatedByPath,
          NugetPaths.flatContainerNuspec(path.packageId(), path.version()));
    }
    return List.copyOf(expanded.values());
  }

  private static void addRelatedAsset(
      Map<Long, AssetRecord> expanded, Map<String, AssetRecord> relatedByPath, String path) {
    AssetRecord related = relatedByPath.get(path);
    if (related != null) expanded.put(related.id(), related);
  }

  private static boolean isRubyGemPackage(String path) {
    String normalized = value(path).toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("gems/") && normalized.endsWith(".gem");
  }

  private static boolean isRpmPackage(String path) {
    return value(path).toLowerCase(java.util.Locale.ROOT).endsWith(".rpm");
  }

  private Subject assetSubject(RepositoryFormat format, AssetWithBlob row) {
    AssetRecord asset = row.asset();
    Map<Long, CleanupUsage> usage = assetUsage(List.of(asset.id()));
    return assetSubject(format, row, usage);
  }

  private Subject assetSubject(
      RepositoryFormat format, AssetWithBlob row, Map<Long, CleanupUsage> usage) {
    AssetRecord asset = row.asset();
    String key = "asset:" + asset.id();
    long bytes = asset.size() == null
        ? row.blob() == null ? 0 : row.blob().size()
        : asset.size();
    return new Subject(
        asset.id(),
        "ASSET",
        key,
        PersistenceHashes.sha256(key),
        "asset:" + asset.path(),
        asset.name() == null ? asset.path() : asset.name(),
        asset.path(),
        null,
        asset.path(),
        effectiveLastDownloaded(asset, usage.get(asset.id())),
        asset.lastUpdatedAt(),
        1,
        Math.max(0, bytes),
        List.of(asset.id()),
        contentToken(asset),
        usageRevision(List.of(asset), usage));
  }

  private List<Subject> assetSubjects(
      RepositoryRecord repository, List<AssetWithBlob> rows) {
    List<AssetWithBlob> cleanable = rows.stream()
        .filter(row -> isStandaloneCleanupAsset(repository, row.asset()))
        .toList();
    Map<Long, CleanupUsage> usage = assetUsage(
        cleanable.stream().map(row -> row.asset().id()).toList());
    return cleanable.stream()
        .map(row -> assetSubject(repository.format(), row, usage))
        .toList();
  }

  private Map<String, Map<Long, Integer>> versionRanks(
      RepositoryFormat format,
      List<Subject> subjects,
      Integer retainCount) {
    if (retainCount == null) {
      return Map.of();
    }
    Comparator<String> comparator = capabilities.versionComparator(format).orElse(null);
    if (comparator == null) {
      return Map.of();
    }
    Map<String, List<Subject>> families = new LinkedHashMap<>();
    for (Subject subject : subjects) {
      if (subject.version() != null && !subject.version().isBlank()) {
        families.computeIfAbsent(subject.familyKey(), ignored -> new ArrayList<>()).add(subject);
      }
    }
    Map<String, Map<Long, Integer>> result = new HashMap<>();
    for (Map.Entry<String, List<Subject>> family : families.entrySet()) {
      List<Subject> versions = new ArrayList<>(family.getValue());
      try {
        versions.sort((left, right) -> {
          int byVersion = comparator.compare(right.version(), left.version());
          return byVersion != 0
              ? byVersion
              : Long.compare(right.identityId(), left.identityId());
        });
      } catch (RuntimeException ignored) {
        continue;
      }
      Map<Long, Integer> ranks = new HashMap<>();
      int rank = 0;
      String previousVersion = null;
      for (Subject subject : versions) {
        if (previousVersion == null || comparator.compare(previousVersion, subject.version()) != 0) {
          rank++;
          previousVersion = subject.version();
        }
        ranks.put(subject.identityId(), rank);
      }
      result.put(family.getKey(), Map.copyOf(ranks));
    }
    return Map.copyOf(result);
  }

  private static String familyKey(ComponentRecord component) {
    return familyPart(component.namespace())
        + familyPart(component.name())
        + familyPart(component.kind());
  }

  private static boolean sameFamily(ComponentRecord left, ComponentRecord right) {
    return value(left.namespace()).equals(value(right.namespace()))
        && value(left.name()).equals(value(right.name()))
        && value(left.kind()).equals(value(right.kind()));
  }

  private static CleanupScanCursor componentCursor(
      CleanupScanCursor current, ComponentRecord component) {
    return new CleanupScanCursor(
        current.policyId(),
        current.repositoryId(),
        "COMPONENT",
        component.namespace(),
        component.name(),
        component.kind(),
        0,
        current.revision(),
        current.wrappedCount());
  }

  private static CleanupScanCursor assetCursor(CleanupScanCursor current, long assetId) {
    return new CleanupScanCursor(
        current.policyId(),
        current.repositoryId(),
        "ASSET",
        null,
        null,
        null,
        Math.max(0, assetId),
        current.revision(),
        current.wrappedCount());
  }

  private static CleanupScanCursor dockerCursor(CleanupScanCursor current, long assetId) {
    return new CleanupScanCursor(
        current.policyId(),
        current.repositoryId(),
        "DOCKER",
        null,
        null,
        null,
        Math.max(0, assetId),
        current.revision(),
        current.wrappedCount());
  }

  private static CleanupScanCursor wrapCursor(
      CleanupScanCursor current, String phase) {
    return new CleanupScanCursor(
        current.policyId(),
        current.repositoryId(),
        phase,
        null,
        null,
        null,
        0,
        current.revision(),
        current.wrappedCount() + 1);
  }

  private static CleanupScanCursor initialCursor(
      long policyId,
      long repositoryId,
      RepositoryFormat format,
      long revision,
      long wrappedCount) {
    return new CleanupScanCursor(
        policyId,
        repositoryId,
        format == RepositoryFormat.DOCKER ? "DOCKER" : "COMPONENT",
        null,
        null,
        null,
        0,
        revision,
        wrappedCount);
  }

  private static CleanupScanCursor normalizeCursor(
      RepositoryRecord repository, CleanupScanCursor cursor) {
    if (cursor == null) {
      return initialCursor(0, repository.id(), repository.format(), 0, 0);
    }
    if (cursor.repositoryId() != repository.id()) {
      throw new IllegalArgumentException("cleanup cursor repository does not match target");
    }
    if (repository.format() == RepositoryFormat.DOCKER) {
      if (!"DOCKER".equals(cursor.phase())) {
        throw new IllegalArgumentException("Docker cleanup requires a Docker scan cursor");
      }
      return cursor;
    }
    if (!"COMPONENT".equals(cursor.phase()) && !"ASSET".equals(cursor.phase())) {
      throw new IllegalArgumentException("repository cleanup cursor phase is invalid");
    }
    return cursor;
  }

  private static String familyPart(String raw) {
    String normalized = value(raw);
    return normalized.length() + ":" + normalized;
  }

  private static String displayName(ComponentRecord component) {
    StringBuilder display = new StringBuilder();
    if (component.namespace() != null && !component.namespace().isBlank()) {
      display.append(component.namespace()).append(':');
    }
    display.append(component.name());
    if (component.version() != null && !component.version().isBlank()) {
      display.append(':').append(component.version());
    }
    return display.toString();
  }

  private static String mavenDeletePath(RepositoryFormat format, ComponentRecord component) {
    if (format != RepositoryFormat.MAVEN2
        || component.namespace() == null
        || component.namespace().isBlank()
        || component.version() == null
        || component.version().isBlank()) {
      return null;
    }
    return component.namespace().replace('.', '/') + "/" + component.name()
        + "/" + component.version();
  }

  private static String cleanupPath(
      RepositoryFormat format, ComponentRecord component, List<AssetRecord> assets) {
    Object browsePath = component.attributes() == null
        ? null
        : component.attributes().get("browsePath");
    if (browsePath != null && !browsePath.toString().isBlank()) {
      return browsePath.toString();
    }
    String mavenPath = mavenDeletePath(format, component);
    if (mavenPath != null) {
      return mavenPath;
    }
    if (format == RepositoryFormat.SWIFT) {
      return value(component.namespace()) + "/" + component.name() + "/" + component.version();
    }
    if (format == RepositoryFormat.ANSIBLEGALAXY && !assets.isEmpty()) {
      String assetPath = assets.getFirst().path();
      String filename = assetPath.substring(assetPath.lastIndexOf('/') + 1);
      return value(component.namespace()) + "/" + component.name() + "/"
          + component.version() + "/" + filename;
    }
    return assets.isEmpty() ? null : assets.getFirst().path();
  }

  private ScanResult scanDocker(
      RepositoryRecord repository,
      CleanupCriteria criteria,
      int scanLimit,
      Instant cutoff,
      String safetyStatus,
      CleanupScanCursor cursor) {
    if (dockerRegistryDao == null) {
      return new ScanResult(
          0, false, null, List.of(), safetyStatus, cursor,
          wrapCursor(cursor, "DOCKER"), null);
    }
    List<DockerRegistryDao.CleanupManifestCandidate> fetched =
        dockerRegistryDao.listManifestCleanupCandidatesPage(
            repository.id(), cursor.subjectId(), scanLimit + 1);
    boolean truncated = fetched.size() > scanLimit;
    List<DockerRegistryDao.CleanupManifestCandidate> selected =
        fetched.stream().limit(scanLimit).toList();
    List<Long> assetIds = selected.stream()
        .map(DockerRegistryDao.CleanupManifestCandidate::assetId)
        .toList();
    Map<Long, AssetRecord> assets = assetDao.findAssetsByIds(assetIds);
    Map<Long, CleanupUsage> usage = assetUsage(assetIds);
    var manifests = dockerRegistryDao.findManifestsByAssetIds(assetIds);
    var tags = dockerRegistryDao.listTagsForManifests(
        manifests.values().stream().map(manifest -> manifest.id()).toList());
    List<MatchedSubject> matchedSubjects = new ArrayList<>();
    for (DockerRegistryDao.CleanupManifestCandidate manifest : selected) {
      String displayName = manifest.imageName() + "@" + manifest.digest();
      if (!criteria.matchesPattern(manifest.imageName())
          && !criteria.matchesPattern(displayName)) {
        continue;
      }
      if (!criteria.matchesPublishedAt(manifest.updatedAt(), cutoff)
          || !criteria.matchesLastDownloadedAt(
              effectiveLastDownloaded(
                  assets.get(manifest.assetId()),
                  usage.get(manifest.assetId())),
              usageCutoff(cutoff))) {
        continue;
      }
      if (safetyStatus != null) continue;
      String key = "docker-manifest:" + displayName;
      AssetRecord asset = assets.get(manifest.assetId());
      var storedManifest = manifests.get(manifest.assetId());
      Subject subject = new Subject(
          manifest.assetId(),
          "DOCKER_MANIFEST",
          key,
          PersistenceHashes.sha256(key),
          manifest.imageName(),
          manifest.imageName(),
          displayName,
          manifest.digest(),
          displayName,
          effectiveLastDownloaded(asset, usage.get(manifest.assetId())),
          manifest.updatedAt(),
          1,
          Math.max(0, manifest.size()),
          List.of(manifest.assetId()),
          dockerContentToken(
              repository.id(),
              manifest.imageName(),
              manifest.digest(),
              asset,
              storedManifest,
              storedManifest == null
                  ? List.of()
                  : tags.getOrDefault(storedManifest.id(), List.of())),
          usageRevision(asset == null ? List.of() : List.of(asset), usage));
      matchedSubjects.add(new MatchedSubject(subject, criteria.matchReason(null)));
    }
    List<Candidate> candidates = attachProtections(repository.id(), matchedSubjects, cutoff);
    CleanupScanCursor nextCursor = truncated
        ? dockerCursor(cursor, fetched.get(scanLimit - 1).assetId())
        : wrapCursor(cursor, "DOCKER");
    return new ScanResult(
        Math.min(scanLimit, fetched.size()),
        truncated,
        null,
        List.copyOf(candidates),
        safetyStatus,
        cursor,
        nextCursor,
        null);
  }

  public java.util.Optional<Subject> resolve(
      RepositoryRecord repository, String subjectKind, long identityId, String deletePath) {
    if ("COMPONENT".equals(subjectKind)) {
      return componentDao.findById(identityId)
          .filter(component -> component.repositoryId() == repository.id())
          .flatMap(component -> {
            List<AssetRecord> assets = assetDao.listAssetsByComponent(component.id());
            if (!isCleanupComponent(repository, component, assets)) {
              return java.util.Optional.empty();
            }
            return java.util.Optional.of(componentSubject(
                repository.format(), component, assets, false));
          });
    }
    if ("ASSET".equals(subjectKind)) {
      return assetDao.findAssetWithBlobById(identityId)
          .filter(row -> row.asset().repositoryId() == repository.id()
              && row.asset().componentId() == null
              && isStandaloneCleanupAsset(repository, row.asset()))
          .map(row -> assetSubject(repository.format(), row));
    }
    if ("DOCKER_MANIFEST".equals(subjectKind)
        && repository.format() == RepositoryFormat.DOCKER
        && deletePath != null) {
      int separator = deletePath.lastIndexOf('@');
      if (separator <= 0 || separator == deletePath.length() - 1) {
        return java.util.Optional.empty();
      }
      String imageName = deletePath.substring(0, separator);
      String digest = deletePath.substring(separator + 1);
      return dockerRegistryDao.findManifestByDigest(repository.id(), imageName, digest)
          .filter(manifest -> manifest.assetId() == identityId && manifest.deletedAt() == null)
          .flatMap(manifest -> assetDao.findAssetById(identityId).map(asset -> {
            Map<Long, CleanupUsage> usage = assetUsage(List.of(identityId));
            String key = "docker-manifest:" + imageName + "@" + digest;
            return new Subject(
                identityId,
                "DOCKER_MANIFEST",
                key,
                PersistenceHashes.sha256(key),
                imageName,
                imageName,
                imageName + "@" + digest,
                digest,
                deletePath,
                effectiveLastDownloaded(asset, usage.get(identityId)),
                manifest.updatedAt(),
                1,
                Math.max(0, manifest.size()),
                List.of(identityId),
                dockerContentToken(repository.id(), imageName, digest, asset),
                usageRevision(List.of(asset), usage));
          }));
    }
    return java.util.Optional.empty();
  }

  public java.util.Optional<Subject> resolveLocked(
      RepositoryRecord repository, String subjectKind, long identityId, String deletePath) {
    if ("COMPONENT".equals(subjectKind)) {
      return componentDao.findByIdForUpdate(identityId)
          .filter(component -> component.repositoryId() == repository.id())
          .flatMap(component -> {
            List<AssetRecord> assets = assetDao.listAssetsByComponentForUpdate(identityId);
            if (!isCleanupComponent(repository, component, assets)) {
              return java.util.Optional.empty();
            }
            return java.util.Optional.of(componentSubject(
                repository.format(), component, assets, true));
          });
    }
    if ("ASSET".equals(subjectKind)) {
      return assetDao.findAssetByIdForUpdate(identityId)
          .filter(asset -> asset.repositoryId() == repository.id()
              && asset.componentId() == null
              && isStandaloneCleanupAsset(repository, asset))
          .map(asset -> assetSubject(
              repository.format(),
              new AssetWithBlob(
                  asset,
                  asset.assetBlobId() == null
                      ? null
                      : assetDao.findBlobById(asset.assetBlobId()).orElse(null))));
    }
    if ("DOCKER_MANIFEST".equals(subjectKind)
        && repository.format() == RepositoryFormat.DOCKER
        && deletePath != null) {
      int separator = deletePath.lastIndexOf('@');
      if (separator <= 0 || separator == deletePath.length() - 1) {
        return java.util.Optional.empty();
      }
      String imageName = deletePath.substring(0, separator);
      String digest = deletePath.substring(separator + 1);
      return dockerRegistryDao.findManifestByDigestForUpdate(repository.id(), imageName, digest)
          .filter(manifest -> manifest.assetId() == identityId && manifest.deletedAt() == null)
          .flatMap(manifest -> assetDao.findAssetByIdForUpdate(identityId).map(asset -> {
            var tags = dockerRegistryDao.listTagsForManifestForUpdate(manifest.id());
            Map<Long, CleanupUsage> usage = assetUsage(List.of(identityId));
            String key = "docker-manifest:" + imageName + "@" + digest;
            return new Subject(
                identityId,
                "DOCKER_MANIFEST",
                key,
                PersistenceHashes.sha256(key),
                imageName,
                imageName,
                imageName + "@" + digest,
                digest,
                deletePath,
                effectiveLastDownloaded(asset, usage.get(identityId)),
                manifest.updatedAt(),
                1,
                Math.max(0, manifest.size()),
                List.of(identityId),
                dockerContentToken(repository.id(), imageName, digest, asset, manifest, tags),
                usageRevision(List.of(asset), usage));
          }));
    }
    return java.util.Optional.empty();
  }

  private Map<Long, CleanupUsage> assetUsage(List<Long> assetIds) {
    return cleanupDao == null ? Map.of() : cleanupDao.findAssetUsage(assetIds);
  }

  private static Instant effectiveLastDownloaded(AssetRecord asset, CleanupUsage usage) {
    Instant fromAsset = asset == null ? null : asset.lastDownloadedAt();
    Instant fromUsage = usage == null ? null : usage.lastDownloadedAt();
    if (fromAsset == null) return fromUsage;
    if (fromUsage == null) return fromAsset;
    return fromAsset.isAfter(fromUsage) ? fromAsset : fromUsage;
  }

  private static long usageRevision(
      List<AssetRecord> assets, Map<Long, CleanupUsage> usage) {
    long revision = 0;
    for (AssetRecord asset : assets) {
      CleanupUsage row = usage.get(asset.id());
      if (row == null) continue;
      if (Long.MAX_VALUE - revision < row.usageRevision()) return Long.MAX_VALUE;
      revision += row.usageRevision();
    }
    return revision;
  }

  private static String contentToken(ComponentRecord component, List<AssetRecord> assets) {
    StringBuilder value = new StringBuilder("component|")
        .append(component.id()).append('|')
        .append(component.repositoryId()).append('|')
        .append(component.namespace()).append('|')
        .append(component.name()).append('|')
        .append(component.version()).append('|')
        .append(component.kind()).append('|')
        .append(epoch(component.lastUpdatedAt()));
    assets.stream().sorted(Comparator.comparing(AssetRecord::path)).forEach(asset -> value
        .append("|asset|").append(asset.id())
        .append('|').append(asset.assetBlobId())
        .append('|').append(asset.path())
        .append('|').append(asset.size())
        .append('|').append(epoch(asset.lastUpdatedAt())));
    return token(value.toString());
  }

  private static String contentToken(AssetRecord asset) {
    return token("asset|" + asset.id() + '|' + asset.repositoryId() + '|'
        + asset.componentId() + '|' + asset.assetBlobId() + '|' + asset.format() + '|'
        + asset.path() + '|' + asset.name() + '|' + asset.kind() + '|'
        + asset.contentType() + '|' + asset.size() + '|' + epoch(asset.lastUpdatedAt()));
  }

  private String dockerContentToken(
      long repositoryId, String imageName, String digest, AssetRecord asset) {
    var manifest = dockerRegistryDao.findManifestByDigest(repositoryId, imageName, digest).orElse(null);
    var tags = manifest == null ? List.<com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord>of()
        : dockerRegistryDao.listTagsForManifest(manifest.id());
    return dockerContentToken(repositoryId, imageName, digest, asset, manifest, tags);
  }

  private static String dockerContentToken(
      long repositoryId,
      String imageName,
      String digest,
      AssetRecord asset,
      com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord manifest,
      List<com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord> tags) {
    StringBuilder value = new StringBuilder("docker|")
        .append(repositoryId).append('|').append(imageName).append('|').append(digest);
    if (manifest != null) {
      value.append('|').append(manifest.id()).append('|').append(epoch(manifest.updatedAt()));
      tags.stream()
          .sorted(Comparator.comparing(com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord::tag))
          .forEach(tag -> value.append("|tag|").append(tag.tag()).append('|')
              .append(tag.manifestDigest()).append('|').append(epoch(tag.updatedAt())));
    }
    if (asset != null) value.append('|').append(contentToken(asset));
    return token(value.toString());
  }

  private static String token(String value) {
    return HexFormat.of().formatHex(PersistenceHashes.sha256(value));
  }

  private static long epoch(Instant value) {
    return value == null ? 0 : value.toEpochMilli();
  }

  private Instant usageCutoff(Instant cutoff) {
    return runtimeProperties == null
        ? cutoff
        : cutoff.minus(runtimeProperties.getUsage().getSafetyLag());
  }

  private String usageSafetyStatus(
      long repositoryId, CleanupCriteria criteria, Instant cutoff) {
    if (criteria.lastDownloadedOlderThanDays() == null || usageTracking == null) return null;
    Instant startedAt = usageTracking.trackingStartedAt(repositoryId);
    if (startedAt == null) return "USAGE_TRACKING_NOT_ACTIVE";
    Duration observation = Duration.ofDays(criteria.lastDownloadedOlderThanDays());
    if (runtimeProperties != null) {
      observation = observation.plus(runtimeProperties.getUsage().getSafetyLag());
    }
    return startedAt.isAfter(cutoff.minus(observation))
        ? "USAGE_TRACKING_WARMING_UP"
        : null;
  }

  private List<Candidate> attachProtections(
      long repositoryId, List<MatchedSubject> matchedSubjects, Instant activeAt) {
    if (matchedSubjects.isEmpty()) return List.of();
    Map<String, CleanupProtection> protections = cleanupDao == null
        ? Map.of()
        : cleanupDao.findActiveProtections(
            repositoryId,
            matchedSubjects.stream()
                .map(matched -> new CleanupProtectionLookup(
                    matched.subject().key(),
                    matched.subject().kind(),
                    matched.subject().key(),
                    matched.subject().keyHash()))
                .toList(),
            activeAt);
    if (protections == null) protections = Map.of();
    List<Candidate> candidates = new ArrayList<>(matchedSubjects.size());
    for (MatchedSubject matched : matchedSubjects) {
      CleanupProtection protection = protections.get(matched.subject().key());
      Map<String, Object> reason = new LinkedHashMap<>(matched.reason());
      if (protection != null) {
        reason.put("protectionId", protection.id());
        reason.put("protectionReason", protection.reason());
      }
      candidates.add(new Candidate(
          matched.subject(),
          Map.copyOf(reason),
          protection == null ? null : protection.id()));
    }
    return List.copyOf(candidates);
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  public record ScanResult(
      int scannedSubjects,
      boolean truncated,
      String incompleteFamily,
      List<Candidate> candidates,
      String safetyStatus,
      CleanupScanCursor startCursor,
      CleanupScanCursor nextCursor,
      String cursorWarning) {
    public ScanResult(
        int scannedSubjects,
        boolean truncated,
        String incompleteFamily,
        List<Candidate> candidates) {
      this(scannedSubjects, truncated, incompleteFamily, candidates, null, null, null, null);
    }

    public ScanResult(
        int scannedSubjects,
        boolean truncated,
        String incompleteFamily,
        List<Candidate> candidates,
        String safetyStatus) {
      this(
          scannedSubjects,
          truncated,
          incompleteFamily,
          candidates,
          safetyStatus,
          null,
          null,
          null);
    }
  }

  private record SubjectPage(
      List<Subject> subjects,
      boolean truncated,
      String incompleteFamily,
      CleanupScanCursor nextCursor,
      String cursorWarning) {
  }

  private record MatchedSubject(Subject subject, Map<String, Object> reason) {
  }

  public record Candidate(Subject subject, Map<String, Object> reason, Long protectionId) {
    public Candidate(Subject subject, Map<String, Object> reason) {
      this(subject, reason, null);
    }
  }

  public record Subject(
      long identityId,
      String kind,
      String key,
      byte[] keyHash,
      String familyKey,
      String simpleName,
      String displayName,
      String version,
      String deletePath,
      Instant lastDownloadedAt,
      Instant publishedAt,
      int assetCount,
      long estimatedBytes,
      List<Long> assetIds,
      String contentToken,
      long usageRevision) {
    public Subject(
        long identityId,
        String kind,
        String key,
        byte[] keyHash,
        String familyKey,
        String simpleName,
        String displayName,
        String version,
        String deletePath,
        Instant lastDownloadedAt,
        Instant publishedAt,
        int assetCount,
        long estimatedBytes) {
      this(
          identityId,
          kind,
          key,
          keyHash,
          familyKey,
          simpleName,
          displayName,
          version,
          deletePath,
          lastDownloadedAt,
          publishedAt,
          assetCount,
          estimatedBytes,
          List.of(identityId),
          null,
          0);
    }
  }
}
