package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Administrative deletion of Docker browse entries, including locally cached proxy content. */
@Service
public class DockerBrowseDeleteService {
  private static final String MANIFESTS = "/manifests";

  private final RepositoryDao repositoryDao;
  private final DockerRegistryDao dockerDao;
  private final RepositoryRuntimeRegistry runtimes;
  private final DockerManifestStore manifests;

  public DockerBrowseDeleteService(
      RepositoryDao repositoryDao,
      DockerRegistryDao dockerDao,
      RepositoryRuntimeRegistry runtimes,
      DockerManifestStore manifests) {
    this.repositoryDao = repositoryDao;
    this.dockerDao = dockerDao;
    this.runtimes = runtimes;
    this.manifests = manifests;
  }

  /**
   * Uses database-backed Docker identities, never generic asset path deletion. The transaction
   * covers the selected references; concurrent uploads after the directory snapshot may remain.
   * DockerManifestStore maintains tag/manifest/blob state and shared cache versions after commit,
   * so sibling replicas observe the deletion without depending on this node's browse state.
   */
  @Transactional
  public BrowseContentDeleteController.BrowseDeleteResult delete(
      RepositoryRecord requested, String path, String sourceRepository) {
    for (RepositoryRecord source : sources(requested, sourceRepository)) {
      List<Reference> references = references(source, path);
      if (references.isEmpty()) {
        continue;
      }
      var runtime = runtimes.resolveById(source.id()).orElseThrow(() ->
          new ResponseStatusException(HttpStatus.CONFLICT, "Repository runtime is unavailable"));
      int deleted = 0;
      for (Reference reference : references) {
        deleted += manifests.deleteReference(runtime, reference.image(), reference.value());
      }
      if (deleted == 0) {
        throw notFound(path);
      }
      return new BrowseContentDeleteController.BrowseDeleteResult(
          requested.name(), source.name(), path, deleted);
    }
    throw notFound(path);
  }

  private List<RepositoryRecord> sources(RepositoryRecord requested, String sourceRepository) {
    if (sourceRepository == null || sourceRepository.isBlank()) {
      return requested.type() == RepositoryType.GROUP
          ? repositoryDao.listMembers(requested.id()) : List.of(requested);
    }
    RepositoryRecord source = repositoryDao.findByName(sourceRepository.trim())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Repository not found: " + sourceRepository));
    if (!source.id().equals(requested.id())) {
      if (requested.type() != RepositoryType.GROUP) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must match repository");
      }
      if (repositoryDao.listMembers(requested.id()).stream()
          .noneMatch(member -> member.id().equals(source.id()))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "source is not a member of " + requested.name());
      }
    }
    return List.of(source);
  }

  private List<Reference> references(RepositoryRecord source, String path) {
    int marker = path.lastIndexOf(MANIFESTS + "/");
    if (marker > 0) {
      String value = path.substring(marker + MANIFESTS.length() + 1);
      if (!value.contains("/")) {
        String image = path.substring(0, marker);
        validate(image, value);
        return dockerDao.findManifestByReference(source.id(), image, value).isPresent()
            ? List.of(new Reference(image, value)) : List.of();
      }
    }
    boolean manifestDirectory = path.endsWith(MANIFESTS);
    String parent = manifestDirectory
        ? path.substring(0, path.length() - MANIFESTS.length()) : path;
    validate(parent, null);
    List<String> images = manifestDirectory
        ? dockerDao.imageExists(source.id(), parent) ? List.of(parent) : List.of()
        : dockerDao.listBrowseImages(source.id(), parent).stream()
            .map(DockerRegistryDao.BrowseImageRow::imageName)
            // SQL LIKE can also match wildcard characters in names; deletion must stay literal.
            .filter(image -> image.equals(parent) || image.startsWith(parent + "/"))
            .toList();
    LinkedHashSet<Reference> references = new LinkedHashSet<>();
    for (String image : images) {
      for (DockerRegistryDao.BrowseReferenceRow row : dockerDao.listBrowseReferences(source.id(), image)) {
        references.add(new Reference(image, row.digest()));
      }
    }
    return List.copyOf(references);
  }

  private static void validate(String image, String reference) {
    try {
      DockerPathParser.validateImageName(image);
      if (reference != null && !DockerPathParser.isDigestReference(reference)) {
        DockerPathParser.validateTag(reference);
      }
    } catch (DockerProtocolException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
    }
  }

  private static ResponseStatusException notFound(String path) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Browse path not found: " + path);
  }

  private record Reference(String image, String value) {}
}
