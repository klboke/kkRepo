package com.github.klboke.kkrepo.server.winget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.WingetDao;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetIndex;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetPackage;
import com.github.klboke.kkrepo.protocol.winget.WingetSearch;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.protocol.winget.WingetVersion;
import com.github.klboke.kkrepo.protocol.winget.WingetVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hosted WinGet manifest lifecycle: publication, replacement, deletion and search. */
@Service
public class WingetHostedService {
  private static final int SQL_PAGE_SIZE = 200;

  private final WingetDao dao;
  private final ObjectMapper mapper;
  private final WingetManifestParser parser = new WingetManifestParser();

  public WingetHostedService(WingetDao dao, ObjectMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  @Transactional
  public WingetManifest publish(RepositoryRuntime runtime, WingetManifest manifest) {
    for (WingetVersion version : manifest.versions()) {
      if (dao.find(runtime.id(), manifest.packageIdentifier(), version.packageVersion(),
          version.channel()).isPresent()) {
        throw conflict(manifest.packageIdentifier(), version);
      }
      try {
        dao.insert(record(runtime.id(), version));
      } catch (DuplicateKeyException race) {
        throw conflict(manifest.packageIdentifier(), version);
      }
    }
    return manifest;
  }

  @Transactional
  public WingetManifest replace(RepositoryRuntime runtime, WingetManifest manifest) {
    dao.deletePackage(runtime.id(), manifest.packageIdentifier());
    for (WingetVersion version : manifest.versions()) {
      dao.insert(record(runtime.id(), version));
    }
    return manifest;
  }

  /** Replaces the cached rows for one proxy package after a successful upstream read. */
  @Transactional
  public void cache(RepositoryRuntime runtime, WingetManifest manifest) {
    dao.deletePackage(runtime.id(), manifest.packageIdentifier());
    for (WingetVersion version : manifest.versions()) {
      dao.insert(record(runtime.id(), version));
    }
  }

  @Transactional
  public int delete(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel) {
    if (packageVersion == null || packageVersion.isBlank()) {
      return dao.deletePackage(runtime.id(), packageIdentifier);
    }
    return dao.deleteVersion(runtime.id(), packageIdentifier, packageVersion, channel);
  }

  public WingetManifest manifest(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel) {
    return manifestFromRows(packageIdentifier, dao.listByPackage(runtime.id(), packageIdentifier),
        packageVersion, channel);
  }

  public WingetSearchPage search(
      RepositoryRuntime runtime, WingetSearchRequest request, int offset, int limit) {
    List<WingetPackage> results = new ArrayList<>();
    List<String> terms = WingetSearch.prefilterTerms(request);
    int cursor = Math.max(0, offset);
    boolean exhausted = false;
    while (results.size() < limit) {
      List<String> identifiers = dao.searchPackageIdentifiers(runtime.id(), terms, SQL_PAGE_SIZE, cursor);
      if (identifiers.isEmpty()) {
        exhausted = true;
        break;
      }
      List<WingetIndex> indexes = new ArrayList<>();
      for (WingetDao.ManifestRecord row : dao.listSummaries(runtime.id(), identifiers)) {
        indexes.add(index(row));
      }
      List<WingetPackage> page = WingetSearch.search(indexes, request, limit - results.size());
      merge(results, page);
      cursor += identifiers.size();
      if (identifiers.size() < SQL_PAGE_SIZE) {
        exhausted = true;
        break;
      }
    }
    Integer next = !exhausted && results.size() >= limit ? cursor : null;
    return new WingetSearchPage(results, next, null);
  }

  /** Search limited to what is already stored, used as a proxy fallback when upstream is down. */
  public WingetSearchPage searchCached(
      RepositoryRuntime runtime, WingetSearchRequest request, int offset, int limit) {
    return search(runtime, request, offset, limit);
  }

  public WingetManifest manifestFromRows(
      String packageIdentifier, List<WingetDao.ManifestRecord> rows,
      String packageVersion, String channel) {
    List<WingetVersion> versions = new ArrayList<>();
    for (WingetDao.ManifestRecord row : rows) {
      if (!matches(row, packageVersion, channel)) continue;
      versions.add(version(row));
    }
    if (versions.isEmpty()) {
      throw WingetException.notFound("Package manifest not found: " + packageIdentifier);
    }
    versions.sort(Comparator
        .comparing(WingetVersion::packageVersion, WingetVersions::compare)
        .thenComparing(version -> version.index().channel()));
    return new WingetManifest(packageIdentifier, versions);
  }

  public WingetIndex index(WingetDao.ManifestRecord row) {
    return readIndex(row.summaryJson());
  }

  public WingetVersion version(WingetDao.ManifestRecord row) {
    try {
      JsonNode manifest = mapper.readTree(row.manifestJson());
      return new WingetVersion(index(row), manifest);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Persisted WinGet manifest is not valid JSON", invalid);
    }
  }

  private WingetIndex readIndex(String summaryJson) {
    try {
      return WingetManifestParser.readIndex(mapper.readTree(summaryJson), mapper);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Persisted WinGet index is not valid JSON", invalid);
    }
  }

  private WingetDao.ManifestRecord record(long repositoryId, WingetVersion version) {
    WingetIndex index = version.index();
    try {
      byte[] manifestBytes = mapper.writeValueAsBytes(version.manifest());
      String sha256 = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(manifestBytes));
      return new WingetDao.ManifestRecord(
          0L, repositoryId, index.packageIdentifier(), index.packageVersion(), index.channel(),
          index.defaultLocale(), index.packageName(), index.publisher(), index.moniker(),
          index.searchText(), mapper.writeValueAsString(index), mapper.writeValueAsString(version.manifest()),
          sha256, java.time.Instant.now());
    } catch (JsonProcessingException | NoSuchAlgorithmException invalid) {
      throw new IllegalStateException("Unable to serialize WinGet manifest", invalid);
    }
  }

  private static boolean matches(
      WingetDao.ManifestRecord row, String packageVersion, String channel) {
    return (packageVersion == null || packageVersion.isBlank()
        || row.packageVersion().equalsIgnoreCase(packageVersion))
        && (channel == null || channel.isBlank() || row.channel().equalsIgnoreCase(channel));
  }

  private static void merge(List<WingetPackage> target, List<WingetPackage> page) {
    for (WingetPackage candidate : page) {
      if (target.stream().noneMatch(existing ->
          existing.packageIdentifier().equals(candidate.packageIdentifier()))) {
        target.add(candidate);
      }
    }
  }

  private static WingetException conflict(String identifier, WingetVersion version) {
    return WingetException.conflict("Package " + identifier + " version "
        + version.packageVersion() + " (channel '" + version.channel() + "') already exists");
  }
}
