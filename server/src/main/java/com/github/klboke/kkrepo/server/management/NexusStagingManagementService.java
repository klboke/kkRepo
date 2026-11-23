package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetDetailView;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetPage;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetSummaryView;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingService;
import com.github.klboke.kkrepo.server.staging.StagingService.StagingItemView;
import com.github.klboke.kkrepo.server.staging.StagingService.StagingSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nexus-shaped read models over multi-repository staging sessions.
 *
 * <p>Nexus has no staging concept, so the multi-repository aspect is exposed through a
 * {@code stagingSession} parameter on the normal Nexus resource paths. The resource shapes stay
 * Nexus-compatible: repositories, assets, components, and continuation-token paging.
 */
@Service
public class NexusStagingManagementService {
  static final int PAGE_SIZE = 50;
  private static final String COMPONENT_ATTRIBUTE = "stagingComponent";

  private final StagingService staging;
  private final RepositoryDao repositories;
  private final NexusStagingIdCodec ids;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  public NexusStagingManagementService(
      StagingService staging,
      RepositoryDao repositories,
      NexusStagingIdCodec ids,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.staging = staging;
    this.repositories = repositories;
    this.ids = ids;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  public boolean isStagingId(String id) {
    return ids.isStagingId(id);
  }

  public AssetPage searchAssets(
      String repositoryName,
      String stagingSession,
      String continuationToken,
      HttpServletRequest request) {
    SessionContext context = session(stagingSession, request);
    StagingSessionView view = context.view();
    StagingAssetContinuation continuation = continuation(
        continuationToken, view.session().id());
    List<StagingItemView> items = filteredItems(context, repositoryName).stream()
        .sorted(Comparator.comparingLong(StagingItemView::id))
        .filter(item -> item.id() > continuation.afterItemId())
        .toList();
    List<AssetSummaryView> page = new ArrayList<>(PAGE_SIZE);
    long last = continuation.afterItemId();
    for (StagingItemView item : items) {
      page.add(summary(item, context, request));
      last = item.id();
      if (page.size() == PAGE_SIZE) break;
    }
    String next = items.size() > page.size()
        ? new NexusAssetIdCodec().encodeContinuation(view.session().id(), last) : null;
    return new AssetPage(List.copyOf(page), next);
  }

  public AssetDetailView getAsset(String id, HttpServletRequest request) {
    NexusStagingIdCodec.DecodedAsset decoded = ids.decodeAsset(id);
    SessionContext context = session(String.valueOf(decoded.sessionId()), request);
    StagingItemView item = staging.itemByKey(
            String.valueOf(decoded.sessionId()), decoded.itemId(), subject(request))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staged asset not found"));
    RepositoryRecord repository = repository(item.repositoryId());
    AssetSummaryView summary = summary(item, context, request);
    return new AssetDetailView(
        summary.downloadUrl(),
        summary.path(),
        summary.id(),
        summary.repository(),
        summary.format(),
        summary.checksum(),
        item.contentType(),
        item.updatedAt(),
        null,
        stringAttribute(item.attributes(), "stagedBy"),
        null,
        item.size(),
        null,
        null,
        Map.of(summary.format(), formatAttributes(item)));
  }

  public int deleteAsset(String id, HttpServletRequest request) {
    NexusStagingIdCodec.DecodedAsset decoded = ids.decodeAsset(id);
    return staging.deleteItemByKey(
        String.valueOf(decoded.sessionId()), decoded.itemId(), subject(request),
        request.getRemoteAddr());
  }

  public ComponentPage components(
      String repositoryName, String stagingSession, HttpServletRequest request) {
    SessionContext context = session(stagingSession, request);
    Map<String, List<StagingItemView>> grouped = new LinkedHashMap<>();
    for (StagingItemView item : filteredItems(context, repositoryName)) {
      ComponentIdentity identity = identity(item);
      grouped.computeIfAbsent(
          item.repositoryId() + ":" + identity.identityHash(), ignored -> new ArrayList<>())
          .add(item);
    }
    List<ComponentView> items = grouped.values().stream()
        .map(group -> component(group, context, request))
        .sorted(Comparator.comparing(ComponentView::repository)
            .thenComparing(ComponentView::name, Comparator.nullsLast(String::compareTo))
            .thenComparing(ComponentView::version, Comparator.nullsLast(String::compareTo)))
        .toList();
    return new ComponentPage(items, null);
  }

  public ComponentView getComponent(String id, HttpServletRequest request) {
    NexusStagingIdCodec.DecodedComponent decoded = ids.decodeComponent(id);
    SessionContext context = session(String.valueOf(decoded.sessionId()), request);
    List<StagingItemView> matching = filteredItems(context, null).stream()
        .filter(item -> item.repositoryId() == decoded.repositoryId())
        .filter(item -> identity(item).identityHash().equals(decoded.identity()))
        .toList();
    if (matching.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staged component not found");
    }
    return component(matching, context, request);
  }

  public List<RepositoryView> repositories(
      String stagingSession, HttpServletRequest request) {
    SessionContext context = session(stagingSession, request);
    return context.view().repositories().stream()
        .map(member -> {
          RepositoryRecord repository = repository(member.repositoryId());
          return new RepositoryView(
              repository.name(),
              repository.format() == null ? member.format() : repository.format().id(),
              repository.type() == null ? null : repository.type().name().toLowerCase(java.util.Locale.ROOT),
              repositoryBaseUrl(request, repository.name()),
              repository.online(),
              Map.of(
                  "stagingSession", context.view().session().name(),
                  "stagingSessionId", context.view().session().id()));
        })
        .toList();
  }

  private ComponentView component(
      List<StagingItemView> group, SessionContext context, HttpServletRequest request) {
    StagingItemView first = group.getFirst();
    RepositoryRecord repository = repository(first.repositoryId());
    StagingComponent component = componentProjection(first);
    String id = ids.encodeComponent(
        context.view().session().id(), first.repositoryId(), component.namespace(),
        component.name(), component.version(), component.kind());
    List<AssetSummaryView> assets = group.stream()
        .sorted(Comparator.comparingLong(StagingItemView::id))
        .map(item -> summary(item, context, request))
        .toList();
    return new ComponentView(
        id,
        repository.name(),
        repository.format() == null ? first.format() : repository.format().id(),
        component.namespace(),
        component.name(),
        component.version(),
        component.kind(),
        assets,
        component.attributes());
  }

  private List<StagingItemView> filteredItems(
      SessionContext context, String repositoryName) {
    List<StagingItemView> items = staging.itemsByKey(
        String.valueOf(context.view().session().id()), context.actor());
    if (repositoryName == null || repositoryName.isBlank()) {
      return items;
    }
    RepositoryRecord repository = repositories.findByName(repositoryName)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Repository not found: " + repositoryName));
    return items.stream().filter(item -> item.repositoryId() == repository.id()).toList();
  }

  private AssetSummaryView summary(
      StagingItemView item, SessionContext context, HttpServletRequest request) {
    RepositoryRecord repository = repository(item.repositoryId());
    String downloadUrl = repositoryBaseUrl(request, repository.name()) + "/"
        + item.logicalPath() + "?stagingSession=" + context.view().session().id();
    Map<String, String> checksum = new LinkedHashMap<>();
    put(checksum, "md5", stringAttribute(item.attributes(), "md5"));
    put(checksum, "sha1", stringAttribute(item.attributes(), "sha1"));
    put(checksum, "sha256", item.sha256());
    return new AssetSummaryView(
        downloadUrl,
        item.logicalPath(),
        ids.encodeAsset(context.view().session().id(), item.id()),
        repository.name(),
        repository.format() == null ? item.format() : repository.format().id(),
        Map.copyOf(checksum));
  }

  private SessionContext session(String key, HttpServletRequest request) {
    String sessionKey = key;
    if (sessionKey == null || sessionKey.isBlank()) {
      sessionKey = request.getHeader(StagingService.HEADER);
    }
    if (sessionKey == null || sessionKey.isBlank()) {
      sessionKey = request.getParameter("stagingSession");
    }
    if (sessionKey == null || sessionKey.isBlank()) {
      Object bound = request.getAttribute(
          com.github.klboke.kkrepo.server.staging.StagingCredentialService.SESSION_ATTRIBUTE);
      sessionKey = bound == null ? null : bound.toString();
    }
    if (sessionKey == null || sessionKey.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "stagingSession is required");
    }
    PermissionSubject actor = subject(request);
    StagingSessionView view = staging.getByKey(sessionKey.trim(), actor);
    return new SessionContext(
        view, actor);
  }

  private StagingAssetContinuation continuation(String token, long sessionId) {
    if (token == null || token.isBlank()) return new StagingAssetContinuation(0);
    NexusAssetIdCodec.DecodedContinuation decoded = new NexusAssetIdCodec()
        .decodeContinuation(token);
    if (decoded.repositoryId() != sessionId) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "continuationToken belongs to another staging session");
    }
    return new StagingAssetContinuation(decoded.lastAssetId());
  }

  private RepositoryRecord repository(long repositoryId) {
    return repositories.findById(repositoryId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Repository not found: " + repositoryId));
  }

  @SuppressWarnings("unchecked")
  private static ComponentIdentity identity(StagingItemView item) {
    StagingComponent component = componentProjection(item);
    return new ComponentIdentity(
        NexusStagingIdCodec.componentIdentity(
            component.namespace(), component.name(), component.version(), component.kind()),
        component);
  }

  @SuppressWarnings("unchecked")
  private static StagingComponent componentProjection(StagingItemView item) {
    Object value = item.attributes() == null ? null : item.attributes().get(COMPONENT_ATTRIBUTE);
    if (value instanceof Map<?, ?> raw) {
      String namespace = string(raw.get("namespace"));
      String name = string(raw.get("name"));
      String version = string(raw.get("version"));
      String kind = string(raw.get("kind"));
      Map<String, Object> attributes = raw.get("attributes") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      return new StagingComponent(namespace, name, version, kind, attributes);
    }
    return new StagingComponent(null, item.logicalPath(), null, null, Map.of());
  }

  private static String stringAttribute(Map<String, Object> attributes, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    return value == null ? null : value.toString();
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static void put(Map<String, String> values, String key, String value) {
    if (value != null && !value.isBlank()) values.put(key, value);
  }

  private String repositoryBaseUrl(HttpServletRequest request, String repository) {
    return forwardedHeaderPolicy.serverBaseUrl(request) + request.getContextPath()
        + RepositoryUrlPrefix.path(request) + repository;
  }

  private static Map<String, Object> formatAttributes(StagingItemView item) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (item.attributes() != null) attributes.putAll(item.attributes());
    attributes.keySet().removeIf(key -> key.startsWith("staging"));
    return Map.copyOf(attributes);
  }

  private static PermissionSubject subject(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject authenticated
        && authenticated.permissionSubject() != null) {
      return authenticated.permissionSubject();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  private record SessionContext(
      StagingSessionView view, PermissionSubject actor) {
  }

  private record StagingAssetContinuation(long afterItemId) {
  }

  private record StagingComponent(
      String namespace, String name, String version, String kind, Map<String, Object> attributes) {
  }

  private record ComponentIdentity(String identityHash, StagingComponent component) {
  }

  public record ComponentPage(List<ComponentView> items, String continuationToken) {
  }

  public record ComponentView(
      String id,
      String repository,
      String format,
      String group,
      String name,
      String version,
      String kind,
      List<AssetSummaryView> assets,
      Map<String, Object> attributes) {
  }

  public record RepositoryView(
      String name,
      String format,
      String type,
      String url,
      boolean online,
      Map<String, Object> attributes) {
  }
}
