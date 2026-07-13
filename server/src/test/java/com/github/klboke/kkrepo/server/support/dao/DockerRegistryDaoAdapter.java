package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.BrowseImageRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.BrowseReferenceRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupManifestCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupPolicyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupTagCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.DeletedManifest;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Test-only base class for focused DockerRegistryDao fakes. */
public class DockerRegistryDaoAdapter implements DockerRegistryDao {
  public DockerRegistryDaoAdapter() {
  }

  public DockerRegistryDaoAdapter(Object ignored) {
  }

  public DockerRegistryDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public int countTags(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public DeletedManifest deleteManifest(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteTag(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<DockerManifestRecord> findBrowseManifestByReferencePath(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<DockerManifestRecord> findManifestByDigest(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<DockerManifestRecord> findManifestById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<DockerManifestRecord> findManifestByReference(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<DockerManifestRecord> findManifestByTag(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<Long, DockerManifestRecord> findManifestsByAssetIds(Collection<Long> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public OptionalLong findUnreferencedBlobAssetIdForCleanup(long arg0, long arg1, int arg2, Instant arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean imageExists(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean imageReferencesDigest(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BrowseImageRow> listBrowseImages(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BrowseReferenceRow> listBrowseReferences(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listCatalog(long arg0, String arg1, int arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<CleanupPolicyRecord> listCleanupPolicies(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<CleanupManifestCandidate> listManifestCleanupCandidates(long arg0, boolean arg1, Instant arg2, Instant arg3, int arg4) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<DockerManifestReferenceRecord> listReferences(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<DockerManifestRecord> listReferrers(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<CleanupTagCandidate> listTagCleanupCandidates(long arg0, int arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listTags(long arg0, String arg1, String arg2, int arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<DockerTagRecord> listTagsForManifest(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean referencedDigestExists(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceManifestReferences(long arg0, List<DockerManifestReferenceRecord> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean tagExists(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public DockerManifestRecord upsertManifest(DockerManifestRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertTag(DockerTagRecord arg0) {
    throw new UnsupportedOperationException();
  }
}
