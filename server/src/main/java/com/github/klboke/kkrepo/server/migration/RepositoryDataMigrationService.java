package com.github.klboke.kkrepo.server.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipes;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.persistence.mysql.dao.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao.MigrationJobProgress;
import com.github.klboke.kkrepo.persistence.mysql.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryDataMigrationRepositoryRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RepositoryDataMigrationService {
  private static final String DEFAULT_NEXUS_VERSION = "3.29.2-02";
  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final int DEFAULT_CONCURRENCY = 8;
  private static final boolean DEFAULT_CHECKSUM_VALIDATION = true;
  private static final String MODE_HOSTED = "hosted";
  private static final String MODE_PROXY_BACKUP = "proxy-backup";

  private final ObjectMapper objectMapper;
  private final MigrationJobDao migrationJobDao;
  private final RepositoryDataMigrationDao migrationDao;
  private final RepositoryDao repositoryDao;
  private final TransactionTemplate transactionTemplate;

  RepositoryDataMigrationService(
      ObjectMapper objectMapper,
      MigrationJobDao migrationJobDao,
      RepositoryDataMigrationDao migrationDao,
      RepositoryDao repositoryDao,
      PlatformTransactionManager transactionManager) {
    this.objectMapper = objectMapper;
    this.migrationJobDao = migrationJobDao;
    this.migrationDao = migrationDao;
    this.repositoryDao = repositoryDao;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  RepositoryDataMigrationStatus start(RepositoryDataMigrationRequest request) throws Exception {
    int pageSize = clamp(request.pageSize(), DEFAULT_PAGE_SIZE, 100, 5000);
    int concurrency = clamp(request.concurrency(), DEFAULT_CONCURRENCY, 1, 64);
    boolean checksumValidation = request.checksumValidation() == null
        ? DEFAULT_CHECKSUM_VALIDATION
        : request.checksumValidation();
    NexusInventory inventory = new NexusRestClient(
        request.sourceBaseUrl(),
        request.sourceUsername(),
        request.sourcePassword(),
        objectMapper).readInventory();
    List<SourceRepository> sourceRepositories = sourceRepositories(
        inventory,
        request.repositories(),
        request.backupProxyRepositories());
    if (sourceRepositories.isEmpty()) {
      throw new IllegalArgumentException("No supported source repositories matched the request");
    }
    Map<String, RepositoryRecord> targets = targetRepositories(sourceRepositories);
    List<String> missingTargets = sourceRepositories.stream()
        .filter(source -> !targets.containsKey(source.name()))
        .map(SourceRepository::name)
        .toList();
    if (!missingTargets.isEmpty()) {
      throw new IllegalArgumentException("Target repositories must exist before data migration: " + missingTargets);
    }

    long jobId = transactionTemplate.execute(status -> {
      long createdJobId = migrationJobDao.create(
          defaultString(request.sourceNexusVersion(), DEFAULT_NEXUS_VERSION),
          request.sourceBaseUrl(),
          jobOptions(request, pageSize, concurrency, checksumValidation, sourceRepositories));
      for (SourceRepository source : sourceRepositories) {
        RepositoryRecord target = targets.get(source.name());
        migrationDao.createRepositoryJob(
            createdJobId,
            source.name(),
            target.name(),
            target.id(),
            target.format(),
            pageSize,
            repositoryOptions(request, source, target));
      }
      migrationDao.updateMigrationJobSummary(
          createdJobId,
          "running",
          Map.of(
              "jobId", createdJobId,
              "status", "running",
              "repositories", sourceRepositories.size(),
              "discoveredAssets", 0,
              "totalAssets", 0,
              "migratedAssets", 0,
              "pendingAssets", 0,
              "failedAssets", 0));
      return createdJobId;
    });
    return status(jobId);
  }

  RepositoryDataMigrationStatus status(long jobId) {
    MigrationJobRecord job = migrationJobDao.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("migration job not found: " + jobId));
    List<RepositoryDataMigrationRepositoryRecord> repositories = migrationDao.listRepositories(jobId);
    MigrationJobProgress progress = migrationDao.jobProgress(jobId);
    return new RepositoryDataMigrationStatus(
        job.id(),
        job.status(),
        job.startedAt(),
        job.sourceNexusVersion(),
        job.sourceDataPath(),
        progress.repositories(),
        progress.discoveredAssets(),
        progress.totalAssets(),
        progress.migratedAssets(),
        progress.failedAssets(),
        progress.pendingAssets(),
        packageMigrationEnabled(job),
        progress.active(),
        progress.failedRepositories(),
        repositories.stream()
            .map(RepositoryDataMigrationService::repositoryStatus)
            .toList());
  }

  List<RepositoryDataMigrationStatus> listJobs(int limit) {
    return migrationDao.listRepositoryDataJobs(limit).stream()
        .map(job -> status(job.id()))
        .toList();
  }

  RepositoryDataMigrationStatus enablePackageMigration(long jobId) {
    migrationJobDao.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("migration job not found: " + jobId));
    validatePackageMigrationTargets(jobId);
    migrationDao.setPackageMigrationEnabled(jobId, true);
    refreshJobSummary(jobId);
    return status(jobId);
  }

  RepositoryDataMigrationStatus retryFailedPackages(long jobId) {
    migrationJobDao.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("migration job not found: " + jobId));
    validatePackageMigrationTargets(jobId);
    migrationDao.setPackageMigrationEnabled(jobId, true);
    migrationDao.retryFailedAssets(jobId);
    refreshJobSummary(jobId);
    return status(jobId);
  }

  void refreshJobSummary(long jobId) {
    RepositoryDataMigrationStatus status = status(jobId);
    String jobStatus = status.active()
        ? "running"
        : status.failedRepositories() ? "finished_with_failures" : "finished";
    migrationDao.updateMigrationJobSummary(jobId, jobStatus, status.toSummary(jobStatus));
  }

  private List<SourceRepository> sourceRepositories(
      NexusInventory inventory,
      List<String> requestedRepositories,
      List<String> backupProxyRepositories) {
    LinkedHashMap<String, SourceRepository> sourcesByName = new LinkedHashMap<>();
    for (RepositoryDocument document : inventory.repositories()) {
      SourceRepository source = sourceRepository(document);
      if (source != null) {
        sourcesByName.put(source.name(), source);
      }
    }
    LinkedHashMap<String, SourceRepository> selected = new LinkedHashMap<>();
    List<String> requestedHosted = normalizeNames(requestedRepositories);
    if (requestedHosted.isEmpty()) {
      sourcesByName.values().stream()
          .filter(source -> source.type() == RepositoryType.HOSTED && source.supported())
          .sorted(Comparator.comparing(SourceRepository::name))
          .map(source -> source.withMigrationMode(MODE_HOSTED))
          .forEach(source -> selected.put(source.name(), source));
    } else {
      List<String> invalid = new ArrayList<>();
      for (String name : requestedHosted) {
        SourceRepository source = sourcesByName.get(name);
        if (source == null) {
          invalid.add(name + " (not found)");
        } else if (source.type() != RepositoryType.HOSTED) {
          invalid.add(name + " (" + source.type().name().toLowerCase(Locale.ROOT) + ")");
        } else if (!source.supported()) {
          invalid.add(name + " (unsupported format " + source.format().id() + ")");
        } else {
          selected.put(source.name(), source.withMigrationMode(MODE_HOSTED));
        }
      }
      if (!invalid.isEmpty()) {
        throw new IllegalArgumentException("Repositories are invalid for hosted data migration: " + invalid);
      }
    }

    List<String> invalid = new ArrayList<>();
    for (String name : normalizeNames(backupProxyRepositories)) {
      SourceRepository source = sourcesByName.get(name);
      if (source == null) {
        invalid.add(name + " (not found)");
      } else if (source.type() != RepositoryType.PROXY) {
        invalid.add(name + " (" + source.type().name().toLowerCase(Locale.ROOT) + ")");
      } else if (!source.supported()) {
        invalid.add(name + " (unsupported format " + source.format().id() + ")");
      } else {
        selected.put(source.name(), source.withMigrationMode(MODE_PROXY_BACKUP));
      }
    }
    if (!invalid.isEmpty()) {
      throw new IllegalArgumentException("Backup proxy repositories are invalid: " + invalid);
    }
    return List.copyOf(selected.values());
  }

  private Map<String, RepositoryRecord> targetRepositories(List<SourceRepository> sourceRepositories) {
    LinkedHashMap<String, RepositoryRecord> records = new LinkedHashMap<>();
    for (SourceRepository source : sourceRepositories) {
      repositoryDao.findByName(source.name())
          .filter(record -> validTargetType(source.migrationMode(), record.type()))
          .ifPresent(record -> records.put(source.name(), record));
    }
    return records;
  }

  private void validatePackageMigrationTargets(long jobId) {
    List<String> invalidTargets = migrationDao.listRepositories(jobId).stream()
        .filter(repository -> !validPackageMigrationTarget(repository))
        .map(repository -> repository.targetRepositoryName() + " (" + repository.format().id() + ")")
        .toList();
    if (!invalidTargets.isEmpty()) {
      throw new IllegalArgumentException(
          "Target repositories must exist before package migration: " + invalidTargets);
    }
  }

  private boolean validPackageMigrationTarget(RepositoryDataMigrationRepositoryRecord repository) {
    String migrationMode = string(repository.options() == null ? null : repository.options().get("migrationMode"));
    return repositoryDao.findByName(repository.targetRepositoryName())
        .filter(target -> validTargetType(migrationMode, target.type()))
        .filter(target -> target.id() != null && target.id().equals(repository.targetRepositoryId()))
        .filter(target -> target.format() == repository.format())
        .isPresent();
  }

  private static boolean validTargetType(String migrationMode, RepositoryType targetType) {
    if (MODE_PROXY_BACKUP.equals(migrationMode)) {
      return RepositoryType.PROXY.equals(targetType);
    }
    return RepositoryType.HOSTED.equals(targetType);
  }

  private Map<String, Object> repositoryOptions(
      RepositoryDataMigrationRequest request,
      SourceRepository source,
      RepositoryRecord target) {
    LinkedHashMap<String, Object> options = new LinkedHashMap<>();
    options.put("sourceRepository", source.raw());
    options.put("migrationMode", source.migrationMode());
    options.put("sourceType", source.type().name());
    options.put("targetType", target.type().name());
    if (request.metadataSince() != null) {
      options.put("metadataSince", request.metadataSince().toString());
    }
    return Map.copyOf(options);
  }

  private Map<String, Object> jobOptions(
      RepositoryDataMigrationRequest request,
      int pageSize,
      int concurrency,
      boolean checksumValidation,
      List<SourceRepository> repositories) {
    SecretCipher cipher = new SecretCipher(EncryptionSecrets.credentialSecret());
    LinkedHashMap<String, Object> options = new LinkedHashMap<>();
    options.put("scope", "repository-data");
    options.put("sourceBaseUrl", request.sourceBaseUrl());
    options.put("sourceUsername", request.sourceUsername());
    options.put("sourcePassword", cipher.encrypt(request.sourcePassword() == null ? "" : request.sourcePassword()));
    options.put("pageSize", pageSize);
    options.put("concurrency", concurrency);
    options.put("checksumValidation", checksumValidation);
    options.put("packageMigrationEnabled", false);
    options.put("repositories", repositories.stream().map(SourceRepository::name).toList());
    options.put("requestedRepositories", normalizeNames(request.repositories()));
    options.put("backupProxyRepositories", normalizeNames(request.backupProxyRepositories()));
    if (request.metadataSince() != null) {
      options.put("metadataSince", request.metadataSince().toString());
    }
    return Map.copyOf(options);
  }

  private static RepositoryDataMigrationRepositoryStatus repositoryStatus(
      RepositoryDataMigrationRepositoryRecord repository) {
    return new RepositoryDataMigrationRepositoryStatus(
        repository.id(),
        repository.sourceRepositoryName(),
        repository.targetRepositoryName(),
        repository.format().id(),
        repository.status(),
        repository.cursorPath(),
        repository.totalAssets(),
        repository.discoveredAssets(),
        repository.migratedAssets(),
        repository.failedAssets(),
        Math.max(0, repository.totalAssets() - repository.migratedAssets() - repository.failedAssets()),
        repository.lastError());
  }

  private static SourceRepository sourceRepository(RepositoryDocument document) {
    Map<String, Object> summary = document.summary();
    String name = string(summary.get("name"));
    RepositoryFormat format = format(string(summary.get("format")));
    RepositoryType type = type(string(summary.get("type")));
    if (name == null || format == null || type == null) {
      return null;
    }
    boolean supported = format != RepositoryFormat.CARGO
        && RepositoryRecipes.byName(format.id() + "-" + type.name().toLowerCase(Locale.ROOT)).isPresent();
    return new SourceRepository(name, format, type, supported, summary, null);
  }

  private static int clamp(Integer requested, int fallback, int min, int max) {
    int value = requested == null ? fallback : requested;
    return Math.max(min, Math.min(max, value));
  }

  private static RepositoryFormat format(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("MAVEN".equals(normalized)) {
      normalized = "MAVEN2";
    }
    try {
      return RepositoryFormat.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static RepositoryType type(String value) {
    if (value == null) {
      return null;
    }
    try {
      return RepositoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static boolean packageMigrationEnabled(MigrationJobRecord job) {
    Object value = job.options() == null ? null : job.options().get("packageMigrationEnabled");
    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
  }

  record RepositoryDataMigrationRequest(
      String sourceBaseUrl,
      String sourceUsername,
      String sourcePassword,
      String sourceNexusVersion,
      Integer pageSize,
      Integer concurrency,
      Boolean checksumValidation,
      Instant metadataSince,
      List<String> repositories,
      List<String> backupProxyRepositories) {
  }

  record RepositoryDataMigrationStatus(
      long jobId,
      String status,
      Instant startedAt,
      String sourceNexusVersion,
      String sourceBaseUrl,
      int repositories,
      long discoveredAssets,
      long totalAssets,
      long migratedAssets,
      long failedAssets,
      long pendingAssets,
      boolean packageMigrationEnabled,
      boolean active,
      boolean failedRepositories,
      List<RepositoryDataMigrationRepositoryStatus> repositoryJobs) {

    Map<String, Object> toSummary(String effectiveStatus) {
      LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
      summary.put("jobId", jobId);
      summary.put("status", effectiveStatus);
      summary.put("startedAt", startedAt);
      summary.put("repositories", repositories);
      summary.put("discoveredAssets", discoveredAssets);
      summary.put("totalAssets", totalAssets);
      summary.put("migratedAssets", migratedAssets);
      summary.put("failedAssets", failedAssets);
      summary.put("pendingAssets", pendingAssets);
      summary.put("packageMigrationEnabled", packageMigrationEnabled);
      summary.put("active", active);
      summary.put("failedRepositories", failedRepositories);
      return Map.copyOf(summary);
    }
  }

  record RepositoryDataMigrationRepositoryStatus(
      long id,
      String sourceRepositoryName,
      String targetRepositoryName,
      String format,
      String status,
      String cursorPath,
      long totalAssets,
      long discoveredAssets,
      long migratedAssets,
      long failedAssets,
      long pendingAssets,
      String lastError) {
  }

  private record SourceRepository(
      String name,
      RepositoryFormat format,
      RepositoryType type,
      boolean supported,
      Map<String, Object> raw,
      String migrationMode) {

    private SourceRepository withMigrationMode(String migrationMode) {
      return new SourceRepository(name, format, type, supported, raw, migrationMode);
    }
  }

  private static List<String> normalizeNames(List<String> names) {
    if (names == null || names.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String name : names) {
      String value = string(name);
      if (value != null) {
        normalized.add(value);
      }
    }
    return List.copyOf(normalized);
  }
}
