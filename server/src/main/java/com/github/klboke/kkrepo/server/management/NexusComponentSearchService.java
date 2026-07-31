package com.github.klboke.kkrepo.server.management;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCriteria;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedComponentContinuation;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetSummaryView;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nexus-compatible component search backed entirely by shared relational state. */
@Service
public class NexusComponentSearchService {
  static final int PAGE_SIZE = 50;
  private static final int FETCH_SIZE = PAGE_SIZE + 1;

  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final RepositoryDao repositoryDao;
  private final NexusRepositoryManagementAuthorizer authorizer;
  private final NexusAssetIdCodec idCodec;
  private final NexusAssetManagementService assetManagementService;

  public NexusComponentSearchService(
      ComponentDao componentDao,
      AssetDao assetDao,
      RepositoryDao repositoryDao,
      NexusRepositoryManagementAuthorizer authorizer,
      NexusAssetIdCodec idCodec,
      NexusAssetManagementService assetManagementService) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.repositoryDao = repositoryDao;
    this.authorizer = authorizer;
    this.idCodec = idCodec;
    this.assetManagementService = assetManagementService;
  }

  @Transactional
  public ComponentPage search(SearchRequest search, HttpServletRequest request) {
    SearchSpec spec = SearchSpec.from(search);
    authorizer.requireSearch(request);
    long afterComponentId = continuationOffset(spec, search.continuationToken());
    if (spec.invalidFormat()) {
      return new ComponentPage(List.of(), null);
    }

    Map<Long, RepositoryRecord> repositories = new LinkedHashMap<>();
    for (RepositoryRecord repository : repositoryDao.list()) {
      repositories.put(repository.id(), repository);
    }
    List<VisibleComponent> visible = new ArrayList<>(FETCH_SIZE);
    long cursor = afterComponentId;
    while (visible.size() < FETCH_SIZE) {
      long batchStart = cursor;
      List<ComponentSearchRow> rows = componentDao.searchPage(
          spec.criteria(), cursor, FETCH_SIZE);
      if (rows.isEmpty()) {
        break;
      }
      for (ComponentSearchRow row : rows) {
        if (row.id() <= cursor) {
          continue;
        }
        cursor = row.id();
        ComponentView item = visibleComponent(row, repositories.get(row.repositoryId()), request);
        if (item != null) {
          visible.add(new VisibleComponent(row.id(), item));
          if (visible.size() == FETCH_SIZE) {
            break;
          }
        }
      }
      if (cursor == batchStart || rows.size() < FETCH_SIZE) {
        break;
      }
    }

    List<ComponentView> items = visible.stream()
        .limit(PAGE_SIZE)
        .map(VisibleComponent::view)
        .toList();
    String continuationToken = visible.size() > PAGE_SIZE
        ? idCodec.encodeComponentContinuation(
            spec.fingerprint(), visible.get(PAGE_SIZE - 1).componentId())
        : null;
    return new ComponentPage(items, continuationToken);
  }

  private long continuationOffset(SearchSpec spec, String token) {
    if (token == null || token.isBlank()) {
      return 0;
    }
    DecodedComponentContinuation decoded = idCodec.decodeComponentContinuation(token);
    if (!spec.fingerprint().equals(decoded.queryFingerprint())) {
      throw new NexusAssetManagementService.InvalidSearchRequestException(
          "continuationToken belongs to another component search");
    }
    return decoded.lastComponentId();
  }

  private ComponentView visibleComponent(
      ComponentSearchRow row,
      RepositoryRecord repository,
      HttpServletRequest request) {
    if (repository == null
        || !repository.name().equals(row.repositoryName())
        || repository.format() != row.format()) {
      return null;
    }
    List<AssetSummaryView> assets = assetDao.listAssetWithBlobByComponent(row.id()).stream()
        .filter(stored -> belongsTo(stored.asset(), row, repository))
        .filter(stored -> authorizer.repositoryActionAllowed(
            request, repository, stored.asset().path(), PermissionAction.BROWSE))
        .map(stored -> assetManagementService.componentSearchSummary(repository, stored, request))
        .toList();
    if (assets.isEmpty()) {
      return null;
    }
    return new ComponentView(
        idCodec.encodeAssetId(repository.name(), row.id()),
        repository.name(),
        repository.format().id(),
        row.namespace(),
        row.name(),
        row.version(),
        assets);
  }

  private static boolean belongsTo(
      AssetRecord asset, ComponentSearchRow component, RepositoryRecord repository) {
    return asset.id() != null
        && asset.componentId() != null
        && asset.componentId() == component.id()
        && asset.repositoryId() == repository.id()
        && asset.format() == repository.format();
  }

  private static String fingerprint(List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(String.join("\u001f", values).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  public record SearchRequest(
      String keyword,
      String repository,
      String format,
      String group,
      String name,
      String version,
      String continuationToken) {
  }

  public record ComponentView(
      String id,
      String repository,
      String format,
      String group,
      String name,
      String version,
      List<AssetSummaryView> assets) {
  }

  public record ComponentPage(
      List<ComponentView> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) String continuationToken) {
  }

  private record VisibleComponent(long componentId, ComponentView view) {
  }

  private record SearchSpec(
      ComponentSearchCriteria criteria,
      String fingerprint,
      boolean invalidFormat) {

    private static SearchSpec from(SearchRequest request) {
      String keyword = blankToNull(request.keyword());
      String repository = blankToNull(request.repository());
      String formatValue = blankToNull(request.format());
      String group = blankToNull(request.group());
      String name = blankToNull(request.name());
      String version = blankToNull(request.version());
      RepositoryFormat format = null;
      boolean invalidFormat = false;
      if (formatValue != null) {
        try {
          format = RepositoryFormat.fromJson(formatValue);
        } catch (IllegalArgumentException ignored) {
          invalidFormat = true;
        }
      }
      String normalizedFormat = formatValue == null
          ? null : formatValue.toLowerCase(Locale.ROOT);
      ComponentSearchCriteria criteria = new ComponentSearchCriteria(
          keyword, format, repository, group, name, version);
      return new SearchSpec(
          criteria,
          NexusComponentSearchService.fingerprint(List.of(
              value(keyword),
              value(repository),
              value(normalizedFormat),
              value(group),
              value(name),
              value(version))),
          invalidFormat);
    }

    private static String value(String value) {
      return value == null ? "" : value;
    }
  }
}
