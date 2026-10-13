package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao.DeletionNode;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, stateless previews; database locks and exact identities fence execution across replicas. */
@Service
public class BrowseDirectoryDeletionService {
  static final int MAX_ASSETS = 500;
  static final int MAX_NODES = 1000;
  private static final Set<RepositoryFormat> SUPPORTED = Set.of(
      RepositoryFormat.MAVEN2, RepositoryFormat.RAW, RepositoryFormat.NPM,
      RepositoryFormat.PYPI, RepositoryFormat.NUGET,
      RepositoryFormat.YUM, RepositoryFormat.RUBYGEMS);
  private final RepositoryDao repositories;
  private final BrowseNodeDao nodes;
  private final AssetDao assets;
  private final CleanupSubjectScanner subjects;
  private final RepositoryContentDeletionService deletion;

  public BrowseDirectoryDeletionService(
      RepositoryDao repositories, BrowseNodeDao nodes, AssetDao assets,
      CleanupSubjectScanner subjects, RepositoryContentDeletionService deletion) {
    this.repositories = repositories;
    this.nodes = nodes;
    this.assets = assets;
    this.subjects = subjects;
    this.deletion = deletion;
  }

  @Transactional(readOnly = true)
  public Preview preview(String repository, String path) {
    return plan(repository, requirePath(path)).preview();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
  public Result delete(String repository, Command command, String actorId) {
    if (command == null) throw badRequest("A confirmed directory preview is required");
    String path = requirePath(command.path());
    if (!path.equals(command.confirmationPath())) {
      throw badRequest("Enter the complete directory path exactly as shown in the preview");
    }
    Plan expected = plan(repository, path);
    requireToken(expected, command.selectionToken());
    // Component locks precede asset locks, matching publication/cleanup ordering. Whole-component
    // adapters may only operate when ALL of their assets were included in the preview.
    for (Subject subject : expected.subjects()) {
      Subject current = subjects.resolveLocked(
          expected.repository(), subject.kind(), subject.identityId(), subject.deletePath())
          .orElseThrow(BrowseDirectoryDeletionService::stale);
      if (!Objects.equals(subject.contentToken(), current.contentToken())) throw stale();
    }
    for (AssetRecord asset : expected.lockAssets()) {
      AssetRecord current = assets.findAssetByIdForUpdate(asset.id())
          .orElseThrow(BrowseDirectoryDeletionService::stale);
      if (!assetFingerprint(asset).equals(assetFingerprint(current))) throw stale();
    }
    // READ_COMMITTED avoids a stale repeatable-read snapshot following a lock wait. New members
    // added after this final check are retained: execution never issues a prefix DELETE.
    Plan current = plan(repository, path);
    requireToken(current, command.selectionToken());
    Map<String, List<CleanupDeleteSubject>> families = new LinkedHashMap<>();
    for (Subject subject : current.subjects()) {
      families.computeIfAbsent(subject.kind() + ":" + subject.familyKey(), ignored -> new ArrayList<>())
          .add(new CleanupDeleteSubject(subject.kind(), subject.identityId(), subject.deletePath()));
    }
    if (!current.directAssets().isEmpty()) {
      families.put("direct-assets", current.directAssets().stream()
          .map(asset -> new CleanupDeleteSubject("ASSET", asset.id(), asset.path())).toList());
    }
    int deleted = 0;
    for (List<CleanupDeleteSubject> commands : families.values()) {
      List<Integer> counts = deletion.deleteBatchForCleanup(repository, commands, actorId);
      if (counts.size() != commands.size()) {
        throw new IllegalStateException("Directory deletion returned an invalid result count");
      }
      deleted += counts.stream().mapToInt(Integer::intValue).sum();
    }
    return new Result(repository, path, deleted);
  }

  private Plan plan(String repositoryName, String path) {
    RepositoryRecord repository = repositories.findByName(repositoryName)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
    if (repository.type() != RepositoryType.HOSTED || !SUPPORTED.contains(repository.format())) {
      throw badRequest("Directory deletion is unavailable for this repository type or format");
    }
    if (!repository.online()) throw badRequest("Bring the repository online before deleting a directory");
    String treePath = repository.format() == RepositoryFormat.PYPI
        && !path.equals("simple") && !path.startsWith("simple/") ? "packages/" + path : path;
    var root = nodes.findNode(repository.id(), treePath)
        .filter(node -> node.hasChildren() && node.hasAssetSubtree())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found"));
    List<DeletionNode> selected = new ArrayList<>();
    if (root.assetId() != null) selected.add(new DeletionNode(root.id(), root.path(), root.assetId()));
    List<Long> parents = List.of(root.id());
    Set<Long> visited = new HashSet<>(parents);
    int nodeCount = 1;
    while (!parents.isEmpty()) {
      List<DeletionNode> children = nodes.listDeletionChildren(
          repository.id(), parents, MAX_NODES - nodeCount + 1);
      nodeCount += children.size();
      if (nodeCount > MAX_NODES) throw tooLarge();
      List<Long> next = new ArrayList<>();
      for (DeletionNode child : children) {
        if (!child.path().startsWith(treePath + "/") || !visited.add(child.id())) throw stale();
        if (child.assetId() != null) selected.add(child);
        next.add(child.id());
      }
      if (selected.size() > MAX_ASSETS) throw tooLarge();
      parents = next;
    }
    Map<Long, String> paths = new LinkedHashMap<>();
    selected.forEach(node -> paths.put(node.assetId(), node.path()));
    Map<Long, AssetRecord> loaded = assets.findAssetsByIds(paths.keySet());
    if (loaded.size() != paths.size() || loaded.values().stream()
        .anyMatch(asset -> asset.repositoryId() != repository.id())) throw stale();
    List<AssetRecord> rows = loaded.values().stream().sorted(Comparator.comparing(AssetRecord::id)).toList();
    List<AssetRecord> direct = new ArrayList<>();
    List<Subject> packages = new ArrayList<>();
    Set<Long> handledComponents = new HashSet<>();
    Set<Long> deletingIds = new HashSet<>();
    boolean directFormat = repository.format() == RepositoryFormat.RAW
        || repository.format() == RepositoryFormat.MAVEN2 || repository.format() == RepositoryFormat.YUM;
    for (AssetRecord row : rows) {
      if (directFormat) {
        direct.add(row);
        deletingIds.add(row.id());
        continue;
      }
      String kind = row.componentId() == null ? "ASSET" : "COMPONENT";
      long id = row.componentId() == null ? row.id() : row.componentId();
      if (row.componentId() != null && !handledComponents.add(id)) continue;
      if (row.componentId() != null && assets.hasComponentAssetsOutside(id, paths.keySet())) {
        throw badRequest("This directory contains only part of a package; select its parent directory");
      }
      subjects.resolve(repository, kind, id, row.path()).ifPresent(subject -> {
        if (!paths.keySet().containsAll(subject.assetIds())) {
          throw badRequest("This directory contains only part of a package; select its parent directory");
        }
        packages.add(subject);
        deletingIds.addAll(subject.assetIds());
      });
    }
    for (AssetRecord row : rows) {
      if (!deletingIds.contains(row.id()) && !isGeneratedMetadata(row)) {
        throw badRequest("This directory contains an unsupported package identity; delete it individually");
      }
    }
    if (deletingIds.isEmpty()) throw badRequest("This directory contains only generated metadata; select a package directory");
    packages.sort(Comparator.comparing(Subject::kind).thenComparingLong(Subject::identityId));
    List<Item> items = rows.stream().map(row -> new Item(
        publicPath(repository.format(), paths.get(row.id())), row.size() == null ? 0 : row.size(),
        deletingIds.contains(row.id()) ? "DELETE" : "PROTOCOL_METADATA")).toList();
    long componentCount = rows.stream().filter(row -> deletingIds.contains(row.id()))
        .map(AssetRecord::componentId).filter(Objects::nonNull).distinct().count();
    long bytes = items.stream().filter(item -> item.action().equals("DELETE")).mapToLong(Item::size).sum();
    StringBuilder fingerprint = new StringBuilder();
    append(fingerprint, repository.id(), repository.name(), repository.format(), repository.type(),
        repository.online(), repository.writePolicy(), path);
    for (AssetRecord row : rows) append(fingerprint, paths.get(row.id()), assetFingerprint(row));
    for (Subject subject : packages) append(fingerprint, subject.key(), subject.contentToken());
    String token = HexFormat.of().formatHex(PersistenceHashes.sha256(fingerprint.toString()));
    Set<Long> subjectIds = new HashSet<>();
    packages.forEach(subject -> subjectIds.addAll(subject.assetIds()));
    List<AssetRecord> lockAssets = rows.stream().filter(row -> !subjectIds.contains(row.id())).toList();
    return new Plan(repository, List.copyOf(direct), List.copyOf(packages), lockAssets,
        new Preview(repositoryName, path, componentCount, deletingIds.size(), bytes,
            rows.size() - deletingIds.size(), MAX_ASSETS, token, items));
  }

  private static boolean isGeneratedMetadata(AssetRecord row) {
    return switch (row.format()) {
      case NPM -> "package-root".equals(row.kind());
      case PYPI -> row.path().startsWith("simple/");
      case NUGET -> row.path().endsWith("/index.json") || row.path().startsWith("registration/");
      case RUBYGEMS -> !row.path().endsWith(".gem");
      default -> false;
    };
  }

  private static String publicPath(RepositoryFormat format, String path) {
    return format == RepositoryFormat.PYPI && path.startsWith("packages/")
        ? path.substring("packages/".length()) : path;
  }

  private static String requirePath(String path) {
    if (path == null || path.isBlank() || path.length() > 2048 || !path.equals(path.trim())
        || path.startsWith("/") || path.endsWith("/") || path.contains("\\")
        || path.codePoints().anyMatch(Character::isISOControl)) {
      throw badRequest("Select a non-root directory using its exact Browse path");
    }
    for (String part : path.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) throw badRequest("Invalid directory path");
    }
    return path;
  }

  private static String assetFingerprint(AssetRecord asset) {
    StringBuilder value = new StringBuilder();
    append(value, asset.id(), asset.repositoryId(), asset.componentId(), asset.assetBlobId(),
        asset.path(), asset.kind(), asset.contentType(), asset.size(), asset.lastUpdatedAt());
    return value.toString();
  }

  private static void append(StringBuilder target, Object... values) {
    for (Object value : values) {
      String text = String.valueOf(value);
      target.append(text.length()).append(':').append(text);
    }
  }

  private static void requireToken(Plan plan, String token) {
    if (token == null || !token.equals(plan.preview().selectionToken())) throw stale();
  }

  private static ResponseStatusException stale() {
    return new ResponseStatusException(HttpStatus.CONFLICT,
        "Directory contents changed. Review a new preview before deleting");
  }

  private static ResponseStatusException tooLarge() {
    return badRequest("Directory is too large for one deletion (500 files or 1,000 tree nodes). Select a smaller directory");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record Plan(RepositoryRecord repository, List<AssetRecord> directAssets,
                      List<Subject> subjects, List<AssetRecord> lockAssets, Preview preview) {}
  public record Command(String path, String confirmationPath, String selectionToken) {}
  public record Item(String path, long size, String action) {}
  public record Preview(String repository, String path, long componentCount, int assetCount,
                        long estimatedBytes, int metadataAssetCount, int maxAssets,
                        String selectionToken, List<Item> items) {}
  public record Result(String repository, String path, int deletedAssets) {}
}
