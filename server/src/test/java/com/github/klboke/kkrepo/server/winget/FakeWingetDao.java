package com.github.klboke.kkrepo.server.winget;

import com.github.klboke.kkrepo.persistence.jdbc.api.WingetDao;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

/** Minimal in-memory DAO that preserves the production conflict and filtering semantics. */
final class FakeWingetDao implements WingetDao {
  final List<ManifestRecord> rows = new ArrayList<>();
  private long nextId = 1;

  @Override
  public Optional<ManifestRecord> find(
      long repositoryId, String packageIdentifier, String packageVersion, String channel) {
    return rows.stream().filter(row -> row.repositoryId() == repositoryId
        && row.packageIdentifier().equals(packageIdentifier)
        && row.packageVersion().equals(packageVersion)
        && row.channel().equals(channel == null ? "" : channel)).findFirst();
  }

  @Override
  public List<ManifestRecord> listByPackage(long repositoryId, String packageIdentifier) {
    return rows.stream().filter(row -> row.repositoryId() == repositoryId
        && row.packageIdentifier().equals(packageIdentifier)).toList();
  }

  @Override
  public List<ManifestRecord> listSummaries(long repositoryId, List<String> packageIdentifiers) {
    Set<String> ids = new HashSet<>(packageIdentifiers);
    return rows.stream().filter(row -> row.repositoryId() == repositoryId
        && ids.contains(row.packageIdentifier())).toList();
  }

  @Override
  public List<String> searchPackageIdentifiers(
      long repositoryId, List<String> terms, int limit, int offset) {
    List<ManifestRecord> matching = rows.stream()
        .filter(row -> row.repositoryId() == repositoryId)
        .filter(row -> terms.stream().allMatch(term ->
            row.searchText().toLowerCase().contains(term.toLowerCase())))
        .sorted(Comparator.comparing(ManifestRecord::updatedAt).reversed()
            .thenComparing(ManifestRecord::packageIdentifier))
        .toList();
    return matching.stream().map(ManifestRecord::packageIdentifier).distinct()
        .skip(Math.max(0, offset)).limit(Math.max(1, limit)).toList();
  }

  @Override
  public void insert(ManifestRecord record) {
    if (find(record.repositoryId(), record.packageIdentifier(), record.packageVersion(),
        record.channel()).isPresent()) {
      throw new DuplicateKeyException("duplicate winget manifest");
    }
    rows.add(new ManifestRecord(nextId++, record.repositoryId(), record.packageIdentifier(),
        record.packageVersion(), record.channel(), record.defaultLocale(), record.packageName(),
        record.publisher(), record.moniker(), record.searchText(), record.summaryJson(),
        record.manifestJson(), record.manifestSha256(), Instant.now()));
  }

  @Override
  public int deleteVersion(
      long repositoryId, String packageIdentifier, String packageVersion, String channel) {
    int before = rows.size();
    rows.removeIf(row -> row.repositoryId() == repositoryId
        && row.packageIdentifier().equals(packageIdentifier)
        && row.packageVersion().equals(packageVersion)
        && (channel == null || channel.isBlank() || row.channel().equals(channel)));
    return before - rows.size();
  }

  @Override
  public int deletePackage(long repositoryId, String packageIdentifier) {
    int before = rows.size();
    rows.removeIf(row -> row.repositoryId() == repositoryId
        && row.packageIdentifier().equals(packageIdentifier));
    return before - rows.size();
  }

  @Override
  public int deleteByRepository(long repositoryId) {
    int before = rows.size();
    rows.removeIf(row -> row.repositoryId() == repositoryId);
    return before - rows.size();
  }

  @Override
  public long countVersions(long repositoryId) {
    return rows.stream().filter(row -> row.repositoryId() == repositoryId).count();
  }
}
