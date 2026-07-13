package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class NpmSearchService {
  private final ComponentDao componentDao;
  private final NpmHostedService hosted;
  private final NpmProxyService proxy;

  public NpmSearchService(ComponentDao componentDao, NpmHostedService hosted, NpmProxyService proxy) {
    this.componentDao = componentDao;
    this.hosted = hosted;
    this.proxy = proxy;
  }

  public Map<String, Object> search(RepositoryRuntime runtime, String keyword, int limit, String repositoryBaseUrl) {
    if (runtime.isProxy()) {
      return proxy.search(runtime, keyword, limit);
    }
    if (runtime.isGroup()) {
      return groupSearch(runtime, keyword, limit, repositoryBaseUrl);
    }
    long start = System.nanoTime();
    Map<String, Object> response = hostedSearch(runtime, keyword, limit, repositoryBaseUrl);
    response.put("time", Math.max(0, (System.nanoTime() - start) / 1_000_000L) + "ms");
    return response;
  }

  private Map<String, Object> groupSearch(
      RepositoryRuntime runtime,
      String keyword,
      int limit,
      String repositoryBaseUrl) {
    long start = System.nanoTime();
    int max = Math.max(1, limit);
    List<Map<String, Object>> objects = new ArrayList<>();
    Set<String> emitted = new LinkedHashSet<>();
    for (RepositoryRuntime member : runtime.members()) {
      if (objects.size() >= max) {
        break;
      }
      Map<String, Object> response = search(member, keyword, max - objects.size(), repositoryBaseUrl);
      for (Map<String, Object> item : searchObjects(response)) {
        String packageName = packageName(item);
        if (packageName != null && emitted.add(packageName)) {
          objects.add(item);
          if (objects.size() >= max) {
            break;
          }
        }
      }
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("objects", objects);
    response.put("total", objects.size());
    response.put("time", Math.max(0, (System.nanoTime() - start) / 1_000_000L) + "ms");
    return response;
  }

  private Map<String, Object> hostedSearch(
      RepositoryRuntime runtime,
      String keyword,
      int limit,
      String repositoryBaseUrl) {
    List<Long> repositoryIds = repositoryIds(runtime);
    List<ComponentSearchRow> rows = componentDao.searchByRepositoryIds(repositoryIds, keyword, limit);
    List<Map<String, Object>> objects = rows.stream()
        .map(row -> item(row, repositoryBaseUrl))
        .toList();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("objects", objects);
    response.put("total", objects.size());
    return response;
  }

  public Map<String, Object> legacyIndex(RepositoryRuntime runtime) {
    Map<Long, RepositoryRuntime> runtimes = repositoryRuntimeIndex(runtime);
    List<ComponentSearchRow> rows = componentDao.searchByRepositoryIds(
        new ArrayList<>(runtimes.keySet()), "", 300);
    Set<String> emitted = new LinkedHashSet<>();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("_updated", System.currentTimeMillis());
    for (ComponentSearchRow row : rows) {
      String packageName = packageName(row);
      if (!emitted.add(packageName)) {
        continue;
      }
      RepositoryRuntime owner = runtimes.get(row.repositoryId());
      if (owner == null) {
        continue;
      }
      NpmPackageId packageId = new NpmPackageId(
          row.namespace() == null || row.namespace().isBlank() ? null : row.namespace(),
          row.name());
      hosted.packageRoot(owner, packageId)
          .map(NpmMetadata::shrinkForSearch)
          .ifPresent(packageRoot -> response.put(packageName, packageRoot));
    }
    return response;
  }

  private List<Long> repositoryIds(RepositoryRuntime runtime) {
    return new ArrayList<>(repositoryRuntimeIndex(runtime).keySet());
  }

  private Map<Long, RepositoryRuntime> repositoryRuntimeIndex(RepositoryRuntime runtime) {
    Map<Long, RepositoryRuntime> ids = new LinkedHashMap<>();
    collectMemberRuntimes(runtime, ids);
    return ids;
  }

  private void collectMemberRuntimes(RepositoryRuntime runtime, Map<Long, RepositoryRuntime> ids) {
    if (!runtime.isGroup()) {
      ids.put(runtime.id(), runtime);
      return;
    }
    for (RepositoryRuntime member : runtime.members()) {
      collectMemberRuntimes(member, ids);
    }
  }

  private Map<String, Object> item(ComponentSearchRow row, String repositoryBaseUrl) {
    String packageName = packageName(row);
    Map<String, Object> pkg = new LinkedHashMap<>();
    pkg.put("name", packageName);
    pkg.put("version", row.version());
    pkg.put("description", "");
    pkg.put("date", row.lastUpdatedAt() == null ? null : row.lastUpdatedAt().toString());
    pkg.put("links", Map.of("npm", repositoryBaseUrl + "/" + packageName));
    pkg.put("publisher", Map.of("username", "anonymous"));
    pkg.put("maintainers", List.of());

    Map<String, Object> score = new LinkedHashMap<>();
    score.put("final", 1.0);
    score.put("detail", Map.of(
        "quality", 1.0,
        "popularity", 0.0,
        "maintenance", 1.0));

    Map<String, Object> item = new LinkedHashMap<>();
    item.put("package", pkg);
    item.put("score", score);
    item.put("searchScore", 1.0);
    return item;
  }

  private String packageName(ComponentSearchRow row) {
    return row.namespace() == null || row.namespace().isBlank()
        ? row.name()
        : "@" + row.namespace() + "/" + row.name();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> searchObjects(Map<String, Object> response) {
    Object objects = response.get("objects");
    if (!(objects instanceof Iterable<?> iterable)) {
      return List.of();
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : iterable) {
      if (item instanceof Map<?, ?> map) {
        result.add((Map<String, Object>) map);
      }
    }
    return result;
  }

  private String packageName(Map<String, Object> item) {
    Object pkg = item.get("package");
    if (!(pkg instanceof Map<?, ?> packageMap)) {
      return null;
    }
    Object name = packageMap.get("name");
    return name == null ? null : name.toString();
  }
}
