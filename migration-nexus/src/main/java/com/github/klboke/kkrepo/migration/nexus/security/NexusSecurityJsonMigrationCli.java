package com.github.klboke.kkrepo.migration.nexus.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.DatabaseConnectionSettings;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStoreFactories;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class NexusSecurityJsonMigrationCli {
  private static final String DEFAULT_NEXUS_VERSION = "3.29.2-02";

  public static void main(String[] args) {
    int code = new NexusSecurityJsonMigrationCli().run(args, System.out, System.err);
    if (code != 0) {
      System.exit(code);
    }
  }

  int run(String[] args, PrintStream out, PrintStream err) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      usage(err);
      return 2;
    }

    if (options.help()) {
      usage(out);
      return 0;
    }

    try {
      ObjectMapper objectMapper = new ObjectMapper();
      try (PersistenceStores stores = PersistenceStoreFactories.connect(new DatabaseConnectionSettings(
          options.jdbcUrl(), options.username(), options.password()))) {
        MigrationJobDao jobDao = stores.migrationJobs();
        long jobId = jobDao.create(
            options.sourceNexusVersion(),
            options.sourceDataPath(),
            Map.of(
                "scope", "security",
                "exportPath", options.exportPath().toString()));
        try {
          NexusSecurityExport export = readExport(objectMapper, options.exportPath());
          NexusSecurityMigrationResult result = new NexusSecurityMigrationService(
              new NexusSecurityRecordMapper(),
              new SecurityDaoMigrationWriter(stores.security()))
              .migrate(new NexusSecurityExportReader().read(export));
          int checkpoints = new NexusSecurityMigrationCheckpointWriter(
              stores.migrationCheckpoints(),
              objectMapper)
              .write(jobId, export);
          Map<String, Object> summary = summary(jobId, result, checkpoints, "finished");
          jobDao.markFinished(jobId, "finished", summary);
          out.println(toJson(objectMapper, summary));
          return 0;
        } catch (Exception e) {
          Map<String, Object> summary = new LinkedHashMap<>();
          summary.put("jobId", jobId);
          summary.put("status", "failed");
          summary.put("error", e.getMessage());
          jobDao.markFinished(jobId, "failed", summary);
          throw e;
        }
      }
    } catch (Exception e) {
      err.println("Nexus security migration failed: " + e.getMessage());
      return 1;
    }
  }

  private static NexusSecurityExport readExport(ObjectMapper objectMapper, Path exportPath) throws IOException {
    return objectMapper.readValue(exportPath.toFile(), NexusSecurityExport.class);
  }

  private static Map<String, Object> summary(
      long jobId,
      NexusSecurityMigrationResult result,
      int checkpoints,
      String status) {
    LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
    summary.put("jobId", jobId);
    summary.put("status", status);
    summary.put("contentSelectors", result.contentSelectors());
    summary.put("privileges", result.privileges());
    summary.put("roles", result.roles());
    summary.put("users", result.users());
    summary.put("userRoleMappings", result.userRoleMappings());
    summary.put("realms", result.realms());
    summary.put("apiKeys", result.apiKeys());
    summary.put("anonymousConfigs", result.anonymousConfigs());
    summary.put("checkpoints", checkpoints);
    return summary;
  }

  private static String toJson(ObjectMapper objectMapper, Map<String, Object> summary) {
    try {
      return objectMapper.writeValueAsString(summary);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize migration summary", e);
    }
  }

  private static void usage(PrintStream stream) {
    stream.println("""
        Usage:
          java ... com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityJsonMigrationCli \\
            --export /path/to/security-export.json \\
            --jdbc-url jdbc:mysql://host:3306/kkrepo \\
            --username kkrepo \\
            --password kkrepo \\
            [--source-nexus-version 3.29.2-02] \\
            [--source-data-path /path/to/nexus-data]
        """);
  }

  record Options(
      Path exportPath,
      String jdbcUrl,
      String username,
      String password,
      String sourceNexusVersion,
      String sourceDataPath,
      boolean help) {

    static Options parse(String[] args) {
      if (args == null || args.length == 0) {
        throw new IllegalArgumentException("--export, --jdbc-url, --username, and --password are required");
      }
      LinkedHashMap<String, String> values = new LinkedHashMap<>();
      boolean help = false;
      for (int index = 0; index < args.length; index++) {
        String arg = args[index];
        if ("--help".equals(arg) || "-h".equals(arg)) {
          help = true;
          continue;
        }
        if (!arg.startsWith("--")) {
          throw new IllegalArgumentException("Unexpected argument: " + arg);
        }
        if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
          throw new IllegalArgumentException("Missing value for " + arg);
        }
        values.put(arg.substring(2), args[++index]);
      }
      if (help) {
        return new Options(null, null, null, null, DEFAULT_NEXUS_VERSION, null, true);
      }
      String export = require(values, "export");
      String jdbcUrl = require(values, "jdbc-url");
      String username = require(values, "username");
      String password = require(values, "password");
      String sourceNexusVersion = values.getOrDefault("source-nexus-version", DEFAULT_NEXUS_VERSION);
      String sourceDataPath = values.getOrDefault("source-data-path", export);
      return new Options(
          Path.of(export),
          jdbcUrl,
          username,
          password,
          sourceNexusVersion,
          sourceDataPath,
          false);
    }

    private static String require(Map<String, String> values, String name) {
      String value = values.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("--" + name + " is required");
      }
      return value;
    }
  }
}
