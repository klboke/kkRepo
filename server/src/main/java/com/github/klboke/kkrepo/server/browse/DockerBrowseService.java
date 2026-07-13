package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DockerBrowseService {
  private static final String MANIFESTS_SEGMENT = "/manifests";

  private final DockerRegistryDao dockerDao;

  public DockerBrowseService(DockerRegistryDao dockerDao) {
    this.dockerDao = dockerDao;
  }

  public List<BrowseController.BrowseEntry> list(
      RepositoryRecord visibleRepository,
      List<RepositoryRecord> sources,
      String parentPath) {
    String parent = normalize(parentPath);
    LinkedHashMap<String, BrowseController.BrowseEntry> merged = new LinkedHashMap<>();
    for (RepositoryRecord source : sources) {
      for (BrowseController.BrowseEntry entry : listSource(visibleRepository, source, parent)) {
        merged.putIfAbsent(entry.path(), entry);
      }
    }
    List<BrowseController.BrowseEntry> entries = new ArrayList<>(merged.values());
    entries.sort((a, b) -> {
      if (a.leaf() != b.leaf()) return a.leaf() ? 1 : -1;
      return a.path().compareTo(b.path());
    });
    return entries;
  }

  private List<BrowseController.BrowseEntry> listSource(
      RepositoryRecord visibleRepository,
      RepositoryRecord source,
      String parent) {
    String imageName = imageNameForManifestParent(parent);
    if (imageName != null) {
      return dockerDao.listBrowseReferences(source.id(), imageName).stream()
          .map(row -> new BrowseController.BrowseEntry(
              row.reference(),
              parent + "/" + row.reference(),
              source.name(),
              true,
              row.size(),
              row.mediaType(),
              null,
              row.updatedAt(),
              "/v2/" + visibleRepository.name() + "/" + imageName + "/manifests/" + row.reference()))
          .toList();
    }

    LinkedHashMap<String, BrowseController.BrowseEntry> entries = new LinkedHashMap<>();
    for (DockerRegistryDao.BrowseImageRow row : dockerDao.listBrowseImages(source.id(), parent)) {
      String image = row.imageName();
      if (image.equals(parent)) {
        String path = parent + MANIFESTS_SEGMENT;
        entries.putIfAbsent(path, new BrowseController.BrowseEntry(
            "manifests",
            path,
            source.name(),
            false,
            null,
            null,
            null,
            row.updatedAt(),
            null));
        continue;
      }
      String child = nextSegment(parent, image);
      if (child == null) {
        continue;
      }
      String path = parent.isEmpty() ? child : parent + "/" + child;
      entries.putIfAbsent(path, new BrowseController.BrowseEntry(
          child,
          path,
          source.name(),
          false,
          null,
          null,
          null,
          row.updatedAt(),
          null));
    }
    return new ArrayList<>(entries.values());
  }

  private static String imageNameForManifestParent(String parent) {
    if (parent == null || parent.isBlank() || !parent.endsWith(MANIFESTS_SEGMENT)) {
      return null;
    }
    String imageName = parent.substring(0, parent.length() - MANIFESTS_SEGMENT.length());
    return imageName.isBlank() ? null : imageName;
  }

  private static String nextSegment(String parent, String imageName) {
    String rest;
    if (parent == null || parent.isBlank()) {
      rest = imageName;
    } else if (imageName.startsWith(parent + "/")) {
      rest = imageName.substring(parent.length() + 1);
    } else {
      return null;
    }
    int slash = rest.indexOf('/');
    return slash < 0 ? rest : rest.substring(0, slash);
  }

  private static String normalize(String path) {
    String normalized = path == null ? "" : path.trim();
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized;
  }
}
