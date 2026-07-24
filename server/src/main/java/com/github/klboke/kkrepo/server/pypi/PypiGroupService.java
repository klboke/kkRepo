package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PypiGroupService {
  private final PypiHostedService hosted;
  private final PypiProxyService proxy;
  private final PypiGroupSimpleIndexCache simpleIndexCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PypiAssetWriter writer;
  private final PypiAssetReader reader;

  public PypiGroupService(
      PypiHostedService hosted,
      PypiProxyService proxy,
      PypiGroupSimpleIndexCache simpleIndexCache,
      GroupMemberAssetCache groupMemberAssetCache,
      BlobStorageRegistry blobStorageRegistry,
      PypiAssetWriter writer,
      PypiAssetReader reader) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.simpleIndexCache = simpleIndexCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
  }

  public PypiResponse getRootIndex(RepositoryRuntime group, boolean headOnly) {
    ensureGroup(group);
    return getOrBuildIndex(
        group,
        PypiPaths.INDEX_PREFIX,
        PypiGroupSimpleIndexCache.ROOT_INDEX,
        null,
        headOnly,
        () -> mergeRootIndex(group));
  }

  private String mergeRootIndex(RepositoryRuntime group) {
    Map<String, PypiLink> merged = new LinkedHashMap<>();
    for (RepositoryRuntime member : group.members()) {
      try {
        String html = readHtml(dispatchRoot(member));
        for (PypiLink link : PypiIndex.parse(html)) {
          merged.putIfAbsent(link.file().toLowerCase(), link);
        }
      } catch (PypiExceptions.PypiNotFoundException | PypiExceptions.BadUpstreamException
          | PypiExceptions.MethodNotAllowed ignored) {
        // absent/down members do not prevent the group from serving the rest
      }
    }
    return PypiIndex.buildRoot(merged.values());
  }

  public PypiResponse getIndex(RepositoryRuntime group, String projectName, boolean headOnly) {
    ensureGroup(group);
    String normalized = PypiPaths.normalizeName(projectName);
    String path = PypiPaths.indexPath(normalized);
    return getOrBuildIndex(
        group,
        path,
        PypiGroupSimpleIndexCache.INDEX,
        normalized,
        headOnly,
        () -> mergeProjectIndex(group, normalized));
  }

  private String mergeProjectIndex(RepositoryRuntime group, String normalized) {
    Map<String, PypiLink> merged = new LinkedHashMap<>();
    for (RepositoryRuntime member : group.members()) {
      try {
        String html = readHtml(dispatchIndex(member, normalized));
        for (PypiLink link : PypiIndex.parse(html)) {
          merged.putIfAbsent(link.file().toLowerCase(), link);
        }
      } catch (PypiExceptions.PypiNotFoundException | PypiExceptions.BadUpstreamException
          | PypiExceptions.MethodNotAllowed ignored) {
        // try next member
      }
    }
    if (merged.isEmpty()) {
      throw new PypiExceptions.PypiNotFoundException(normalized);
    }
    return PypiIndex.buildProject(normalized, merged.values());
  }

  private PypiResponse getOrBuildIndex(
      RepositoryRuntime group,
      String path,
      String kind,
      String projectName,
      boolean headOnly,
      java.util.function.Supplier<String> loader) {
    Instant now = Instant.now();
    if (simpleIndexCache != null) {
      Optional<CachedAssetMetadata> cached = simpleIndexCache.findFresh(group, path, kind, now);
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
    }
    String html = loader.get();
    if (simpleIndexCache == null || !simpleIndexCache.enabled()) {
      return htmlResponse(html, headOnly, now);
    }
    PypiAssetWriter.Stored stored = storeIndex(group, path, kind, projectName, html, !headOnly);
    return responseFromStored(stored, headOnly);
  }

  private PypiAssetWriter.Stored storeIndex(
      RepositoryRuntime group,
      String path,
      String kind,
      String projectName,
      String html,
      boolean keepResponseFile) {
    long blobStoreId = requireBlobStore(group);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    return writer.writeSimpleIndexCache(
        group,
        storage,
        blobStoreId,
        path,
        html.getBytes(StandardCharsets.UTF_8),
        kind,
        projectName,
        simpleIndexCache.freshAttributes(group, projectName, Instant.now()),
        "group",
        group.name(),
        keepResponseFile);
  }

  private PypiResponse responseFromStored(PypiAssetWriter.Stored stored, boolean headOnly) {
    try {
      if (headOnly) {
        stored.discardBody();
        return PypiResponse.noBody(200, stored.blob().size(), stored.asset().contentType(),
            stored.blob().sha1(), stored.asset().lastUpdatedAt());
      }
      reader.beforeRead(stored.asset().id());
      return PypiResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
          stored.blob().sha1(), stored.asset().lastUpdatedAt());
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  public PypiResponse getPackage(RepositoryRuntime group, String path, boolean headOnly) {
    ensureGroup(group);
    NexusCacheType cacheType = cacheType(path);
    Optional<Long> cachedMemberId = groupMemberAssetCache == null
        ? Optional.empty()
        : groupMemberAssetCache.get(group, path, cacheType);
    if (cachedMemberId.isPresent()) {
      RepositoryRuntime cachedMember = group.members().stream()
          .filter(member -> member.id() == cachedMemberId.get())
          .findFirst()
          .orElse(null);
      if (cachedMember != null) {
        try {
          return dispatchPackage(cachedMember, path, headOnly);
        } catch (PypiExceptions.PypiNotFoundException | PypiExceptions.BadUpstreamException
            | PypiExceptions.MethodNotAllowed ignored) {
          groupMemberAssetCache.evict(group, path, cacheType);
        }
      }
    }
    for (RepositoryRuntime member : group.members()) {
      try {
        PypiResponse response = dispatchPackage(member, path, headOnly);
        if (groupMemberAssetCache != null) {
          groupMemberAssetCache.put(group, path, cacheType, member.id());
        }
        return response;
      } catch (PypiExceptions.PypiNotFoundException ignored) {
        // try next member
      } catch (PypiExceptions.BadUpstreamException e) {
        // Nexus group repositories only return successful member responses; keep probing.
      } catch (PypiExceptions.MethodNotAllowed ignored) {
        // skip
      }
    }
    throw new PypiExceptions.PypiNotFoundException(path);
  }

  private PypiResponse dispatchRoot(RepositoryRuntime member) {
    return switch (member.type()) {
      case HOSTED -> hosted.getRootIndex(member, false);
      case PROXY -> proxy.getRootIndex(member, false);
      case GROUP -> getRootIndex(member, false);
    };
  }

  private PypiResponse dispatchIndex(RepositoryRuntime member, String projectName) {
    return switch (member.type()) {
      case HOSTED -> hosted.getIndex(member, projectName, false);
      case PROXY -> proxy.getIndex(member, projectName, false);
      case GROUP -> getIndex(member, projectName, false);
    };
  }

  private PypiResponse dispatchPackage(RepositoryRuntime member, String path, boolean headOnly) {
    return switch (member.type()) {
      case HOSTED -> hosted.getPackage(member, path, headOnly);
      case PROXY -> proxy.getPackage(member, path, headOnly);
      case GROUP -> getPackage(member, path, headOnly);
    };
  }

  private String readHtml(PypiResponse response) {
    if (!response.hasBody()) return "";
    try (var in = response.body()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading PyPI group member response", e);
    }
  }

  private PypiResponse htmlResponse(String html, boolean headOnly, Instant lastModified) {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    if (headOnly) {
      return PypiResponse.noBody(200, bytes.length, "text/html", null, lastModified);
    }
    return PypiResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/html", null, lastModified);
  }

  private void ensureGroup(RepositoryRuntime group) {
    if (!group.isGroup()) {
      throw new IllegalStateException("PypiGroupService called on non-group " + group.name());
    }
  }

  private static NexusCacheType cacheType(String path) {
    return path != null && path.endsWith(".asc") ? NexusCacheType.METADATA : NexusCacheType.CONTENT;
  }

  private static long requireBlobStore(RepositoryRuntime runtime) {
    if (runtime.blobStoreId() == null) {
      throw new IllegalStateException("Repository has no blob store: " + runtime.name());
    }
    return runtime.blobStoreId();
  }
}
