package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MavenGroupMetadataInvalidatorTest {
  private static final MavenPathParser PARSER = new MavenPathParser();

  @Test
  void metadataChangeDeletesCachedMergeFromContainingGroups() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    RepositoryRecord hosted = repo(1, "hosted", RepositoryType.HOSTED);
    RepositoryRecord group = repo(2, "group", RepositoryType.GROUP);
    RepositoryRecord parent = repo(3, "parent", RepositoryType.GROUP);
    dao.add(hosted, List.of());
    dao.add(group, List.of(hosted));
    dao.add(parent, List.of(group));
    dao.containing.put(hosted.id(), List.of(group));
    dao.containing.put(group.id(), List.of(parent));
    CapturingWriter writer = new CapturingWriter();
    MavenGroupMetadataInvalidator invalidator = new MavenGroupMetadataInvalidator(
        dao,
        new RepositoryRuntimeRegistry(dao, 0),
        new FakeBlobStorageRegistry(),
        writer);

    invalidator.memberMetadataStored(runtime(hosted), PARSER.parsePath("com/acme/app/maven-metadata.xml"));

    assertEquals(List.of("group:com/acme/app/maven-metadata.xml", "parent:com/acme/app/maven-metadata.xml"),
        writer.deletes);
  }

  @Test
  void metadataHashChangesDoNotEvictGroupCacheSeparately() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    RepositoryRecord hosted = repo(1, "hosted", RepositoryType.HOSTED);
    RepositoryRecord group = repo(2, "group", RepositoryType.GROUP);
    dao.add(hosted, List.of());
    dao.add(group, List.of(hosted));
    dao.containing.put(hosted.id(), List.of(group));
    CapturingWriter writer = new CapturingWriter();
    MavenGroupMetadataInvalidator invalidator = new MavenGroupMetadataInvalidator(
        dao,
        new RepositoryRuntimeRegistry(dao, 0),
        new FakeBlobStorageRegistry(),
        writer);

    invalidator.memberMetadataStored(runtime(hosted), PARSER.parsePath("com/acme/app/maven-metadata.xml.sha1"));

    assertTrue(writer.deletes.isEmpty());
  }

  @Test
  void ordinaryArtifactChangeBumpsContainingGroupContentTokens() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    RepositoryRecord hosted = repo(1, "hosted", RepositoryType.HOSTED);
    RepositoryRecord group = repo(2, "group", RepositoryType.GROUP);
    RepositoryRecord parent = repo(3, "parent", RepositoryType.GROUP);
    dao.add(hosted, List.of());
    dao.add(group, List.of(hosted));
    dao.add(parent, List.of(group));
    dao.containing.put(hosted.id(), List.of(group));
    dao.containing.put(group.id(), List.of(parent));
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    MavenGroupMetadataInvalidator invalidator = new MavenGroupMetadataInvalidator(
        dao,
        new RepositoryRuntimeRegistry(dao, 0),
        new FakeBlobStorageRegistry(),
        new CapturingWriter(),
        new NexusLikeCacheController(watermark, 60));

    invalidator.memberAssetStored(runtime(hosted), PARSER.parsePath("com/acme/app/1.0/app-1.0.jar"));

    assertEquals(1, watermark.current("repo:2:CONTENT"));
    assertEquals(1, watermark.current("repo:3:CONTENT"));
  }

  private static RepositoryRuntime runtime(RepositoryRecord record) {
    return new RepositoryRuntime(
        record.id(),
        record.name(),
        record.format(),
        record.type(),
        record.recipeName(),
        record.online(),
        record.blobStoreId(),
        record.writePolicy(),
        record.versionPolicy(),
        record.layoutPolicy(),
        record.strictContentTypeValidation(),
        record.proxyRemoteUrl(),
        null,
        null,
        null, null, List.of());
  }

  private static RepositoryRecord repo(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        type,
        type == RepositoryType.GROUP ? "maven2-group" : "maven2-hosted",
        true,
        1L,
        null,
        null,
        "MIXED",
        "PERMISSIVE",
        "ALLOW",
        true,
        Map.of());
  }

  private static class FakeRepositoryDao extends RepositoryDaoAdapter {
    private final Map<Long, RepositoryRecord> byId = new HashMap<>();
    private final Map<Long, List<RepositoryRecord>> members = new HashMap<>();
    private final Map<Long, List<RepositoryRecord>> containing = new HashMap<>();

    FakeRepositoryDao() {
      super(null, null);
    }

    void add(RepositoryRecord record, List<RepositoryRecord> memberRecords) {
      byId.put(record.id(), record);
      members.put(record.id(), memberRecords);
    }

    @Override
    public Optional<RepositoryRecord> findById(long id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return members.getOrDefault(groupRepositoryId, List.of());
    }

    @Override
    public List<RepositoryRecord> listGroupsContaining(long memberRepositoryId) {
      return containing.getOrDefault(memberRepositoryId, List.of());
    }
  }

  private static class CapturingWriter extends MavenAssetWriter {
    private final List<String> deletes = new ArrayList<>();

    CapturingWriter() {
      super(null, null, null, null, null);
    }

    @Override
    int deleteAsset(RepositoryRuntime runtime, BlobStorage storage, MavenPath path, boolean notifyGroupMetadata) {
      deletes.add(runtime.name() + ":" + path.path());
      return 1;
    }
  }

  private static class FakeBlobStorageRegistry extends BlobStorageRegistry {
    FakeBlobStorageRegistry() {
      super(null, null, null, null, 0L);
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return new NoopBlobStorage();
    }
  }

  private static class NoopBlobStorage implements BlobStorage {
    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return null;
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }
}
