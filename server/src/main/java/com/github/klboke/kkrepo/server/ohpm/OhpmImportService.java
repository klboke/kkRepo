package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Item;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.ItemState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Job;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Source;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OhpmImportService {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final OhpmImportDao imports;
  private final OhpmImportSource sources;
  private final OhpmHostedService hosted;
  private final RepositoryRuntimeRegistry repositories;
  private final TransientTransactionRetry transactions;
  private final ObjectMapper json;
  private final RepositoryDao repositoryRecords;

  public OhpmImportService(OhpmImportDao imports, OhpmImportSource sources, OhpmHostedService hosted,
      RepositoryRuntimeRegistry repositories, TransientTransactionRetry transactions, ObjectMapper json,
      RepositoryDao repositoryRecords) {
    this.imports = imports;
    this.sources = sources;
    this.hosted = hosted;
    this.repositories = repositories;
    this.transactions = transactions;
    this.json = json;
    this.repositoryRecords = repositoryRecords;
  }

  public Job create(String repository, Source source, String sourceRepository, String requester) {
    var target = repositoryRecords.findByName(repository)
        .orElseThrow(() -> new OhpmException(404, "Target repository is missing"));
    target(target.id());
    Long sourceId = null;
    if (source == Source.REGISTRY) {
      var remote = repositoryRecords.findByName(sourceRepository)
          .filter(runtime -> runtime.format() == RepositoryFormat.OHPM
              && runtime.type() == com.github.klboke.kkrepo.core.RepositoryType.PROXY && runtime.online())
          .orElseThrow(() -> new OhpmException(400, "Source must be an online OHPM proxy configuration"));
      sourceId = remote.id();
    } else if (source != Source.DIRECTORY || sourceRepository != null && !sourceRepository.isBlank()) {
      throw new OhpmException(400, "Invalid OHPM import source");
    }
    return imports.create(target.id(), source, sourceId, requester);
  }

  public Job job(String id) {
    return imports.findJob(id).orElseThrow(() -> new OhpmException(404, "OHPM import job is missing"));
  }

  public List<Job> jobs(String repository) {
    var target = repositoryRecords.findByName(repository)
        .orElseThrow(() -> new OhpmException(404, "Target repository is missing"));
    return imports.listJobs(target.id(), 100);
  }

  public List<Item> report(String id) {
    job(id);
    return imports.items(id);
  }

  public List<String> sourceVersions(String id, String name) throws IOException {
    Job job = sourceJob(id, Source.REGISTRY);
    var metadata = sources.metadata(source(job), OhpmPackageId.parse(name));
    List<String> versions = new ArrayList<>();
    metadata.original().path("versions").fieldNames().forEachRemaining(versions::add);
    if (versions.size() > 1000) throw new OhpmException(413, "Import package is limited to 1000 versions");
    return List.copyOf(versions);
  }

  public Item planRegistry(String id, String name, String version, Runnable authorize) throws IOException {
    Job job = sourceJob(id, Source.REGISTRY);
    if (name == null || version == null || name.length() + version.length() > 1024) {
      throw new OhpmException(400, "A bounded source package and version are required");
    }
    try (var upload = sources.registry(source(job), OhpmPackageId.parse(name), version)) {
      return plan(job, upload, name + "@" + version, authorize);
    } catch (OhpmException | IllegalArgumentException | IOException error) {
      return planFailure(job, name + "@" + version, error, authorize);
    }
  }

  public Item planDirectory(String id, String sourceKey, InputStream har, InputStream hsp,
      boolean bundle, OhpmPackageMetadata metadata, Runnable authorize) throws IOException {
    Job job = sourceJob(id, Source.DIRECTORY);
    if (sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 1024) {
      throw new OhpmException(400, "A bounded source file label is required");
    }
    try (var upload = sources.directory(har, hsp, bundle, metadata)) {
      return plan(job, upload, sourceKey, authorize);
    } catch (OhpmException | IllegalArgumentException | IOException error) {
      return planFailure(job, sourceKey, error, authorize);
    }
  }

  private Item plan(Job job, OhpmPublishParser.Upload upload, String sourceKey, Runnable authorize)
      throws IOException {
    if (job.state() != JobState.PLANNING) throw new OhpmException(409, "Import plan is sealed");
    var target = target(job.repositoryId());
    var check = hosted.inspectImport(target, upload.metadata());
    Map<String, Object> plan = planData(upload, sourceKey);
    String name = upload.metadata().packageId().id();
    String version = upload.metadata().version();
    return transactions.execute("Plan OHPM import", () -> {
      authorize.run();
      return imports.addPlan(job.id(), key(name, version), name, version, plan,
          check.conflict() == null ? ItemState.PLANNED : ItemState.MANUAL,
          check.conflict() != null ? check.conflict()
              : check.existing() ? "Matching target content; missing tags can be added" : "Checksums verified; ready to import");
    });
  }

  public Job transition(String id, JobState next, Runnable authorize) {
    return transactions.execute("Change OHPM import state", () -> {
      authorize.run();
      Job current = imports.lockJob(id).orElseThrow(() -> new OhpmException(404, "OHPM import job is missing"));
      if (current.state() == next) return current;
      if (!imports.transition(id, current.state(), next)) {
        throw new OhpmException(409, "Import has no items or has incomplete results");
      }
      return imports.lockJob(id).orElseThrow();
    });
  }

  public Item applyRegistry(String id, String itemKey, String actor, String remoteAddress,
      Runnable authorize) throws IOException {
    Job job = sourceJob(id, Source.REGISTRY);
    Item item = item(job, itemKey);
    if (item.succeeded()) return item;
    try (var upload = sources.registry(source(job), OhpmPackageId.parse(item.packageName()), item.version())) {
      return apply(job, item, upload, actor, remoteAddress, authorize);
    } catch (RuntimeException | IOException error) {
      return applyFailure(job, item, error, authorize);
    }
  }

  public Item applyDirectory(String id, String itemKey, InputStream har, InputStream hsp,
      boolean bundle, OhpmPackageMetadata metadata, String actor, String remoteAddress, Runnable authorize)
      throws IOException {
    Job job = sourceJob(id, Source.DIRECTORY);
    Item item = item(job, itemKey);
    if (item.succeeded()) return item;
    try (var upload = sources.directory(har, hsp, bundle, metadata)) {
      return apply(job, item, upload, actor, remoteAddress, authorize);
    } catch (RuntimeException | IOException error) {
      return applyFailure(job, item, error, authorize);
    }
  }

  private Item planFailure(Job job, String sourceKey, Exception error, Runnable authorize) {
    if (error instanceof OhpmException protocol && (protocol.status() == 401 || protocol.status() == 403)) {
      throw protocol;
    }
    return transactions.execute("Record OHPM import planning failure", () -> {
      authorize.run();
      return imports.addPlan(job.id(), key("invalid-source", sourceKey), null, null,
          Map.of("sourceKey", sourceKey), ItemState.MANUAL,
          failureDetail(error) + "; create a new plan after correcting this input");
    });
  }

  private Item applyFailure(Job job, Item item, Exception error, Runnable authorize) {
    if (error instanceof OhpmException protocol && (protocol.status() == 401 || protocol.status() == 403)) {
      throw protocol;
    }
    return transactions.execute("Record OHPM import failure", () -> {
      authorize.run();
      Job current = imports.lockJob(job.id()).orElseThrow();
      Item latest = imports.lockItem(job.id(), item.key()).orElseThrow();
      if (latest.succeeded()) return latest;
      if (current.state() != JobState.APPLYING) throw new OhpmException(409, "Import is not applying");
      boolean retryable = !(error instanceof IllegalArgumentException)
          && (!(error instanceof OhpmException protocol) || protocol.status() >= 500);
      imports.result(job.id(), item.key(), retryable ? ItemState.FAILED : ItemState.MANUAL, failureDetail(error));
      return imports.lockItem(job.id(), item.key()).orElseThrow();
    });
  }

  private static String failureDetail(Exception error) {
    return error instanceof OhpmException protocol
        ? "Source or target validation failed (HTTP " + protocol.status() + "); inspect the source and retry"
        : error instanceof IllegalArgumentException
            ? "Source package metadata is invalid; create a corrected plan"
            : "Source or target content could not be read; retry with the same source";
  }

  private Item apply(Job initial, Item item, OhpmPublishParser.Upload upload, String actor,
      String remoteAddress, Runnable authorize) throws IOException {
    Map<String, Object> actual = planData(upload, item.plan().get("sourceKey").toString());
    authorize.run();
    var runtime = target(initial.repositoryId());
    var verified = hosted.verifyImport(runtime, upload.metadata());
    try (var prepared = hosted.prepare(runtime, upload)) {
      return transactions.execute("Import OHPM version and checkpoint", () -> {
        authorize.run();
        Job current = imports.lockJob(initial.id()).orElseThrow(() -> new OhpmException(404, "Import job is missing"));
        if (current.state() != JobState.APPLYING) throw new OhpmException(409, "Import must be started before applying");
        Item latest = imports.lockItem(current.id(), item.key()).orElseThrow();
        if (latest.succeeded()) return latest;
        if (!latest.plan().equals(actual)) {
          imports.result(current.id(), item.key(), ItemState.MANUAL, "Source differs from the sealed plan; create a new plan");
        } else {
          try {
            var result = hosted.importVersion(target(current.repositoryId()), upload, prepared, verified, actor, remoteAddress,
                existing -> authorize.run());
            imports.result(current.id(), item.key(), result.conflict() != null ? ItemState.MANUAL
                : result.existing() ? ItemState.SKIPPED : ItemState.IMPORTED,
                result.conflict() != null ? result.conflict()
                    : result.existing() ? "Matching content verified; tags synchronized" : "Imported and checksums verified");
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
        }
        return imports.lockItem(current.id(), item.key()).orElseThrow();
      });
    }
  }

  private Item item(Job job, String key) {
    Item item = imports.findItem(job.id(), key).orElseThrow(() -> new OhpmException(404, "Import item is missing"));
    if (item.packageName() == null) throw new OhpmException(409, "Invalid source requires a new corrected plan");
    if (!item.succeeded() && job.state() != JobState.APPLYING) {
      throw new OhpmException(409, "Import must be started before applying");
    }
    return item;
  }

  private Map<String, Object> planData(OhpmPublishParser.Upload upload, String sourceKey) {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("sourceKey", sourceKey);
    plan.put("metadata", json.convertValue(upload.metadata().metadata(), MAP));
    plan.put("harSize", upload.har().size());
    if (upload.hsp() != null) plan.put("hspSize", upload.hsp().size());
    try {
      return json.readValue(json.writeValueAsBytes(plan), MAP);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private Job sourceJob(String id, Source source) {
    Job job = job(id);
    if (job.source() != source) throw new OhpmException(400, "Import source type does not match");
    return job;
  }

  private RepositoryRuntime source(Job job) {
    return repositories.resolveFreshById(job.sourceRepositoryId())
        .orElseThrow(() -> new OhpmException(404, "Source configuration is missing"));
  }

  private RepositoryRuntime target(long id) {
    var runtime = repositories.resolveFreshById(id)
        .orElseThrow(() -> new OhpmException(404, "Target repository is missing"));
    if (runtime.format() != RepositoryFormat.OHPM || !runtime.isHosted()) {
      throw new OhpmException(400, "Import target must be an OHPM hosted repository");
    }
    if (!runtime.online() || "DENY".equals(runtime.writePolicy())) {
      throw new OhpmException(409, "Import target is offline or read-only");
    }
    return runtime;
  }

  private static String key(String name, String version) {
    return HexFormat.of().formatHex(PersistenceHashes.sha256(name + "\n" + version));
  }
}
