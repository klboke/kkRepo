package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.support.dao.DockerRegistryDaoAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerBrowseServiceTest {
  @Test
  void rootListsTopLevelImageNamespaces() {
    StubDockerRegistryDao dockerDao = new StubDockerRegistryDao();
    dockerDao.images = List.of(
        image("codex/alpine"),
        image("library/alpine"),
        image("library/busybox"));
    DockerBrowseService service = new DockerBrowseService(dockerDao);

    List<BrowseController.BrowseEntry> entries = service.list(
        repo(1L, "docker-hosted", RepositoryType.HOSTED),
        List.of(repo(1L, "docker-hosted", RepositoryType.HOSTED)),
        "");

    assertEquals(List.of("codex", "library"), entries.stream().map(BrowseController.BrowseEntry::name).toList());
    assertEquals(List.of("codex", "library"), entries.stream().map(BrowseController.BrowseEntry::path).toList());
  }

  @Test
  void imageListsManifestReferencesAsLeafEntries() {
    StubDockerRegistryDao dockerDao = new StubDockerRegistryDao();
    dockerDao.references = List.of(
        reference("latest", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        reference("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    DockerBrowseService service = new DockerBrowseService(dockerDao);

    List<BrowseController.BrowseEntry> entries = service.list(
        repo(1L, "docker-hosted", RepositoryType.HOSTED),
        List.of(repo(1L, "docker-hosted", RepositoryType.HOSTED)),
        "codex/alpine/manifests");

    assertEquals(List.of("latest", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        entries.stream().map(BrowseController.BrowseEntry::name).toList());
    assertEquals(List.of(true, true), entries.stream().map(BrowseController.BrowseEntry::leaf).toList());
    assertEquals("codex/alpine/manifests/latest", entries.get(0).path());
    assertEquals("/v2/docker-hosted/codex/alpine/manifests/latest", entries.get(0).downloadUrl());
  }

  @Test
  void groupBrowseKeepsFirstMemberForSamePath() {
    StubDockerRegistryDao dockerDao = new StubDockerRegistryDao();
    dockerDao.imagesByRepository.put(2L, List.of(image("library/alpine")));
    dockerDao.imagesByRepository.put(3L, List.of(image("library/alpine"), image("team/app")));
    DockerBrowseService service = new DockerBrowseService(dockerDao);

    List<BrowseController.BrowseEntry> entries = service.list(
        repo(1L, "docker-group", RepositoryType.GROUP),
        List.of(repo(2L, "docker-hosted", RepositoryType.HOSTED), repo(3L, "docker-proxy", RepositoryType.PROXY)),
        "library");

    assertEquals(List.of("alpine"), entries.stream().map(BrowseController.BrowseEntry::name).toList());
    assertEquals("docker-hosted", entries.get(0).sourceRepository());
  }

  private static DockerRegistryDao.BrowseImageRow image(String imageName) {
    return new DockerRegistryDao.BrowseImageRow(
        imageName,
        Instant.parse("2026-06-22T00:00:00Z"),
        527L,
        "application/vnd.oci.image.manifest.v1+json");
  }

  private static DockerRegistryDao.BrowseReferenceRow reference(String reference, String digest) {
    return new DockerRegistryDao.BrowseReferenceRow(
        reference,
        digest,
        10L,
        527L,
        "application/vnd.oci.image.manifest.v1+json",
        Instant.parse("2026-06-22T00:00:00Z"));
  }

  private static RepositoryRecord repo(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.DOCKER,
        type,
        "docker-" + type.name().toLowerCase(Locale.ROOT),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static final class StubDockerRegistryDao extends DockerRegistryDaoAdapter {
    private List<BrowseImageRow> images = List.of();
    private List<BrowseReferenceRow> references = List.of();
    private final Map<Long, List<BrowseImageRow>> imagesByRepository = new java.util.LinkedHashMap<>();

    private StubDockerRegistryDao() {
      super(null, null);
    }

    @Override
    public List<BrowseImageRow> listBrowseImages(long repositoryId, String parentPath) {
      return imagesByRepository.getOrDefault(repositoryId, images);
    }

    @Override
    public List<BrowseReferenceRow> listBrowseReferences(long repositoryId, String imageName) {
      return references;
    }
  }
}
