package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HuggingFaceRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {

  @Test
  void revisionsFilesApiProjectionAndDurableFencingArePortable() {
    long repositoryId = insertRepository("hf-registry-mysql", "huggingface");
    jdbc().update(
        "UPDATE repository SET type = 'proxy', recipe_name = 'huggingface-proxy' WHERE id = ?",
        repositoryId);
    HuggingFaceRegistryDao dao = stores().huggingFaceRegistry();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    String repoId = "openai/fixture-model";
    String commitA = "a".repeat(40);
    String commitB = "b".repeat(40);
    long componentId = stores().components().upsertReturningId(new ComponentRecord(
        null, repositoryId, RepositoryFormat.HUGGINGFACE, "openai", "fixture-model", commitA,
        "model-revision", PersistenceHashes.sha256("hf-component", commitA), Map.of(), now));

    HuggingFaceRegistryDao.ModelRevision revision = dao.upsertRevision(
        new HuggingFaceRegistryDao.ModelRevision(
            null, repositoryId, repoId, commitA, componentId, null, "fixture", now,
            false, false, "transformers", "text-generation", "apache-2.0", now, now));
    assertEquals(revision, dao.findRevision(repositoryId, repoId, commitA).orElseThrow());

    HuggingFaceRegistryDao.RevisionRef first = dao.upsertRef(
        new HuggingFaceRegistryDao.RevisionRef(
            repositoryId, repoId, "main", commitA, 0, now.plusSeconds(60), now, now));
    HuggingFaceRegistryDao.RevisionRef moved = dao.upsertRef(
        new HuggingFaceRegistryDao.RevisionRef(
            repositoryId, repoId, "main", commitB, 0, now.plusSeconds(120), now, now));
    assertEquals(first.generation() + 1, moved.generation());

    HuggingFaceRegistryDao.ModelFile file = dao.upsertFileMetadata(
        new HuggingFaceRegistryDao.ModelFile(
            null, revision.id(), repositoryId, repoId, commitA, "model.safetensors", null,
            componentId, "c".repeat(40), "d".repeat(64), "xet-fixture", 7L, null,
            "application/octet-stream", "SAFETENSORS", HuggingFaceRegistryDao.FILE_DISCOVERED,
            0L, null, null, now));
    String fetchKey = repoId + "\u0000" + commitA + "\u0000model.safetensors";
    HuggingFaceRegistryDao.FetchLease lease = dao.tryAcquireLease(
        repositoryId, fetchKey, "owner-1", now.plusSeconds(60)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        repositoryId, fetchKey, "owner-2", now.plusSeconds(60)).isEmpty());
    assertTrue(dao.markFileFetching(file.id(), lease.fencingToken(), now));

    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, null, RepositoryFormat.HUGGINGFACE,
        repoId + "/resolve/" + commitA + "/model.safetensors",
        PersistenceHashes.sha256("hf-asset", commitA), "model.safetensors", "model-file",
        "application/octet-stream", 7L, null, now, Map.of()));
    assertFalse(dao.markFileReady(
        file.id(), lease.fencingToken() + 1, assetId, componentId, "e".repeat(64),
        "application/octet-stream", now));
    assertTrue(dao.markFileReady(
        file.id(), lease.fencingToken(), assetId, componentId, "e".repeat(64),
        "application/octet-stream", now));
    assertEquals(HuggingFaceRegistryDao.FILE_READY,
        dao.findFile(repositoryId, repoId, commitA, "model.safetensors").orElseThrow().state());
    assertEquals(List.of(file.id()), dao.listRevisionFiles(revision.id(), 0, 10).stream()
        .map(HuggingFaceRegistryDao.ModelFile::id).toList());

    dao.upsertRouteProjection(new HuggingFaceRegistryDao.RouteProjection(
        repositoryId, repoId + "/resolve/main/model.safetensors", file.id(), "main",
        first.generation(), now));
    assertEquals(file.id(), dao.findRouteProjection(
        repositoryId, repoId + "/resolve/main/model.safetensors").orElseThrow().fileId());
    HuggingFaceRegistryDao.ApiCacheEntry api = dao.upsertApiCache(
        new HuggingFaceRegistryDao.ApiCacheEntry(
            null, repositoryId, "api/models/" + repoId, "expand=true", "", null, null,
            "upstream", "derived", null, 1, now.plusSeconds(60), now));
    assertEquals(api, dao.findApiCache(
        repositoryId, "api/models/" + repoId, "expand=true", "").orElseThrow());

    dao.releaseLease(repositoryId, fetchKey, "owner-1", lease.fencingToken());
    HuggingFaceRegistryDao.FetchLease takeover = dao.tryAcquireLease(
        repositoryId, fetchKey, "owner-2", Instant.now().plusSeconds(120)).orElseThrow();
    assertTrue(takeover.fencingToken() > lease.fencingToken());
    assertFalse(dao.renewLease(
        repositoryId, fetchKey, "owner-1", lease.fencingToken(), now.plusSeconds(180)));
    assertEquals(0, dao.deleteExpiredLeases(Instant.now().plusSeconds(1), 10));

    dao.deleteRepositoryState(repositoryId);
    assertTrue(dao.findRevision(repositoryId, repoId, commitA).isEmpty());
    assertTrue(dao.findFile(repositoryId, repoId, commitA, "model.safetensors").isEmpty());
    assertTrue(dao.findApiCache(repositoryId, "api/models/" + repoId, "expand=true", "").isEmpty());
  }
}
