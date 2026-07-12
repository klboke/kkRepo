package com.github.klboke.kkrepo.server.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryAssetMetadata;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryAssetPage;
import com.github.klboke.kkrepo.persistence.mysql.dao.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao.AssetClaim;
import com.github.klboke.kkrepo.persistence.mysql.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryDataMigrationRepositoryRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class RepositoryDataMigrationWorker {
  private static final Logger log = LoggerFactory.getLogger(RepositoryDataMigrationWorker.class);
  private static final int DEFAULT_CONCURRENCY = 8;
  private static final int MAX_CONCURRENCY = 64;
  private static final int DISCOVERY_PAGES_PER_RUN = 100;
  private static final int MAX_ATTEMPTS = 5;
  private static final long CLAIM_RETRY_SECONDS = 300;

  private final ObjectMapper objectMapper;
  private final MigrationJobDao migrationJobDao;
  private final RepositoryDataMigrationDao migrationDao;
  private final RepositoryDataMigrationService migrationService;
  private final RepositoryDataMigrationWriter writer;
  private final TransactionTemplate transactionTemplate;
  private final ExecutorService executor;
  private final ExecutorService triggerExecutor;
  private final Set<String> runningTriggers = ConcurrentHashMap.newKeySet();

  RepositoryDataMigrationWorker(
      ObjectMapper objectMapper,
      MigrationJobDao migrationJobDao,
      RepositoryDataMigrationDao migrationDao,
      RepositoryDataMigrationService migrationService,
      RepositoryDataMigrationWriter writer,
      PlatformTransactionManager transactionManager) {
    this.objectMapper = objectMapper;
    this.migrationJobDao = migrationJobDao;
    this.migrationDao = migrationDao;
    this.migrationService = migrationService;
    this.writer = writer;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.executor = Executors.newFixedThreadPool(MAX_CONCURRENCY, threadFactory("repository-data-migration-"));
    this.triggerExecutor = Executors.newCachedThreadPool(threadFactory("repository-data-migration-trigger-"));
  }

  void triggerMetadata(long migrationJobId) {
    trigger("metadata:" + migrationJobId, () -> {
      while (discoverOneRepository(migrationJobId)) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
      }
      migrationService.refreshJobSummary(migrationJobId);
    });
  }

  void triggerPackages(long migrationJobId) {
    trigger("packages:" + migrationJobId, () -> {
      while (migrateAssetBatch(migrationJobId)) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
      }
      migrationService.refreshJobSummary(migrationJobId);
    });
  }

  private void trigger(String key, Runnable task) {
    if (!runningTriggers.add(key)) {
      log.info("repository data migration trigger already running: {}", key);
      return;
    }
    triggerExecutor.submit(() -> {
      try {
        task.run();
      } finally {
        runningTriggers.remove(key);
      }
    });
  }

  private boolean discoverOneRepository(Long migrationJobId) {
    Instant retryBefore = Instant.now().minusSeconds(CLAIM_RETRY_SECONDS);
    RepositoryDataMigrationRepositoryRecord repositoryJob = transactionTemplate.execute(status ->
        migrationDao.claimRepositoryForDiscovery(migrationJobId, retryBefore).orElse(null));
    if (repositoryJob == null) {
      return false;
    }
    MigrationJobRecord job = migrationJobDao.findById(repositoryJob.migrationJobId())
        .orElseThrow(() -> new IllegalStateException("migration job not found: " + repositoryJob.migrationJobId()));
    SourceAccess source = sourceAccess(job);
    String cursor = repositoryJob.cursorPath();
    Instant metadataSince = metadataSince(repositoryJob);
    int pages = 0;
    try (NexusRestClient.RepositoryDataScriptSession script = source.client().openRepositoryDataScript()) {
      boolean complete;
      do {
        RepositoryAssetPage page = script.readPage(
            repositoryJob.sourceRepositoryName(),
            repositoryJob.format().id(),
            source.metadataEngine(),
            cursor,
            repositoryJob.pageSize(),
            metadataSince);
        complete = processDiscoveryPage(repositoryJob, page, metadataSince);
        cursor = page.nextAfterPath();
        pages++;
      } while (!complete && pages < DISCOVERY_PAGES_PER_RUN);
      migrationService.refreshJobSummary(repositoryJob.migrationJobId());
      return true;
    } catch (Exception e) {
      log.warn("repository data discovery failed for repo={}", repositoryJob.sourceRepositoryName(), e);
      transactionTemplate.executeWithoutResult(status ->
          migrationDao.markDiscoveryFailure(repositoryJob.id(), errorSummary(e)));
      migrationService.refreshJobSummary(repositoryJob.migrationJobId());
      return true;
    }
  }

  private boolean processDiscoveryPage(
      RepositoryDataMigrationRepositoryRecord repositoryJob,
      RepositoryAssetPage page,
      Instant metadataSince) {
    List<RepositoryDataMigrationAssetRecord> records = page.assets().stream()
        .filter(asset -> changedSince(asset, metadataSince))
        .filter(asset -> RepositoryDataMigrationPaths.shouldDiscoverAsset(repositoryJob.format(), asset.path()))
        .filter(asset -> shouldMigrateSourceAsset(repositoryJob.format(), asset.path()))
        .map(asset -> assetRecord(repositoryJob, asset))
        .toList();
    boolean complete = page.complete();
    String nextCursor = page.nextAfterPath();
    transactionTemplate.executeWithoutResult(status -> {
      Map<java.nio.ByteBuffer, RepositoryDataMigrationDao.TargetAssetRef> existingTargets =
          migrationDao.findTargetAssetsByPathHash(
              repositoryJob.targetRepositoryId(),
              records.stream().map(RepositoryDataMigrationAssetRecord::sourcePathHash).toList());
      migrationDao.upsertDiscoveredAssets(repositoryJob.id(), records, existingTargets);
      migrationDao.finishDiscoveryPage(repositoryJob.id(), nextCursor, complete);
    });
    if (!page.warnings().isEmpty()) {
      log.warn("repository data discovery warnings for repo={}: {}",
          repositoryJob.sourceRepositoryName(), page.warnings());
    }
    return complete;
  }

  private boolean migrateAssetBatch(Long migrationJobId) {
    Instant retryBefore = Instant.now().minusSeconds(CLAIM_RETRY_SECONDS);
    int concurrency = packageConcurrency(migrationJobId);
    List<AssetClaim> claims = transactionTemplate.execute(status ->
        migrationDao.claimAssetsForMigration(migrationJobId, concurrency, MAX_ATTEMPTS, retryBefore));
    if (claims == null || claims.isEmpty()) {
      return false;
    }
    Map<Long, NexusRestClient> clients = new ConcurrentHashMap<>();
    List<Future<?>> futures = new ArrayList<>(claims.size());
    for (AssetClaim claim : claims) {
      futures.add(executor.submit(() -> {
        SourceAccess source = sourceAccess(claim);
        NexusRestClient client = clients.computeIfAbsent(claim.migrationJobId(), ignored -> source.client());
        migrateOne(claim, client, source.checksumValidation());
      }));
    }
    boolean interrupted = false;
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        interrupted = true;
        break;
      } catch (Exception e) {
        log.warn("repository data migration task failed after marking row state", e);
      }
    }
    BatchProgressTargets progressTargets = batchProgressTargets(claims);
    for (Long repositoryJobId : progressTargets.repositoryJobIds()) {
      migrationDao.refreshRepositoryProgress(repositoryJobId);
    }
    for (Long jobId : progressTargets.jobIds()) {
      migrationService.refreshJobSummary(jobId);
    }
    return !interrupted;
  }

  static BatchProgressTargets batchProgressTargets(List<AssetClaim> claims) {
    LinkedHashSet<Long> repositoryJobIds = new LinkedHashSet<>();
    LinkedHashSet<Long> jobIds = new LinkedHashSet<>();
    for (AssetClaim claim : claims) {
      repositoryJobIds.add(claim.asset().repositoryJobId());
      jobIds.add(claim.migrationJobId());
    }
    return new BatchProgressTargets(List.copyOf(repositoryJobIds), List.copyOf(jobIds));
  }

  private void migrateOne(AssetClaim claim, NexusRestClient client, boolean checksumValidation) {
    try {
      if (!shouldMigrateSourceAsset(claim.repositoryFormat(), claim.asset().sourcePath())) {
        migrationDao.markAssetMigrated(
            claim.asset().id(),
            claim.asset().repositoryJobId(),
            null,
            null,
            null);
        return;
      }
      HttpResponse<InputStream> response = client.getRepositoryAsset(
          claim.sourceRepositoryName(),
          claim.asset().sourcePath());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        closeQuietly(response.body());
        throw new IOException("Source asset returned HTTP " + response.statusCode()
            + " for " + claim.sourceRepositoryName() + "/" + claim.asset().sourcePath());
      }
      String contentType = response.headers().firstValue("Content-Type").orElse(claim.asset().contentType());
      RepositoryDataMigrationWriter.WriteResult result = writer.write(
          claim.targetRepositoryId(),
          claim.asset(),
          response.body(),
          contentType,
          checksumValidation && shouldValidateDownloadedSize(claim));
      migrationDao.markAssetMigrated(
          claim.asset().id(),
          claim.asset().repositoryJobId(),
          result.componentId(),
          result.assetId(),
          result.assetBlobId());
    } catch (Exception e) {
      log.warn("repository data asset migration failed for repo={} path={}",
          claim.sourceRepositoryName(), claim.asset().sourcePath(), e);
      migrationDao.markAssetFailed(
          claim.asset().id(),
          claim.asset().repositoryJobId(),
          MAX_ATTEMPTS,
          errorSummary(e));
    }
  }

  private int packageConcurrency(Long migrationJobId) {
    if (migrationJobId == null) {
      return DEFAULT_CONCURRENCY;
    }
    return migrationJobDao.findById(migrationJobId)
        .map(job -> intOption(job.options().get("concurrency"), DEFAULT_CONCURRENCY, 1, MAX_CONCURRENCY))
        .orElse(DEFAULT_CONCURRENCY);
  }

  static boolean shouldValidateDownloadedSize(AssetClaim claim) {
    if (claim.repositoryFormat() == RepositoryFormat.NPM
        && !claim.asset().sourcePath().toLowerCase().endsWith(".tgz")) {
      return false;
    }
    if (claim.repositoryFormat() == RepositoryFormat.COMPOSER
        && isComposerRootMetadata(claim.asset().sourcePath())) {
      // Nexus stores the upstream packages.json blob, but serves a generated capability document
      // whose metadata-url points back to the Nexus repository. The public representation is the
      // content kkrepo must migrate, so its length is intentionally different from blob_size.
      return false;
    }
    if (claim.repositoryFormat() == RepositoryFormat.RUBYGEMS
        && isRubygemsDependencyIndex(claim.asset().sourcePath())) {
      return false;
    }
    return true;
  }

  private static boolean isComposerRootMetadata(String path) {
    if (path == null) {
      return false;
    }
    String normalized = path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return "packages.json".equals(normalized);
  }

  static boolean shouldMigrateSourceAsset(RepositoryFormat format, String path) {
    if (format == RepositoryFormat.CARGO && isCargoDynamicConfig(path)) {
      return false;
    }
    if (format == RepositoryFormat.PUB && !RepositoryDataMigrationPaths.shouldDiscoverAsset(format, path)) {
      return false;
    }
    return true;
  }

  private static boolean isCargoDynamicConfig(String path) {
    if (path == null) {
      return false;
    }
    String normalized = path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return "config.json".equals(normalized);
  }

  private static boolean isRubygemsDependencyIndex(String path) {
    if (path == null) {
      return false;
    }
    String normalized = path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized.startsWith("dependencies/") && normalized.endsWith(".ruby");
  }

  private static boolean changedSince(RepositoryAssetMetadata asset, Instant since) {
    if (since == null) {
      return true;
    }
    Instant blobUpdated = instant(asset.blobUpdated());
    if (blobUpdated != null && !blobUpdated.isBefore(since)) {
      return true;
    }
    Instant blobCreated = instant(asset.blobCreated());
    if (blobCreated != null && !blobCreated.isBefore(since)) {
      return true;
    }
    if (blobUpdated == null && blobCreated == null) {
      Instant lastUpdated = instant(asset.lastUpdated());
      return lastUpdated != null && !lastUpdated.isBefore(since);
    }
    return false;
  }

  private SourceAccess sourceAccess(MigrationJobRecord job) {
    return sourceAccess(job.options(), job.sourceDataPath());
  }

  private SourceAccess sourceAccess(AssetClaim claim) {
    return sourceAccess(claim.jobOptions(), claim.sourceBaseUrl());
  }

  private SourceAccess sourceAccess(Map<String, Object> options, String fallbackBaseUrl) {
    String sourceBaseUrl = firstNonBlank(string(options.get("sourceBaseUrl")), fallbackBaseUrl);
    String sourceUsername = string(options.get("sourceUsername"));
    String encryptedPassword = string(options.get("sourcePassword"));
    String sourcePassword = new SecretCipher(EncryptionSecrets.credentialSecret()).decrypt(encryptedPassword);
    boolean checksumValidation = bool(options.get("checksumValidation"), true);
    return new SourceAccess(
        new NexusRestClient(sourceBaseUrl, sourceUsername, sourcePassword, objectMapper),
        metadataEngine(options),
        checksumValidation);
  }

  private static String metadataEngine(Map<String, Object> options) {
    if (options == null) {
      return "UNKNOWN";
    }
    Object sourceProfile = options.get("sourceProfile");
    if (sourceProfile instanceof Map<?, ?> profile) {
      Object engine = profile.get("metadataEngine");
      if (engine != null && !String.valueOf(engine).isBlank()) {
        return String.valueOf(engine);
      }
    }
    Object migrationPlan = options.get("migrationPlan");
    if (migrationPlan instanceof Map<?, ?> plan) {
      Object adapter = plan.get("adapter");
      if (adapter != null) {
        String value = String.valueOf(adapter);
        if (value.contains("OrientDb")) {
          return "ORIENTDB";
        }
        if (value.contains("DatastoreH2")) {
          return "DATASTORE_H2";
        }
        if (value.contains("DatastorePostgresql")) {
          return "DATASTORE_POSTGRESQL";
        }
      }
    }
    return "UNKNOWN";
  }

  private static RepositoryDataMigrationAssetRecord assetRecord(
      RepositoryDataMigrationRepositoryRecord repositoryJob,
      RepositoryAssetMetadata source) {
    Map<String, Object> metadata = metadata(repositoryJob, source);
    return new RepositoryDataMigrationAssetRecord(
        null,
        repositoryJob.id(),
        source.assetId(),
        source.componentId(),
        source.path(),
        HashColumns.pathHash(source.path()),
        repositoryJob.format(),
        blankToNull(source.namespace()),
        blankToNull(source.name()),
        blankToNull(source.version()),
        blankToNull(source.assetKind()),
        blankToNull(source.contentType()),
        source.size(),
        blankToNull(source.sourceBlobRef()),
        instant(source.lastUpdated()),
        instant(source.lastDownloaded()),
        instant(source.blobCreated()),
        instant(source.blobUpdated()),
        blankToNull(source.createdBy()),
        blankToNull(source.createdByIp()),
        RepositoryDataMigrationDao.ASSET_PENDING,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        metadata,
        null);
  }

  private static Map<String, Object> metadata(
      RepositoryDataMigrationRepositoryRecord repositoryJob,
      RepositoryAssetMetadata source) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    putIfPresent(metadata, "repositoryName", source.repositoryName());
    putIfPresent(metadata, "format", source.format());
    putIfPresent(metadata, "migrationMode", optionString(repositoryJob, "migrationMode"));
    putIfPresent(metadata, "sourceRepositoryType", optionString(repositoryJob, "sourceType"));
    putIfPresent(metadata, "targetRepositoryType", optionString(repositoryJob, "targetType"));
    putIfPresent(metadata, "attributes", source.attributes());
    putIfPresent(metadata, "componentAttributes", source.componentAttributes());
    return Map.copyOf(metadata);
  }

  private static Instant metadataSince(RepositoryDataMigrationRepositoryRecord repositoryJob) {
    return instant(optionString(repositoryJob, "metadataSince"));
  }

  private static String optionString(RepositoryDataMigrationRepositoryRecord repositoryJob, String key) {
    if (repositoryJob == null || repositoryJob.options() == null) {
      return null;
    }
    Object value = repositoryJob.options().get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void closeQuietly(InputStream input) {
    if (input == null) {
      return;
    }
    try {
      input.close();
    } catch (IOException ignored) {
    }
  }

  private ThreadFactory threadFactory(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  @PreDestroy
  void shutdown() {
    triggerExecutor.shutdown();
    executor.shutdown();
    try {
      if (!triggerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        triggerExecutor.shutdownNow();
      }
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      triggerExecutor.shutdownNow();
      executor.shutdownNow();
    }
  }

  private static boolean bool(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static int intOption(Object value, int fallback, int min, int max) {
    if (value == null) {
      return fallback;
    }
    try {
      int parsed = value instanceof Number number
          ? number.intValue()
          : Integer.parseInt(String.valueOf(value).trim());
      return Math.max(min, Math.min(max, parsed));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String firstNonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static String errorSummary(Throwable error) {
    if (error == null) {
      return "";
    }
    String message = error.getMessage();
    return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private record SourceAccess(NexusRestClient client, String metadataEngine, boolean checksumValidation) {
  }

  record BatchProgressTargets(List<Long> repositoryJobIds, List<Long> jobIds) {
  }
}
