package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DockerUploadDao {
  void insertSession(DockerUploadSessionRecord record);

  Optional<DockerUploadSessionRecord> findSession(String uuid);

  Optional<DockerUploadSessionRecord> lockSession(String uuid);

  void appendChunk(
      String uuid,
      int chunkIndex,
      long startOffset,
      long endOffset,
      String blobRef,
      String objectKey,
      String sha256,
      long size,
      long nextOffset);

  List<DockerUploadChunkRecord> listChunks(String uuid);

  int nextChunkIndex(String uuid);

  void completeSession(String uuid, String expectedDigest, String digestAlgorithm);

  int cancelSession(String uuid);

  int expireSessions(Instant now);

  List<DockerUploadSessionRecord> claimTerminalSessions(
      Instant now,
      String owner,
      Instant lockedUntil,
      int batchSize);

  int deleteSession(String uuid);
}
