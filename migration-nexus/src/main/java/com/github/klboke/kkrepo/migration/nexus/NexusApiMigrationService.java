package com.github.klboke.kkrepo.migration.nexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipe;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.core.security.TerraformSigningKeyMaterial;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder.MigrationScope;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.NexusMigrationPlanItem;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExportReader;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityMigrationBatch;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityMigrationResult;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityMigrationService;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusContentSelector;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUser;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUserRoleMapping;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityMigrationWriter;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class NexusApiMigrationService {
  private static final String DEFAULT_NEXUS_VERSION = "3.29.2-02";

  private final ObjectMapper objectMapper;
  private final BlobStoreDao blobStoreDao;
  private final RepositoryDao repositoryDao;
  private final SecurityDao securityDao;
  private final MigrationJobDao migrationJobDao;
  private final NexusSecurityMigrationWriter securityWriter;
  private final TerraformRegistryDao terraformRegistry;
  // The server controller constructs this service directly, so transaction annotations would not
  // be intercepted by a Spring proxy. Keep the atomic migration boundary explicit instead.
  private final TransactionTemplate migrationTransaction;
  private final TransactionTemplate jobTransaction;

  public NexusApiMigrationService(
      ObjectMapper objectMapper,
      BlobStoreDao blobStoreDao,
      RepositoryDao repositoryDao,
      SecurityDao securityDao,
      MigrationJobDao migrationJobDao,
      NexusSecurityMigrationWriter securityWriter) {
    this(objectMapper, blobStoreDao, repositoryDao, securityDao, migrationJobDao, securityWriter, null);
  }

  public NexusApiMigrationService(
      ObjectMapper objectMapper,
      BlobStoreDao blobStoreDao,
      RepositoryDao repositoryDao,
      SecurityDao securityDao,
      MigrationJobDao migrationJobDao,
      NexusSecurityMigrationWriter securityWriter,
      TerraformRegistryDao terraformRegistry) {
    this(
        objectMapper,
        blobStoreDao,
        repositoryDao,
        securityDao,
        migrationJobDao,
        securityWriter,
        terraformRegistry,
        null);
  }

  public NexusApiMigrationService(
      ObjectMapper objectMapper,
      BlobStoreDao blobStoreDao,
      RepositoryDao repositoryDao,
      SecurityDao securityDao,
      MigrationJobDao migrationJobDao,
      NexusSecurityMigrationWriter securityWriter,
      TerraformRegistryDao terraformRegistry,
      PlatformTransactionManager transactionManager) {
    this.objectMapper = objectMapper;
    this.blobStoreDao = blobStoreDao;
    this.repositoryDao = repositoryDao;
    this.securityDao = securityDao;
    this.migrationJobDao = migrationJobDao;
    this.securityWriter = securityWriter;
    this.terraformRegistry = terraformRegistry;
    this.migrationTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    this.jobTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    if (jobTransaction != null) {
      jobTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
  }

  public NexusMigrationPreflight preflight(NexusMigrationRequest request)
      throws Exception {
    NexusInventory inventory = inventory(request);
    return preflight(inventory, request.targetBlobStore(), request.sourceNexusVersion());
  }

  public NexusMigrationResult migrate(NexusMigrationRequest request) throws Exception {
    NexusInventory inventory = inventory(request);
    return migrate(inventory, request);
  }

  NexusMigrationResult migrate(NexusInventory inventory, NexusMigrationRequest request) {
    NexusMigrationPreflight preflight = preflight(inventory, request.targetBlobStore(), request.sourceNexusVersion());
    NexusMigrationPlan plan = preflight.migrationPlan();
    long jobId = inJobTransaction(() -> migrationJobDao.create(
        defaultString(preflight.sourceProfile().nexusVersion(),
            defaultString(request.sourceNexusVersion(), DEFAULT_NEXUS_VERSION)),
        request.sourceBaseUrl(),
        Map.of(
            "scope", "p0-p1",
            "dryRun", request.dryRun(),
            "sourceBaseUrl", request.sourceBaseUrl(),
            "target", "current",
            "profileHash", plan.profileHash(),
            "planHash", plan.planHash(),
            "sourceAdapter", plan.adapter())));
    try {
      return inMigrationTransaction(() -> {
        ConfigMigrationCounts configCounts = migrateConfig(inventory, request);
        NexusSecurityMigrationResult apiSecurity = migrateSecurity(inventory.securityExport(), request.dryRun());
        List<String> passwordResetRequired = passwordResetRequiredUsers(inventory.securityExport());
        NexusMigrationValidation validation =
            validateMigration(inventory, request, preflight, passwordResetRequired);
        String status = validation.failed()
            ? "finished_with_validation_failures"
            : hasProxyManualAction(inventory)
                ? "finished_with_manual_actions"
                : !passwordResetRequired.isEmpty()
                    ? "finished_with_password_resets_required"
                    : request.dryRun() ? "dry_run_finished" : "finished";
        NexusMigrationResult result = new NexusMigrationResult(
            jobId,
            status,
            request.dryRun(),
            preflight,
            configCounts,
            apiSecurity,
            passwordResetRequired,
            validation);
        migrationJobDao.markFinished(jobId, status, result.toSummary());
        return result;
      });
    } catch (Exception e) {
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("jobId", jobId);
      summary.put("status", "failed");
      summary.put("error", e.getMessage());
      inJobTransaction(() -> {
        migrationJobDao.markFinished(jobId, "failed", summary);
        return null;
      });
      throw e;
    }
  }

  private <T> T inMigrationTransaction(Supplier<T> work) {
    return migrationTransaction == null ? work.get() : migrationTransaction.execute(status -> work.get());
  }

  private <T> T inJobTransaction(Supplier<T> work) {
    return jobTransaction == null ? work.get() : jobTransaction.execute(status -> work.get());
  }

  private NexusInventory inventory(NexusMigrationRequest request) throws Exception {
    return new NexusRestClient(
        request.sourceBaseUrl(),
        request.sourceUsername(),
        request.sourcePassword(),
        objectMapper).readInventory();
  }

  NexusMigrationPreflight preflight(
      NexusInventory inventory,
      NexusMigrationTargetBlobStore targetBlobStore) {
    return preflight(inventory, targetBlobStore, null);
  }

  NexusMigrationPreflight preflight(
      NexusInventory inventory,
      NexusMigrationTargetBlobStore targetBlobStore,
      String requestedVersion) {
    NexusSourceProfile sourceProfile = NexusSourceProfile.fromInventory(inventory, requestedVersion);
    NexusMigrationPlan migrationPlan = withProxyManualActions(
        new MigrationPlanBuilder().build(sourceProfile, new MigrationScope(List.of(), true, false)),
        inventory);
    List<UnsupportedRepository> unsupported = new ArrayList<>();
    List<RepositoryMigrationPlan> repositoriesToMigrate = new ArrayList<>();
    List<GroupRepositoryMigrationPlan> groupRepositories = new ArrayList<>();
    List<ProxyRemoteRisk> proxyRemoteRisks = new ArrayList<>();
    LinkedHashSet<String> migratableNames = inventory.repositories().stream()
        .filter(NexusApiMigrationService::migratableRepository)
        .map(NexusApiMigrationService::repositoryName)
        .filter(name -> name != null && !name.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    int supported = 0;
    for (RepositoryDocument document : inventory.repositories()) {
      String name = repositoryName(document);
      String format = repositoryFormat(document);
      String type = repositoryType(document);
      if (!NexusRepositorySupport.supportedRecipe(format, type)) {
        unsupported.add(new UnsupportedRepository(name, format, type,
            NexusRepositorySupport.unsupportedReason(format, type)));
        continue;
      }
      if (unsupportedSwiftProxyRemote(document)) {
        unsupported.add(new UnsupportedRepository(
            name, format, type, "unsupported_swift_remote"));
        proxyRemoteRisks.add(new ProxyRemoteRisk(
            name, format, remoteUrl(document), "unsupported_swift_remote"));
        continue;
      }
      supported++;
      String credentialRisk = "proxy".equalsIgnoreCase(type) ? proxyCredentialRisk(document) : null;
      repositoriesToMigrate.add(new RepositoryMigrationPlan(
          name,
          format,
          type,
          NexusRepositorySupport.recipe(format, type)
              .map(RepositoryRecipe::name)
              .orElse(null),
          sourceBlobStoreName(document),
          credentialRisk == null && bool(value(document, "online"), true),
          remoteUrl(document)));
      if (repositoryTypeEnum(document) == RepositoryType.GROUP) {
        groupRepositories.add(new GroupRepositoryMigrationPlan(
            name,
            format,
            groupMembers(document).stream().filter(migratableNames::contains).toList()));
      }
      if ("proxy".equalsIgnoreCase(type)) {
        String remote = remoteUrl(document);
        proxyRemoteRisks.add(new ProxyRemoteRisk(name, format, remote,
            "swift".equalsIgnoreCase(format) && !validSwiftRemote(remote)
                ? "unsupported_swift_remote"
                : credentialRisk != null
                    ? credentialRisk
                    : remote == null ? "missing_remote_url" : "not_checked"));
      }
    }
    NexusSecurityPreflight security = securityPreflight(inventory.securityExport());
    List<String> missingPasswordUsers = passwordResetRequiredUsers(inventory.securityExport());
    List<String> warnings = new ArrayList<>(inventory.warnings());
    warnings.addAll(sourceProfile.warnings());
    if (!unsupported.isEmpty()) {
      warnings.add("Unsupported repositories will be skipped.");
    }
    if (!missingPasswordUsers.isEmpty()) {
      warnings.add("Local users without password hash compensation will need password reset.");
    }
    proxyManualActionRisks(inventory).forEach((repository, risk) -> {
      if ("unsupported_swift_remote".equals(risk)) {
        warnings.add("Swift proxy repository " + repository
            + " uses an unsupported remote and will be skipped; recreate it manually with "
            + "the GitHub base URL https://github.com/.");
      } else {
        warnings.add("Proxy repository " + repository
            + " requires manual upstream credential configuration (" + risk
            + "); the migrated repository will be offline.");
      }
    });
    return new NexusMigrationPreflight(
        inventory.blobStores().size(),
        inventory.repositories().size(),
        supported,
        unsupported.size(),
        blobStorePlans(inventory, targetBlobStore),
        repositoriesToMigrate,
        unsupported,
        groupRepositories,
        proxyRemoteRisks,
        security,
        security.users(),
        missingPasswordUsers,
        warnings,
        sourceProfile,
        migrationPlan);
  }

  private List<BlobStoreMigrationPlan> blobStorePlans(
      NexusInventory inventory,
      NexusMigrationTargetBlobStore targetBlobStore) {
    NexusMigrationTargetBlobStore template = targetBlobStore == null
        ? NexusMigrationTargetBlobStore.defaultS3()
        : targetBlobStore;
    if (inventory.blobStores().isEmpty()) {
      return List.of(new BlobStoreMigrationPlan(
          template.name(),
          "implicit",
          template.name(),
          defaultString(template.type(), "s3"),
          template.endpoint(),
          template.region(),
          template.bucket(),
          defaultString(template.prefix(), "")));
    }
    return inventory.blobStores().stream()
        .map(source -> {
          String sourceName = defaultString(string(source.get("name")), template.name());
          return new BlobStoreMigrationPlan(
              sourceName,
              defaultString(firstString(source, "type", "blobStoreType"), "unknown"),
              sourceName,
              defaultString(template.type(), "s3"),
              template.endpoint(),
              template.region(),
              template.bucket(),
              prefixFor(template.prefix(), sourceName, template.name()));
        })
        .toList();
  }

  private NexusSecurityPreflight securityPreflight(NexusSecurityExport export) {
    NexusSecurityExport source = export == null ? NexusSecurityExport.empty() : export;
    NexusSecurityMigrationBatch batch = new NexusSecurityExportReader().read(source);
    return new NexusSecurityPreflight(
        batch.contentSelectors().size(),
        batch.privileges().size(),
        batch.roles().size(),
        batch.users().size(),
        batch.userRoleMappings().size(),
        batch.realmOrder().size(),
        batch.apiKeys().size(),
        source.repositoryTargets().size(),
        batch.anonymousConfig() == null
            ? null
            : new SecurityAnonymousPlan(
                batch.anonymousConfig().enabled(),
                batch.anonymousConfig().userSource(),
                batch.anonymousConfig().userId(),
                batch.anonymousConfig().realmName()),
        batch.users().stream()
            .map(user -> new SecurityUserPlan(
                user.source(),
                user.id(),
                user.status(),
                user.email(),
                user.passwordHash() != null && !user.passwordHash().isBlank()))
            .toList(),
        batch.roles().stream()
            .map(role -> new SecurityRolePlan(
                role.id(),
                role.source(),
                role.name(),
                role.readOnly(),
                role.privileges(),
                role.roles()))
            .toList(),
        batch.userRoleMappings().stream()
            .map(mapping -> new SecurityUserRoleMappingPlan(
                mapping.source(),
                mapping.userId(),
                mapping.roles()))
            .toList(),
        batch.apiKeys().stream()
            .map(apiKey -> new SecurityApiKeyPlan(
                apiKey.domain(),
                apiKey.ownerSource(),
                apiKey.ownerUserId(),
                apiKey.displayName(),
                apiKey.status(),
                apiKey.rawApiKey() != null && !apiKey.rawApiKey().isBlank()))
            .toList(),
        batch.contentSelectors().stream()
            .map(selector -> new SecurityContentSelectorPlan(
                selector.name(),
                selector.type(),
                selector.format(),
                selector.expression()))
            .toList(),
        batch.realmOrder());
  }

  private static String firstString(Map<String, Object> source, String... keys) {
    for (String key : keys) {
      String value = string(source.get(key));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  ConfigMigrationCounts migrateConfig(NexusInventory inventory, NexusMigrationRequest request) {
    ConfigMigrationCounts counts = configCountsOnly(inventory);
    if (request.dryRun()) {
      return counts;
    }
    Map<String, BlobStoreRecord> targetBlobStores = migrateBlobStores(inventory, request.targetBlobStore());
    Map<String, RepositoryRecord> migrated = new LinkedHashMap<>();
    List<RepositoryDocument> supported = inventory.repositories().stream()
        .filter(NexusApiMigrationService::migratableRepository)
        .sorted(Comparator.comparing((RepositoryDocument document) ->
            repositoryTypeEnum(document) == RepositoryType.GROUP ? 1 : 0))
        .toList();
    for (RepositoryDocument document : supported) {
      RepositoryRecord record = repositoryRecord(document, targetBlobStores);
      RepositoryRecord stored = repositoryDao.upsertByName(record);
      migrated.put(record.name(), stored);
      migrateTerraformSigningKey(document, stored);
    }
    for (RepositoryDocument document : supported) {
      if (repositoryTypeEnum(document) != RepositoryType.GROUP) {
        continue;
      }
      RepositoryRecord group = migrated.get(repositoryName(document));
      if (group == null || group.id() == null) {
        continue;
      }
      List<Long> memberIds = groupMembers(document).stream()
          .map(migrated::get)
          .filter(record -> record != null && record.id() != null)
          .map(RepositoryRecord::id)
          .toList();
      repositoryDao.replaceMembers(group.id(), memberIds);
    }
    return counts;
  }

  private ConfigMigrationCounts configCountsOnly(NexusInventory inventory) {
    int supported = 0;
    int unsupported = 0;
    int proxy = 0;
    int group = 0;
    for (RepositoryDocument document : inventory.repositories()) {
      if (migratableRepository(document)) {
        supported++;
        if ("proxy".equalsIgnoreCase(repositoryType(document))) {
          proxy++;
        }
        if ("group".equalsIgnoreCase(repositoryType(document))) {
          group++;
        }
      } else {
        unsupported++;
      }
    }
    return new ConfigMigrationCounts(
        inventory.blobStores().size(), supported, unsupported, proxy, group);
  }

  private Map<String, BlobStoreRecord> migrateBlobStores(
      NexusInventory inventory,
      NexusMigrationTargetBlobStore targetBlobStore) {
    NexusMigrationTargetBlobStore template = targetBlobStore == null
        ? NexusMigrationTargetBlobStore.defaultS3()
        : targetBlobStore;
    Map<String, BlobStoreRecord> stores = new LinkedHashMap<>();
    for (Map<String, Object> source : inventory.blobStores()) {
      String sourceName = defaultString(string(source.get("name")), template.name());
      BlobStoreRecord stored = blobStoreDao.upsertByName(blobStoreRecord(sourceName, source, template));
      stores.put(sourceName, stored);
    }
    if (stores.isEmpty()) {
      BlobStoreRecord stored = blobStoreDao.upsertByName(blobStoreRecord(template.name(), Map.of(), template));
      stores.put(template.name(), stored);
    }
    return stores;
  }

  private BlobStoreRecord blobStoreRecord(
      String sourceName,
      Map<String, Object> source,
      NexusMigrationTargetBlobStore template) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(
        template.attributes() == null ? Map.of() : template.attributes());
    attributes.put("source", "nexus-migration");
    attributes.put("sourceBlobStore", source);
    return new BlobStoreRecord(
        null,
        sourceName,
        defaultString(template.type(), "s3"),
        template.endpoint(),
        template.region(),
        template.bucket(),
        prefixFor(template.prefix(), sourceName, template.name()),
        Map.copyOf(attributes));
  }

  private RepositoryRecord repositoryRecord(
      RepositoryDocument document,
      Map<String, BlobStoreRecord> targetBlobStores) {
    RepositoryRecipe recipe = NexusRepositorySupport.recipe(repositoryFormat(document), repositoryType(document))
        .orElseThrow();
    String sourceBlobStoreName = sourceBlobStoreName(document);
    BlobStoreRecord targetBlobStore = targetBlobStores.get(sourceBlobStoreName);
    if (targetBlobStore == null && !targetBlobStores.isEmpty()) {
      targetBlobStore = targetBlobStores.values().iterator().next();
    }
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("recipe", recipe.name());
    attributes.put("source", "nexus-migration");
    attributes.put("sourceRepository", redactSecrets(
        document.detail().isEmpty() ? document.summary() : document.detail()));
    if (recipe.type() == RepositoryType.PROXY) {
      attributes.put("proxy", proxyAttributes(document, recipe));
    }
    if (recipe.format().name().equals("RAW")) {
      attributes.put("raw", Map.of("contentDisposition", "ATTACHMENT"));
    }
    if (recipe.format().name().equals("DOCKER")) {
      putIfNotNull(attributes, "docker", dockerAttributes(document));
    }
    boolean online = bool(value(document, "online"), true);
    if (recipe.type() == RepositoryType.PROXY && proxyCredentialRisk(document) != null) {
      online = false;
    }
    return new RepositoryRecord(
        null,
        repositoryName(document),
        recipe.format(),
        recipe.type(),
        recipe.name(),
        online,
        targetBlobStore == null ? null : targetBlobStore.id(),
        null,
        proxyRemoteUrl(document, recipe),
        mavenSetting(document, "versionPolicy"),
        mavenSetting(document, "layoutPolicy"),
        normalizeWritePolicy(string(nested(value(document, "storage"), "writePolicy"))),
        bool(nested(value(document, "storage"), "strictContentTypeValidation"), true),
        Map.copyOf(attributes));
  }

  private void migrateTerraformSigningKey(RepositoryDocument document, RepositoryRecord repository) {
    if (terraformRegistry == null
        || repository.id() == null
        || repository.format() != RepositoryFormat.TERRAFORM
        || repository.type() != RepositoryType.HOSTED
        || terraformRegistry.findActiveSigningKey(repository.id()).isPresent()) {
      return;
    }
    Object raw = value(document, "terraformSigning");
    if (!(raw instanceof Map<?, ?> signing)) {
      return;
    }
    String privateArmor = string(signing.get("keypair"));
    if (privateArmor == null || privateArmor.isBlank()) {
      return;
    }
    String passphrase = defaultString(string(signing.get("passphrase")), "");
    try {
      PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(
              privateArmor.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
          new JcaKeyFingerprintCalculator());
      PGPSecretKeyRing selectedRing = null;
      PGPSecretKey signingKey = null;
      java.util.Iterator<PGPSecretKeyRing> ringIterator = rings.getKeyRings();
      while (ringIterator.hasNext() && signingKey == null) {
        PGPSecretKeyRing candidateRing = ringIterator.next();
        java.util.Iterator<PGPSecretKey> keys = candidateRing.getSecretKeys();
        while (keys.hasNext()) {
          PGPSecretKey candidate = keys.next();
          if (candidate.isSigningKey()) {
            candidate.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                .build(passphrase.toCharArray()));
            selectedRing = candidateRing;
            signingKey = candidate;
            break;
          }
        }
      }
      if (selectedRing == null || signingKey == null) {
        throw new IllegalArgumentException(
            "Nexus Terraform signing key cannot sign for repository " + repository.name());
      }
      java.io.ByteArrayOutputStream publicArmor = new java.io.ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(publicArmor)) {
        selectedRing.toCertificate().encode(armor);
      }
      String encrypted = new SecretCipher(EncryptionSecrets.credentialSecret()).encrypt(
          TerraformSigningKeyMaterial.encode(privateArmor, passphrase));
      terraformRegistry.insertSigningKey(new TerraformRegistryDao.SigningKey(
          repository.id(),
          1,
          OpenPgpKeyIds.format(signingKey.getKeyID()),
          encrypted,
          publicArmor.toString(java.nio.charset.StandardCharsets.UTF_8),
          java.time.Instant.now()));
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Failed importing Nexus Terraform signing key for repository " + repository.name(), e);
    }
  }

  private static Object redactSecrets(Object value) {
    if (value instanceof Map<?, ?> source) {
      LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
      source.forEach((key, child) -> {
        String name = String.valueOf(key);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean sensitive = lower.contains("password")
            || lower.contains("passphrase")
            || lower.contains("secret")
            || lower.contains("credential")
            || lower.contains("bearer")
            || lower.contains("token")
            || lower.contains("keypair")
            || lower.contains("privatekey");
        sanitized.put(name, sensitive ? "<redacted>" : redactSecrets(child));
      });
      return java.util.Collections.unmodifiableMap(sanitized);
    }
    if (value instanceof List<?> values) {
      return values.stream().map(NexusApiMigrationService::redactSecrets).toList();
    }
    return value;
  }

  private NexusSecurityMigrationResult migrateSecurity(
      NexusSecurityExport export,
      boolean dryRun) {
    if (export == null) {
      return NexusSecurityMigrationResult.empty();
    }
    NexusSecurityMigrationWriter writer = dryRun ? new NoopSecurityMigrationWriter() : securityWriter;
    return new NexusSecurityMigrationService(new NexusSecurityRecordMapper(), writer)
        .migrate(new NexusSecurityExportReader().read(export));
  }

  private NexusMigrationValidation validateMigration(
      NexusInventory inventory,
      NexusMigrationRequest request,
      NexusMigrationPreflight preflight,
      List<String> passwordResetRequired) {
    List<ValidationCheck> checks = new ArrayList<>();
    if (request.dryRun()) {
      checks.add(new ValidationCheck(
          "runtime",
          "target writes",
          ValidationStatus.SKIPPED.name(),
          "Dry-run only; target database validation was skipped.",
          List.of()));
    } else {
      checks.add(validateBlobStores(inventory, request.targetBlobStore()));
      checks.add(validateRepositories(inventory));
      checks.add(validateGroupMembers(inventory));
      checks.add(validateSecurityObjects(inventory.securityExport()));
      checks.add(validateLocalUserPasswordHashes(passwordResetRequired));
    }
    if (preflight.unsupportedRepositories() > 0) {
      checks.add(new ValidationCheck(
          "repository",
          "unsupported repositories",
          ValidationStatus.MANUAL.name(),
          "Unsupported repositories were intentionally skipped.",
          preflight.unsupported().stream()
              .map(repo -> repo.name() + " (" + repo.format() + "/" + repo.type() + "): " + repo.reason())
              .toList()));
    } else {
      checks.add(new ValidationCheck(
          "repository",
          "unsupported repositories",
          ValidationStatus.PASS.name(),
          "No unsupported repositories were reported by source inventory.",
          List.of()));
    }
    checks.add(validateProxyCredentials(inventory));
    checks.add(validateApiKeyExport(inventory));
    checks.add(validatePlanHashes(preflight));
    return new NexusMigrationValidation(checks.stream()
        .anyMatch(check -> ValidationStatus.FAIL.name().equals(check.status())),
        checks.stream()
            .filter(check -> ValidationStatus.MANUAL.name().equals(check.status()))
            .map(check -> check.scope() + "/" + check.name())
            .toList(),
        checks);
  }

  private ValidationCheck validateBlobStores(
      NexusInventory inventory,
      NexusMigrationTargetBlobStore targetBlobStore) {
    List<String> expectedNames = inventory.blobStores().stream()
        .map(source -> defaultString(string(source.get("name")),
            targetBlobStore == null ? "default" : targetBlobStore.name()))
        .distinct()
        .toList();
    List<String> names = expectedNames.isEmpty()
        ? List.of(targetBlobStore == null ? "default" : defaultString(targetBlobStore.name(), "default"))
        : expectedNames;
    List<String> missing = names.stream()
        .filter(name -> blobStoreDao.findByName(name).isEmpty())
        .toList();
    return missing.isEmpty()
        ? new ValidationCheck(
            "blob-store",
            "configuration",
            ValidationStatus.PASS.name(),
            "All source blob store names are present in target.",
            names)
        : new ValidationCheck(
            "blob-store",
            "configuration",
            ValidationStatus.FAIL.name(),
            "Some source blob store names are missing in target.",
            missing);
  }

  private ValidationCheck validateRepositories(NexusInventory inventory) {
    List<String> expectedNames = expectedRepositoryNames(inventory);
    List<String> missing = expectedNames.stream()
        .filter(name -> repositoryDao.findByName(name).isEmpty())
        .toList();
    return missing.isEmpty()
        ? new ValidationCheck(
            "repository",
            "configuration",
            ValidationStatus.PASS.name(),
            "All supported source repositories are present in target.",
            expectedNames)
        : new ValidationCheck(
            "repository",
            "configuration",
            ValidationStatus.FAIL.name(),
            "Some supported source repositories are missing in target.",
            missing);
  }

  private ValidationCheck validateGroupMembers(NexusInventory inventory) {
    List<String> mismatches = new ArrayList<>();
    for (RepositoryDocument document : inventory.repositories()) {
      if (!NexusRepositorySupport.supportedRecipe(repositoryFormat(document), repositoryType(document))
          || repositoryTypeEnum(document) != RepositoryType.GROUP) {
        continue;
      }
      String groupName = repositoryName(document);
      Optional<RepositoryRecord> group = repositoryDao.findByName(groupName);
      if (group.isEmpty() || group.get().id() == null) {
        mismatches.add(groupName + ": group repository is missing");
        continue;
      }
      List<String> expected = groupMembers(document).stream()
          .filter(name -> repositoryDao.findByName(name).isPresent())
          .toList();
      List<String> actual = repositoryDao.listMembers(group.get().id()).stream()
          .map(RepositoryRecord::name)
          .toList();
      if (!expected.equals(actual)) {
        mismatches.add(groupName + ": expected " + expected + " but found " + actual);
      }
    }
    return mismatches.isEmpty()
        ? new ValidationCheck(
            "repository",
            "group members",
            ValidationStatus.PASS.name(),
            "All supported group member orders match the source inventory.",
            List.of())
        : new ValidationCheck(
            "repository",
            "group members",
            ValidationStatus.FAIL.name(),
            "Some group repository member orders do not match source inventory.",
            mismatches);
  }

  private ValidationCheck validateSecurityObjects(NexusSecurityExport export) {
    NexusSecurityMigrationBatch batch = new NexusSecurityExportReader().read(export);
    List<String> missing = new ArrayList<>();
    for (NexusUser user : batch.users()) {
      if (securityDao.findUser(user.source(), user.id()).isEmpty()) {
        missing.add("user:" + user.source() + "/" + user.id());
      }
    }
    batch.roles().forEach(role -> {
      if (securityDao.findRole(role.id()).isEmpty()) {
        missing.add("role:" + role.id());
      }
    });
    batch.privileges().forEach(privilege -> {
      if (securityDao.findPrivilege(privilege.id()).isEmpty()) {
        missing.add("privilege:" + privilege.id());
      }
    });
    for (NexusApiKey apiKey : batch.apiKeys()) {
      if (securityDao.findApiKey(apiKey.domain(), apiKey.ownerSource(), apiKey.ownerUserId()).isEmpty()) {
        missing.add("api-key:" + apiKey.domain() + "/" + apiKey.ownerSource() + "/" + apiKey.ownerUserId());
      }
    }
    return missing.isEmpty()
        ? new ValidationCheck(
            "security",
            "objects",
            ValidationStatus.PASS.name(),
            "All source users, roles, privileges, and API keys are present in target.",
            List.of(
                "users=" + batch.users().size(),
                "roles=" + batch.roles().size(),
                "privileges=" + batch.privileges().size(),
                "apiKeys=" + batch.apiKeys().size()))
        : new ValidationCheck(
            "security",
            "objects",
            ValidationStatus.FAIL.name(),
            "Some source security objects are missing in target.",
            missing);
  }

  private ValidationCheck validateApiKeyExport(NexusInventory inventory) {
    int apiKeys = inventory.securityExport().apiKeys().size();
    boolean warning = inventory.warnings().stream()
        .anyMatch(value -> value.contains("security internals") || value.contains("API keys"));
    if (apiKeys > 0) {
      return new ValidationCheck(
          "security",
          "api keys",
          ValidationStatus.PASS.name(),
          "Source API keys were exported and included in the security migration.",
          List.of("apiKeys=" + apiKeys));
    }
    if (warning) {
      return new ValidationCheck(
          "security",
          "api keys",
          ValidationStatus.MANUAL.name(),
          "Source Nexus did not expose raw API key material; reset or reissue tokens after migration if required.",
          inventory.warnings().stream()
              .filter(value -> value.contains("security internals") || value.contains("API keys"))
              .toList());
    }
    return new ValidationCheck(
        "security",
        "api keys",
        ValidationStatus.PASS.name(),
            "No source API keys were exported.",
            List.of("apiKeys=0"));
  }

  private ValidationCheck validateProxyCredentials(NexusInventory inventory) {
    Map<String, String> risks = proxyCredentialRisks(inventory);
    if (risks.isEmpty()) {
      return new ValidationCheck(
          "repository",
          "proxy credentials",
          ValidationStatus.PASS.name(),
          "No configured proxy credential was omitted or masked by the source Nexus REST API.",
          List.of());
    }
    return new ValidationCheck(
        "repository",
        "proxy credentials",
        ValidationStatus.MANUAL.name(),
        "Some source proxy credentials cannot be recovered; affected target repositories are offline "
            + "until credentials are configured manually.",
        risks.entrySet().stream()
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .toList());
  }

  private ValidationCheck validatePlanHashes(NexusMigrationPreflight preflight) {
    NexusMigrationPlan plan = preflight.migrationPlan();
    String profileHash = plan == null ? null : plan.profileHash();
    String planHash = plan == null ? null : plan.planHash();
    boolean present = profileHash != null && !profileHash.isBlank()
        && planHash != null && !planHash.isBlank();
    return present
        ? new ValidationCheck(
            "migration",
            "profile and plan hashes",
            ValidationStatus.PASS.name(),
            "Profile and plan hashes were recorded for deterministic resume validation.",
            List.of("profileHash=" + profileHash, "planHash=" + planHash))
        : new ValidationCheck(
            "migration",
            "profile and plan hashes",
            ValidationStatus.FAIL.name(),
            "Profile and plan hashes were not generated.",
            List.of());
  }

  private ValidationCheck validateLocalUserPasswordHashes(List<String> passwordResetRequired) {
    return passwordResetRequired.isEmpty()
        ? new ValidationCheck(
            "security",
            "local user password hashes",
            ValidationStatus.PASS.name(),
            "All migrated local users have password hashes, so original Nexus passwords can be verified by kkrepo.",
            List.of())
        : new ValidationCheck(
            "security",
            "local user password hashes",
            ValidationStatus.MANUAL.name(),
            "Some local users require password reset because source Nexus did not expose password hashes.",
            passwordResetRequired);
  }

  private List<String> expectedRepositoryNames(NexusInventory inventory) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    inventory.repositories().stream()
        .filter(NexusApiMigrationService::migratableRepository)
        .map(NexusApiMigrationService::repositoryName)
        .forEach(names::add);
    return List.copyOf(names);
  }

  private List<String> passwordResetRequiredUsers(NexusSecurityExport apiSecurityExport) {
    return new NexusSecurityExportReader().read(apiSecurityExport).users().stream()
        .filter(user -> localUser(user.source()))
        .filter(user -> !"anonymous".equals(user.id()))
        .filter(user -> user.passwordHash() == null || user.passwordHash().isBlank())
        .map(user -> user.source() + "/" + user.id())
        .sorted()
        .toList();
  }

  private boolean localUser(String source) {
    return "Local".equalsIgnoreCase(source) || "default".equalsIgnoreCase(source);
  }

  private static String repositoryName(RepositoryDocument document) {
    return string(value(document, "name"));
  }

  private static String repositoryFormat(RepositoryDocument document) {
    return string(value(document, "format"));
  }

  private static String repositoryType(RepositoryDocument document) {
    return string(value(document, "type"));
  }

  private static RepositoryType repositoryTypeEnum(RepositoryDocument document) {
    return NexusRepositorySupport.type(repositoryType(document));
  }

  private static Object value(RepositoryDocument document, String key) {
    Object detailValue = document.detail().get(key);
    return detailValue == null ? document.summary().get(key) : detailValue;
  }

  private static String sourceBlobStoreName(RepositoryDocument document) {
    return defaultString(string(nested(value(document, "storage"), "blobStoreName")), "default");
  }

  private static String remoteUrl(RepositoryDocument document) {
    Object proxy = value(document, "proxy");
    String remote = string(nested(proxy, "remoteUrl"));
    if (remote != null) {
      return remote;
    }
    Object attributes = value(document, "attributes");
    return string(nested(nested(attributes, "proxy"), "remoteUrl"));
  }

  private static String proxyRemoteUrl(RepositoryDocument document, RepositoryRecipe recipe) {
    String remote = remoteUrl(document);
    if (recipe != null && recipe.format() == RepositoryFormat.SWIFT
        && recipe.type() == RepositoryType.PROXY) {
      if (!validSwiftRemote(remote)) {
        throw new IllegalArgumentException(
            "Nexus Swift proxy remote must be the GitHub base URL https://github.com/");
      }
      return "https://github.com/";
    }
    if ((remote == null || remote.isBlank())
        && recipe != null
        && recipe.format().name().equals("PUB")
        && recipe.type() == RepositoryType.PROXY) {
      return "https://pub.dev/";
    }
    return remote;
  }

  private static boolean validSwiftRemote(String remote) {
    String candidate = remote == null || remote.isBlank() ? "https://github.com/" : remote.trim();
    try {
      URI uri = new URI(candidate);
      String path = uri.getPath();
      return "https".equalsIgnoreCase(uri.getScheme())
          && "github.com".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null
          && (path == null || path.isBlank() || "/".equals(path));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static boolean unsupportedSwiftProxyRemote(RepositoryDocument document) {
    return "swift".equalsIgnoreCase(repositoryFormat(document))
        && repositoryTypeEnum(document) == RepositoryType.PROXY
        && !validSwiftRemote(remoteUrl(document));
  }

  private static boolean migratableRepository(RepositoryDocument document) {
    return NexusRepositorySupport.supportedRecipe(
        repositoryFormat(document), repositoryType(document))
        && !unsupportedSwiftProxyRemote(document);
  }

  private static Map<String, Object> proxyAttributes(RepositoryDocument document, RepositoryRecipe recipe) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    putIfNotNull(attributes, "remoteUrl", proxyRemoteUrl(document, recipe));
    putIfNotNull(attributes, "contentMaxAgeMinutes", intValue(nested(value(document, "proxy"), "contentMaxAge")));
    putIfNotNull(attributes, "metadataMaxAgeMinutes", intValue(nested(value(document, "proxy"), "metadataMaxAge")));
    Object httpClient = value(document, "httpClient");
    putIfNotNull(attributes, "autoBlock", boolOrNull(nested(httpClient, "autoBlock")));
    putProxyAuthentication(attributes, nested(httpClient, "authentication"));
    putIfNotNull(attributes, "negativeCache", value(document, "negativeCache"));
    // The source snapshot already keeps a recursively redacted copy. Do not persist the raw
    // nested httpClient here; runtime credentials live only in the DAO-encrypted fields above.
    return Map.copyOf(attributes);
  }

  private static NexusMigrationPlan withProxyManualActions(
      NexusMigrationPlan plan,
      NexusInventory inventory) {
    Map<String, String> risks = proxyManualActionRisks(inventory);
    if (risks.isEmpty()) {
      return plan;
    }
    List<NexusMigrationPlanItem> items = plan.items().stream()
        .map(item -> {
          String risk = "repository".equals(item.area()) ? risks.get(item.name()) : null;
          if (risk == null) {
            return item;
          }
          ArrayList<String> reasons = new ArrayList<>(item.reasons());
          ArrayList<String> warnings = new ArrayList<>(item.warnings());
          if ("unsupported_swift_remote".equals(risk)) {
            reasons.add("Source Nexus uses a Swift proxy remote that kkrepo cannot reproduce; "
                + "recreate the repository manually with https://github.com/.");
            warnings.add("Target repository will be skipped because Swift proxy mode only supports "
                + "the GitHub base URL.");
          } else {
            reasons.add("Source Nexus omitted or masked the configured upstream credential; "
                + "configure it manually before enabling the migrated proxy repository.");
            warnings.add("Target repository will be migrated offline because the upstream credential "
                + "cannot be recovered from the Nexus REST response (" + risk + ").");
          }
          return new NexusMigrationPlanItem(
              item.area(),
              item.name(),
              item.format(),
              item.type(),
              SupportStatus.NEEDS_MANUAL_ACTION,
              item.sourceAdapter(),
              item.formatAdapter(),
              item.readMode(),
              item.writeMode(),
              item.checksumMode(),
              item.resumeKey(),
              reasons,
              warnings);
        })
        .toList();
    LinkedHashSet<String> warnings = new LinkedHashSet<>(plan.warnings());
    if (risks.containsValue("unsupported_swift_remote")) {
      warnings.add("Swift proxies with non-GitHub remotes are skipped and require manual recreation.");
    }
    if (risks.values().stream().anyMatch(risk -> !"unsupported_swift_remote".equals(risk))) {
      warnings.add("Some proxy credentials were omitted or masked by the source Nexus REST API; "
          + "the affected target repositories are offline until credentials are configured manually.");
    }
    LinkedHashSet<String> manualActions = new LinkedHashSet<>(plan.manualActions());
    risks.keySet().forEach(repository -> manualActions.add("repository:" + repository));
    List<String> warningList = List.copyOf(warnings);
    List<String> manualActionList = List.copyOf(manualActions);
    return new NexusMigrationPlan(
        plan.adapter(),
        plan.profileHash(),
        MigrationPlanHashes.planHash(plan.adapter(), items, warningList, manualActionList),
        items,
        warningList,
        manualActionList);
  }

  private static boolean hasProxyManualAction(NexusInventory inventory) {
    return !proxyManualActionRisks(inventory).isEmpty();
  }

  private static Map<String, String> proxyManualActionRisks(NexusInventory inventory) {
    LinkedHashMap<String, String> risks = new LinkedHashMap<>(proxyCredentialRisks(inventory));
    inventory.repositories().stream()
        .filter(NexusApiMigrationService::unsupportedSwiftProxyRemote)
        .sorted(Comparator.comparing(
            NexusApiMigrationService::repositoryName,
            Comparator.nullsLast(String::compareTo)))
        .forEach(document -> risks.put(
            repositoryName(document), "unsupported_swift_remote"));
    return java.util.Collections.unmodifiableMap(risks);
  }

  private static Map<String, String> proxyCredentialRisks(NexusInventory inventory) {
    LinkedHashMap<String, String> risks = new LinkedHashMap<>();
    for (RepositoryDocument document : inventory.repositories().stream()
        .sorted(Comparator.comparing(
            NexusApiMigrationService::repositoryName,
            Comparator.nullsLast(String::compareTo)))
        .toList()) {
      if (repositoryTypeEnum(document) != RepositoryType.PROXY
          || !NexusRepositorySupport.supportedRecipe(
              repositoryFormat(document), repositoryType(document))) {
        continue;
      }
      String risk = proxyCredentialRisk(document);
      if (risk != null) {
        risks.put(repositoryName(document), risk);
      }
    }
    return java.util.Collections.unmodifiableMap(risks);
  }

  private static String proxyCredentialRisk(RepositoryDocument document) {
    Object authentication = nested(value(document, "httpClient"), "authentication");
    if (!(authentication instanceof Map<?, ?> values) || values.isEmpty()) {
      return null;
    }
    String type = string(nested(authentication, "type"));
    String username = string(nested(authentication, "username"));
    String password = string(nested(authentication, "password"));
    String bearerToken = firstNonBlank(
        string(nested(authentication, "bearerToken")),
        string(nested(authentication, "token")));
    String value = string(nested(authentication, "value"));
    boolean noAuthentication = type != null
        && ("none".equalsIgnoreCase(type) || "anonymous".equalsIgnoreCase(type))
        && username == null
        && password == null
        && bearerToken == null
        && value == null;
    if (noAuthentication) {
      return null;
    }
    boolean configured = type != null
        || username != null
        || password != null
        || bearerToken != null
        || value != null;
    if (!configured) {
      return null;
    }
    boolean bearer = bearerAuthentication(type) || bearerToken != null;
    String secret = bearer
        ? firstNonBlank(bearerToken, password, value)
        : firstNonBlank(password, value);
    if (secret == null) {
      return "missing_proxy_credential_secret";
    }
    if (maskedSecret(secret)) {
      return "masked_proxy_credential_secret";
    }
    if (!bearer && username == null) {
      return "missing_proxy_credential_username";
    }
    return null;
  }

  private static boolean maskedSecret(String value) {
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.matches("[\\*\\u2022]+")
        || "redacted".equals(normalized)
        || "<redacted>".equals(normalized)
        || "[redacted]".equals(normalized);
  }

  private static void putProxyAuthentication(
      Map<String, Object> attributes,
      Object authentication) {
    String type = string(nested(authentication, "type"));
    String bearerToken = firstNonBlank(
        string(nested(authentication, "bearerToken")),
        string(nested(authentication, "token")));
    String password = string(nested(authentication, "password"));
    if (bearerToken == null && bearerAuthentication(type)) {
      bearerToken = firstNonBlank(password, string(nested(authentication, "value")));
    }
    if (maskedSecret(bearerToken)) {
      bearerToken = null;
    }
    if (bearerToken != null) {
      attributes.put("remoteBearerToken", bearerToken);
      return;
    }

    String username = string(nested(authentication, "username"));
    String basicPassword = firstNonBlank(password, string(nested(authentication, "value")));
    putIfNotNull(attributes, "remoteUsername", username);
    if (username != null && !maskedSecret(basicPassword)) {
      putIfNotNull(attributes, "remotePassword", basicPassword);
    }
  }

  private static boolean bearerAuthentication(String type) {
    if (type == null) {
      return false;
    }
    String normalized = type.trim().toLowerCase(Locale.ROOT);
    return normalized.contains("bearer") || normalized.equals("token");
  }

  private static List<String> groupMembers(RepositoryDocument document) {
    Object group = value(document, "group");
    Object memberNames = nested(group, "memberNames");
    if (!(memberNames instanceof Iterable<?> iterable)) {
      return List.of();
    }
    ArrayList<String> values = new ArrayList<>();
    for (Object item : iterable) {
      String value = string(item);
      if (value != null) {
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  private static String mavenSetting(RepositoryDocument document, String key) {
    return upper(string(nested(value(document, "maven"), key)));
  }

  private static Map<String, Object> dockerAttributes(RepositoryDocument document) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    Integer connectorPort = intValue(nested(value(document, "docker"), "httpPort"));
    if (connectorPort == null) {
      connectorPort = intValue(nested(nested(value(document, "attributes"), "docker"), "connectorPort"));
    }
    attributes.put("connectorEnabled", connectorPort != null);
    putIfNotNull(attributes, "connectorPort", connectorPort);
    return Map.copyOf(attributes);
  }

  private static Object nested(Object value, String key) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    return map.get(key);
  }

  private static String normalizeWritePolicy(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private static String prefixFor(String basePrefix, String sourceName, String templateName) {
    String base = basePrefix == null ? "" : basePrefix.trim();
    if (sourceName == null || sourceName.equals(templateName)) {
      return base;
    }
    if (base.isEmpty()) {
      return sourceName;
    }
    return base.replaceAll("/+$", "") + "/" + sourceName;
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static Boolean boolOrNull(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static boolean bool(Object value, boolean fallback) {
    Boolean parsed = boolOrNull(value);
    return parsed == null ? fallback : parsed;
  }

  private static Integer intValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String upper(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static String defaultString(String value, String fallback) {
    String normalized = string(value);
    return normalized == null ? fallback : normalized;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      String normalized = string(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  public record NexusMigrationRequest(
      String sourceBaseUrl,
      String sourceUsername,
      String sourcePassword,
      String sourceNexusVersion,
      boolean dryRun,
      NexusMigrationTargetBlobStore targetBlobStore) {
  }

  public record NexusMigrationTargetBlobStore(
      String name,
      String type,
      String endpoint,
      String region,
      String bucket,
      String prefix,
      Map<String, Object> attributes) {

    public NexusMigrationTargetBlobStore {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    static NexusMigrationTargetBlobStore defaultS3() {
      return new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of());
    }
  }

  public record NexusMigrationPreflight(
      int blobStores,
      int repositories,
      int supportedRepositories,
      int unsupportedRepositories,
      List<BlobStoreMigrationPlan> blobStorePlans,
      List<RepositoryMigrationPlan> repositoriesToMigrate,
      List<UnsupportedRepository> unsupported,
      List<GroupRepositoryMigrationPlan> groupRepositories,
      List<ProxyRemoteRisk> proxyRemoteRisks,
      NexusSecurityPreflight security,
      int users,
      List<String> passwordResetRequiredUsers,
      List<String> warnings,
      NexusSourceProfile sourceProfile,
      NexusMigrationPlan migrationPlan) {

    public NexusMigrationPreflight {
      blobStorePlans = blobStorePlans == null ? List.of() : List.copyOf(blobStorePlans);
      repositoriesToMigrate = repositoriesToMigrate == null ? List.of() : List.copyOf(repositoriesToMigrate);
      unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
      groupRepositories = groupRepositories == null ? List.of() : List.copyOf(groupRepositories);
      proxyRemoteRisks = proxyRemoteRisks == null ? List.of() : List.copyOf(proxyRemoteRisks);
      security = security == null ? NexusSecurityPreflight.empty() : security;
      passwordResetRequiredUsers = passwordResetRequiredUsers == null
          ? List.of()
          : List.copyOf(passwordResetRequiredUsers);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
      sourceProfile = sourceProfile == null
          ? NexusSourceProfile.fromInventory(new NexusInventory(List.of(), List.of(), null, List.of()), null)
          : sourceProfile;
      migrationPlan = migrationPlan == null
          ? new MigrationPlanBuilder().build(sourceProfile, new MigrationScope(List.of(), true, false))
          : migrationPlan;
    }

    public NexusMigrationPreflight(
        int blobStores,
        int repositories,
        int supportedRepositories,
        int unsupportedRepositories,
        List<BlobStoreMigrationPlan> blobStorePlans,
        List<RepositoryMigrationPlan> repositoriesToMigrate,
        List<UnsupportedRepository> unsupported,
        List<GroupRepositoryMigrationPlan> groupRepositories,
        List<ProxyRemoteRisk> proxyRemoteRisks,
        NexusSecurityPreflight security,
        int users,
        List<String> passwordResetRequiredUsers,
        List<String> warnings) {
      this(
          blobStores,
          repositories,
          supportedRepositories,
          unsupportedRepositories,
          blobStorePlans,
          repositoriesToMigrate,
          unsupported,
          groupRepositories,
          proxyRemoteRisks,
          security,
          users,
          passwordResetRequiredUsers,
          warnings,
          null,
          null);
    }
  }

  public record BlobStoreMigrationPlan(
      String sourceName,
      String sourceType,
      String targetName,
      String targetType,
      String targetEndpoint,
      String targetRegion,
      String targetBucket,
      String targetPrefix) {
  }

  public record RepositoryMigrationPlan(
      String name,
      String format,
      String type,
      String recipe,
      String blobStoreName,
      boolean online,
      String remoteUrl) {
  }

  public record UnsupportedRepository(
      String name,
      String format,
      String type,
      String reason) {
  }

  public record GroupRepositoryMigrationPlan(
      String repository,
      String format,
      List<String> members) {

    public GroupRepositoryMigrationPlan {
      members = members == null ? List.of() : List.copyOf(members);
    }
  }

  public record ProxyRemoteRisk(
      String repository,
      String format,
      String remoteUrl,
      String status) {
  }

  public record NexusSecurityPreflight(
      int contentSelectors,
      int privileges,
      int roles,
      int users,
      int userRoleMappings,
      int realms,
      int apiKeys,
      int repositoryTargets,
      SecurityAnonymousPlan anonymous,
      List<SecurityUserPlan> userDetails,
      List<SecurityRolePlan> roleDetails,
      List<SecurityUserRoleMappingPlan> userRoleMappingDetails,
      List<SecurityApiKeyPlan> apiKeyDetails,
      List<SecurityContentSelectorPlan> contentSelectorDetails,
      List<String> realmOrder) {

    public NexusSecurityPreflight {
      userDetails = userDetails == null ? List.of() : List.copyOf(userDetails);
      roleDetails = roleDetails == null ? List.of() : List.copyOf(roleDetails);
      userRoleMappingDetails = userRoleMappingDetails == null ? List.of() : List.copyOf(userRoleMappingDetails);
      apiKeyDetails = apiKeyDetails == null ? List.of() : List.copyOf(apiKeyDetails);
      contentSelectorDetails = contentSelectorDetails == null ? List.of() : List.copyOf(contentSelectorDetails);
      realmOrder = realmOrder == null ? List.of() : List.copyOf(realmOrder);
    }

    static NexusSecurityPreflight empty() {
      return new NexusSecurityPreflight(
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          null,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of());
    }
  }

  public record SecurityAnonymousPlan(
      boolean enabled,
      String userSource,
      String userId,
      String realmName) {
  }

  public record SecurityUserPlan(
      String source,
      String userId,
      String status,
      String email,
      boolean passwordHashPresent) {
  }

  public record SecurityRolePlan(
      String id,
      String source,
      String name,
      boolean readOnly,
      List<String> privileges,
      List<String> childRoles) {

    public SecurityRolePlan {
      privileges = privileges == null ? List.of() : List.copyOf(privileges);
      childRoles = childRoles == null ? List.of() : List.copyOf(childRoles);
    }
  }

  public record SecurityUserRoleMappingPlan(
      String source,
      String userId,
      List<String> roles) {

    public SecurityUserRoleMappingPlan {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  public record SecurityApiKeyPlan(
      String domain,
      String ownerSource,
      String ownerUserId,
      String displayName,
      String status,
      boolean rawKeyPresent) {
  }

  public record SecurityContentSelectorPlan(
      String name,
      String type,
      String format,
      String expression) {
  }

  public record ConfigMigrationCounts(
      int blobStores,
      int repositories,
      int unsupportedRepositories,
      int proxyRepositories,
      int groupRepositories) {
  }

  public record NexusMigrationResult(
      long jobId,
      String status,
      boolean dryRun,
      NexusMigrationPreflight preflight,
      ConfigMigrationCounts config,
      NexusSecurityMigrationResult apiSecurity,
      List<String> passwordResetRequiredUsers,
      NexusMigrationValidation validation) {

    public NexusMigrationResult {
      passwordResetRequiredUsers = passwordResetRequiredUsers == null
          ? List.of()
          : List.copyOf(passwordResetRequiredUsers);
    }

    public Map<String, Object> toSummary() {
      LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
      summary.put("jobId", jobId);
      summary.put("status", status);
      summary.put("dryRun", dryRun);
      summary.put("config", config);
      summary.put("apiSecurity", apiSecurity);
      summary.put("passwordResetRequiredUsers", passwordResetRequiredUsers);
      summary.put("validation", validation);
      summary.put("preflight", preflight);
      return summary;
    }
  }

  public record NexusMigrationValidation(
      boolean failed,
      List<String> manualActions,
      List<ValidationCheck> checks) {

    public NexusMigrationValidation {
      manualActions = manualActions == null ? List.of() : List.copyOf(manualActions);
      checks = checks == null ? List.of() : List.copyOf(checks);
    }
  }

  public record ValidationCheck(
      String scope,
      String name,
      String status,
      String message,
      List<String> details) {

    public ValidationCheck {
      details = details == null ? List.of() : List.copyOf(details);
    }
  }

  private enum ValidationStatus {
    PASS,
    FAIL,
    MANUAL,
    SKIPPED
  }

  private static final class NoopSecurityMigrationWriter implements NexusSecurityMigrationWriter {
    @Override
    public void upsertRepositoryTarget(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord record) {
    }

    @Override
    public void upsertPrivilege(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord record) {
    }

    @Override
    public void upsertRole(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord record) {
    }

    @Override
    public void replaceRolePrivileges(String roleId, List<String> privilegeIds) {
    }

    @Override
    public void replaceRoleInheritance(String roleId, List<String> childRoleIds) {
    }

    @Override
    public void upsertUser(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord record) {
    }

    @Override
    public void replaceUserRoles(String source, String userId, List<String> roleIds) {
    }

    @Override
    public void upsertRealm(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord record) {
    }

    @Override
    public void updateRealmConfig(List<String> activeRealmIds) {
    }

    @Override
    public void upsertAnonymousConfig(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord record) {
    }

    @Override
    public void upsertApiKey(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord record) {
    }
  }
}
