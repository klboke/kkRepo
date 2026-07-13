package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public interface DockerRegistryDao {
  Optional<DockerManifestRecord> findManifestByDigest(
      long repositoryId, String imageName, String digest);

  Optional<DockerManifestRecord> findManifestByTag(
      long repositoryId, String imageName, String tag);

  Optional<DockerManifestRecord> findManifestByReference(
      long repositoryId, String imageName, String reference);

  Map<Long, DockerManifestRecord> findManifestsByAssetIds(Collection<Long> assetIds);

  DockerManifestRecord upsertManifest(DockerManifestRecord record);

  Optional<DockerManifestRecord> findManifestById(long id);

  void replaceManifestReferences(long manifestId, List<DockerManifestReferenceRecord> references);

  List<DockerManifestReferenceRecord> listReferences(long manifestId);

  List<DockerManifestRecord> listReferrers(
      long repositoryId, String subjectDigest, String artifactType);

  void upsertTag(DockerTagRecord record);

  List<String> listTags(long repositoryId, String imageName, String last, int limit);

  boolean imageExists(long repositoryId, String imageName);

  int countTags(long repositoryId, String imageName);

  boolean tagExists(long repositoryId, String imageName, String tag);

  List<DockerTagRecord> listTagsForManifest(long manifestId);

  int deleteTag(long repositoryId, String imageName, String tag);

  DeletedManifest deleteManifest(long repositoryId, String imageName, String digest);

  boolean referencedDigestExists(long repositoryId, String imageName, String digest);

  boolean imageReferencesDigest(long repositoryId, String imageName, String digest);

  OptionalLong findUnreferencedBlobAssetIdForCleanup(
      long repositoryId, long afterAssetId, int maxCandidates, Instant updatedBefore);

  List<CleanupPolicyRecord> listCleanupPolicies(long repositoryId);

  List<CleanupTagCandidate> listTagCleanupCandidates(long repositoryId, int limit);

  List<CleanupManifestCandidate> listManifestCleanupCandidates(
      long repositoryId,
      boolean untaggedOnly,
      Instant lastDownloadedBefore,
      Instant lastUpdatedBefore,
      int limit);

  List<String> listCatalog(long repositoryId, String last, int limit);

  List<BrowseImageRow> listBrowseImages(long repositoryId, String parentPath);

  List<BrowseReferenceRow> listBrowseReferences(long repositoryId, String imageName);

  Optional<DockerManifestRecord> findBrowseManifestByReferencePath(
      long repositoryId, String path);

  record DeletedManifest(int deleted, Long assetId, Long assetBlobId) {
    public static DeletedManifest notFound() {
      return new DeletedManifest(0, null, null);
    }
  }

  record BrowseImageRow(
      String imageName,
      Instant updatedAt,
      Long size,
      String mediaType) {
  }

  record BrowseReferenceRow(
      String reference,
      String digest,
      long assetId,
      Long size,
      String mediaType,
      Instant updatedAt) {
  }

  record CleanupPolicyRecord(long id, String name, Map<String, Object> criteria) {
  }

  record CleanupTagCandidate(String imageName, String tag) {
  }

  record CleanupManifestCandidate(String imageName, String digest) {
  }
}
