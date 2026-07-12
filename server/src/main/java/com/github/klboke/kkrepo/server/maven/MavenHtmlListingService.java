package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.browse.BrowseAssetVisibility;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.util.HtmlUtils;
import org.springframework.stereotype.Service;

/**
 * Renders Nexus-style HTML directory index for {@code /repository/{name}/[path/]} GETs.
 * Backed by browse_node so GROUP repositories see the merged member view.
 */
@Service
public class MavenHtmlListingService {
  private static final DateTimeFormatter DATE_FMT =
      DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US)
          .withZone(ZoneId.of("UTC"));

  private final RepositoryDao repositoryDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetDao assetDao;
  private final ComponentDao componentDao;

  public MavenHtmlListingService(
      RepositoryDao repositoryDao,
      BrowseNodeDao browseNodeDao,
      AssetDao assetDao,
      ComponentDao componentDao) {
    this.repositoryDao = repositoryDao;
    this.browseNodeDao = browseNodeDao;
    this.assetDao = assetDao;
    this.componentDao = componentDao;
  }

  public Optional<String> render(String repoName, String path) {
    return render(repoName, path, false);
  }

  public Optional<String> renderBrowse(String repoName, String path) {
    return render(repoName, path, true);
  }

  private Optional<String> render(String repoName, String path, boolean browseMode) {
    Optional<RepositoryRecord> opt = repositoryDao.findByName(repoName);
    if (opt.isEmpty()) {
      return Optional.empty();
    }
    RepositoryRecord repo = opt.get();
    String normalized = normalize(path);
    ListingPath listingPath = listingPath(repo.format(), normalized, browseMode);
    if (BrowseAssetVisibility.hidden(repo.format(), listingPath.storagePath())) {
      return Optional.empty();
    }
    if (repo.format() == RepositoryFormat.NPM && isNpmInternalDashPath(listingPath.storagePath())) {
      return Optional.empty();
    }
    List<RepositoryRecord> sources = repo.type() == RepositoryType.GROUP
        ? repositoryDao.listMembers(repo.id())
        : List.of(repo);
    if (repo.format() == RepositoryFormat.HELM) {
      return renderHelm(repo, normalized, sources);
    }
    LinkedHashMap<String, BrowseNodeDao.BrowseChild> merged = new LinkedHashMap<>();
    for (RepositoryRecord src : sources) {
      for (BrowseNodeDao.BrowseChild child : browseNodeDao.listChildren(src.id(), listingPath.storagePath())) {
        if (!child.hasAssetSubtree() || BrowseAssetVisibility.hidden(repo.format(), child.path())) {
          continue;
        }
        merged.putIfAbsent(child.path(), child);
      }
    }
    if (!listingPath.storagePath().isEmpty() && merged.isEmpty()) {
      return Optional.empty();
    }
    List<ListingEntry> entries = toListingEntries(repo, listingPath, sources, merged);
    entries.sort((a, b) -> {
      if (a.leaf() != b.leaf()) return a.leaf() ? 1 : -1;
      return a.displayName().compareTo(b.displayName());
    });
    return Optional.of(renderHtml(repo.name(), listingPath.displayPath(), entries));
  }

  private Optional<String> renderHelm(
      RepositoryRecord repo,
      String path,
      List<RepositoryRecord> sources) {
    List<String> segments = pathSegments(path);
    if (segments.size() > 2) {
      return Optional.empty();
    }

    LinkedHashMap<String, ListingEntry> entries = new LinkedHashMap<>();
    for (RepositoryRecord source : sources) {
      if (segments.isEmpty()) {
        assetDao.findAssetByPath(source.id(), "index.yaml")
            .ifPresent(asset -> entries.putIfAbsent("file:" + asset.path(), ListingEntry.fromAsset(repo.name(), asset)));
        for (String chartName : componentDao.listDistinctNamesByRepositoryId(source.id())) {
          if (chartName != null && !chartName.isBlank()) {
            entries.putIfAbsent("dir:" + chartName, ListingEntry.directory(chartName));
          }
        }
      } else if (segments.size() == 1) {
        for (ComponentRecord component : componentDao.listByName(source.id(), segments.get(0))) {
          if (component.version() != null && !component.version().isBlank()) {
            entries.putIfAbsent("dir:" + component.version(), ListingEntry.directory(component.version()));
          }
        }
      } else {
        for (ComponentRecord component : componentDao.listByName(source.id(), segments.get(0))) {
          if (!segments.get(1).equals(component.version())) continue;
          for (AssetRecord asset : assetDao.listAssetsByComponent(component.id())) {
            addHelmFileEntry(repo.name(), entries, asset, segments.get(0), segments.get(1));
          }
        }
      }
    }

    if (!path.isEmpty() && entries.isEmpty()) {
      return Optional.empty();
    }
    List<ListingEntry> sorted = new ArrayList<>(entries.values());
    sorted.sort((a, b) -> {
      if (a.leaf() != b.leaf()) return a.leaf() ? 1 : -1;
      return a.displayName().compareTo(b.displayName());
    });
    return Optional.of(renderHtml(repo.name(), path, sorted));
  }

  private void addHelmFileEntry(
      String repoName,
      LinkedHashMap<String, ListingEntry> entries,
      AssetRecord asset,
      String chart,
      String version) {
    String assetChart = stringAttr(asset, "name");
    String assetVersion = stringAttr(asset, "version");
    if (assetChart == null || assetVersion == null) return;
    if (!assetChart.equals(chart) || !assetVersion.equals(version)) return;
    entries.putIfAbsent("file:" + asset.path(), ListingEntry.fromAsset(repoName, asset));
  }

  private List<ListingEntry> toListingEntries(
      RepositoryRecord repo,
      ListingPath path,
      List<RepositoryRecord> sources,
      LinkedHashMap<String, BrowseNodeDao.BrowseChild> merged) {
    List<ListingEntry> entries = new ArrayList<>();
    BrowseNodeDao.BrowseChild npmDash = null;
    if (repo.format() == RepositoryFormat.NPM && !path.storagePath().isEmpty()) {
      for (BrowseNodeDao.BrowseChild child : merged.values()) {
        if (!child.leaf() && "-".equals(child.displayName())) {
          npmDash = child;
          break;
        }
      }
    }

    for (BrowseNodeDao.BrowseChild child : merged.values()) {
      if (child == npmDash) {
        entries.addAll(flattenNpmTarballs(repo.name(), sources, child.path()));
      } else if (repo.format() == RepositoryFormat.PYPI && path.stripPypiPackages()) {
        entries.add(ListingEntry.fromPypiChild(repo.name(), child));
      } else {
        entries.add(ListingEntry.fromChild(repo.name(), child));
      }
    }
    return entries;
  }

  private List<ListingEntry> flattenNpmTarballs(
      String repoName,
      List<RepositoryRecord> sources,
      String dashPath) {
    LinkedHashMap<String, BrowseNodeDao.BrowseChild> mergedTarballs = new LinkedHashMap<>();
    for (RepositoryRecord src : sources) {
      for (BrowseNodeDao.BrowseChild child : browseNodeDao.listChildren(src.id(), dashPath)) {
        if (child.leaf()) {
          mergedTarballs.putIfAbsent(child.path(), child);
        }
      }
    }
    if (mergedTarballs.isEmpty()) {
      return List.of();
    }
    List<ListingEntry> result = new ArrayList<>();
    for (BrowseNodeDao.BrowseChild child : mergedTarballs.values()) {
      result.add(ListingEntry.fromNpmTarball(repoName, child));
    }
    return result;
  }

  private String renderHtml(String repoName, String path, List<ListingEntry> entries) {
    String displayPath = path.isEmpty() ? "/" : "/" + path;
    StringBuilder sb = new StringBuilder(4096);
    sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
    sb.append("  <title>Index of ").append(escape(displayPath)).append("</title>\n");
    sb.append("  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>\n");
    sb.append("</head>\n<body class=\"htmlIndex\">\n");
    sb.append("<h1>Index of ").append(escape(displayPath)).append("</h1>\n\n");
    sb.append("<table cellspacing=\"10\">\n");
    sb.append("    <tr>\n");
    sb.append("        <th align=\"left\">Name</th>\n");
    sb.append("        <th>Last Modified</th>\n");
    sb.append("        <th>Size</th>\n");
    sb.append("        <th>Description</th>\n");
    sb.append("    </tr>\n");
    if (!path.isEmpty()) {
      sb.append("    <tr><td><a href=\"../\">Parent Directory</a></td><td></td><td></td><td></td></tr>\n");
    }
    for (ListingEntry entry : entries) {
      String size = entry.leaf() && entry.assetSize() != null ? String.valueOf(entry.assetSize()) : "";
      String when = entry.leaf() && entry.assetLastUpdatedAt() != null
          ? DATE_FMT.format(entry.assetLastUpdatedAt())
          : "";
      sb.append("    <tr>\n");
      sb.append("        <td><a href=\"")
          .append(escape(entry.href())).append("\">").append(escape(entry.displayName()))
          .append("</a></td>\n");
      sb.append("        <td>").append(escape(when)).append("</td>\n");
      sb.append("        <td align=\"right\">").append(escape(size)).append("</td>\n");
      sb.append("        <td></td>\n");
      sb.append("    </tr>\n");
    }
    sb.append("</table>\n");
    sb.append("</body>\n</html>\n");
    return sb.toString();
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String s = path.trim();
    while (s.startsWith("/")) s = s.substring(1);
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
  }

  private static boolean isNpmInternalDashPath(String path) {
    return "-".equals(path) || path.endsWith("/-");
  }

  private static ListingPath listingPath(
      RepositoryFormat format,
      String normalized,
      boolean browseMode) {
    if (format != RepositoryFormat.PYPI || !browseMode) {
      return new ListingPath(normalized, normalized, false);
    }
    if (normalized.isEmpty()) {
      return new ListingPath("packages", "", true);
    }
    if (normalized.equals("simple") || normalized.startsWith("simple/")) {
      return new ListingPath(normalized, normalized, false);
    }
    return new ListingPath("packages/" + normalized, normalized, true);
  }

  private static String stringAttr(AssetRecord asset, String key) {
    Object value = asset.attributes() == null ? null : asset.attributes().get(key);
    String text = value == null ? null : value.toString();
    return text == null || text.isBlank() ? null : text;
  }

  private static List<String> pathSegments(String path) {
    if (path == null || path.isBlank()) return List.of();
    List<String> parts = new ArrayList<>();
    for (String part : path.split("/")) {
      if (!part.isBlank()) parts.add(part);
    }
    return parts;
  }

  private static String leafName(String path) {
    if (path == null || path.isBlank()) return "";
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String escape(String s) {
    if (s == null) return "";
    return HtmlUtils.htmlEscape(s, "UTF-8");
  }

  private record ListingPath(String storagePath, String displayPath, boolean stripPypiPackages) {}

  private record ListingEntry(
      String displayName,
      String href,
      boolean leaf,
      Long assetSize,
      Instant assetLastUpdatedAt) {
    static ListingEntry fromChild(String repoName, BrowseNodeDao.BrowseChild child) {
      boolean leaf = child.leaf();
      String href = leaf
          ? "/repository/" + repoName + "/" + child.path()
          : child.displayName() + "/";
      return new ListingEntry(child.displayName(), href, leaf, child.assetSize(), child.assetLastUpdatedAt());
    }

    static ListingEntry fromPypiChild(String repoName, BrowseNodeDao.BrowseChild child) {
      boolean leaf = child.leaf();
      String href = leaf
          ? "/repository/" + repoName + "/" + child.path()
          : child.displayName() + "/";
      return new ListingEntry(child.displayName(), href, leaf, child.assetSize(), child.assetLastUpdatedAt());
    }

    static ListingEntry directory(String name) {
      return new ListingEntry(name, name + "/", false, null, null);
    }

    static ListingEntry fromAsset(String repoName, AssetRecord asset) {
      return new ListingEntry(
          leafName(asset.path()),
          "/repository/" + repoName + "/" + asset.path(),
          true,
          asset.size(),
          asset.lastUpdatedAt());
    }

    static ListingEntry fromNpmTarball(String repoName, BrowseNodeDao.BrowseChild child) {
      return new ListingEntry(
          child.displayName(),
          "/repository/" + repoName + "/" + child.path(),
          true,
          child.assetSize(),
          child.assetLastUpdatedAt());
    }
  }
}
