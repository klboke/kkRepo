package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetPathFilter;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.browse.BrowseAssetVisibility;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Administrative preview of stored assets; it does not download or warm proxy content. */
@Service
public class ContentSelectorPreviewService {
  private static final int PAGE_SIZE = 200;
  private static final int SCAN_LIMIT = 10_000;
  private static final int RESULT_LIMIT = 10;
  private final AssetDao assets;
  private final RepositoryCatalogCache repositories;

  public ContentSelectorPreviewService(AssetDao assets, RepositoryCatalogCache repositories) {
    this.assets = assets;
    this.repositories = repositories;
  }

  public PreviewResult preview(PreviewRequest request) {
    ContentSelectorExpressionEvaluator.validate(request.type(), request.expression());
    if (request.repository() == null || request.repository().isBlank()) {
      throw new SecurityValidationException("repository is required");
    }
    Map<Long, RepositoryRecord> selected = new LinkedHashMap<>();
    Map<Long, AssetPathFilter> filters = new LinkedHashMap<>();
    for (RepositoryRecord repository : repositories.snapshot().records()) {
      String scope = request.repository();
      if (!scope.equals("*") && !scope.equals(repository.name())
          && !scope.equals("*-" + repository.format().id())) continue;
      var filter = ContentSelectorExpressionEvaluator.candidateFilter(request.expression(),
          repository.name(), repository.format().id());
      if (!filter.equals(AssetPathFilter.NONE)) {
        selected.put(repository.id(), repository);
        filters.put(repository.id(), filter);
      }
    }
    List<PreviewAsset> results = new ArrayList<>();
    if (filters.isEmpty()) return new PreviewResult(0, results, false, true, 0);
    long cursor = 0;
    int scanned = 0;
    int total = 0;
    while (scanned < SCAN_LIMIT) {
      var page = assets.listSelectorCandidates(filters, cursor, PAGE_SIZE);
      if (page.isEmpty()) return new PreviewResult(total, results, total > RESULT_LIMIT, true, scanned);
      for (var asset : page) {
        scanned++;
        cursor = asset.id();
        RepositoryRecord repository = selected.get(asset.repositoryId());
        if (repository == null || BrowseAssetVisibility.hidden(asset.format(), asset.path())
            || !ContentSelectorExpressionEvaluator.matches(request.expression(), repository.name(),
                repository.format().id(), asset.path())) continue;
        total++;
        if (results.size() < RESULT_LIMIT) {
          results.add(new PreviewAsset(String.valueOf(asset.id()), "/" + asset.path(),
              repository.format().id(), asset.contentType(), asset.size(), repository.name(),
              repository.name(), asset.componentId() == null ? null : String.valueOf(asset.componentId()),
              asset.lastUpdatedAt(), asset.lastDownloadedAt()));
        }
      }
      if (page.size() < PAGE_SIZE) return new PreviewResult(total, results, total > RESULT_LIMIT, true, scanned);
    }
    return new PreviewResult(total, results, true, false, scanned);
  }

  public record PreviewRequest(String repository, String type, String expression) {}
  // Core AssetXO fields consumed by the Nexus UI; internal storage references are not exposed.
  public record PreviewAsset(String id, String name, String format, String contentType, Long size,
      String repositoryName, String containingRepositoryName, String componentId,
      java.time.Instant blobUpdated, java.time.Instant lastDownloaded) {}
  /** total is a lower bound when totalExact is false because the residual scan reached its cap. */
  public record PreviewResult(int total, List<PreviewAsset> results, boolean truncated,
      boolean totalExact, int scanned) {}
}
