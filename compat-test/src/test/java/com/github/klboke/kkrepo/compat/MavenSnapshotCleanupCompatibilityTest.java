package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Uses the real Nexus task and disposable repositories; enabled in the Nexus CI suite. */
class MavenSnapshotCleanupCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  @Test void buildRetentionAgeAndSurvivingMetadataMatchNexus() throws Exception {
    assumeTrue(Boolean.parseBoolean(System.getenv("MAVEN_SNAPSHOT_CLEANUP_COMPAT_ENABLED")),
        "Requires a disposable Nexus with scripting and candidate write grace explicitly set to PT0S");
    Endpoint nexus = new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
        CompatDefaults.nexusUsername().orElseThrow(), CompatDefaults.nexusPassword().orElseThrow());
    Endpoint candidate = new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
        CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow());
    String name = "snapshot-cleanup-" + Long.toUnsignedString(System.nanoTime());
    Long policyId = null;
    Long revision = null;
    try {
      nexus.json("POST", "/service/rest/v1/repositories/maven/hosted", Map.of("name", name, "online", true,
          "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false, "writePolicy", "ALLOW"),
          "maven", Map.of("versionPolicy", "SNAPSHOT", "layoutPolicy", "STRICT")), 201);
      var repo = candidate.json("POST", "/internal/repositories", Map.of("name", name, "recipe", "maven2-hosted",
          "online", true, "blobStoreName", "default", "hosted", Map.of("writePolicy", "ALLOW", "versionPolicy", "SNAPSHOT", "layoutPolicy", "STRICT")), 201);
      for (Endpoint endpoint : List.of(nexus, candidate)) {
        for (String base : List.of("1.0", "2.0")) {
          for (int build : new int[] {1, 9, 10}) {
            String stem = root(base) + "demo-" + base + "-20260101.120000-" + build;
            for (String suffix : List.of(".jar", "-sources.jar", ".jar.asc")) {
              byte[] bytes = (base + ":" + build + suffix).getBytes(StandardCharsets.UTF_8);
              endpoint.put("/repository/" + name + "/" + stem + suffix, bytes);
              endpoint.put("/repository/" + name + "/" + stem + suffix + ".sha1",
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).getBytes(StandardCharsets.UTF_8));
            }
          }
          endpoint.put("/repository/" + name + "/" + root(base) + "maven-metadata.xml", metadata(base).getBytes(StandardCharsets.UTF_8));
        }
      }
      // Both products use actual publication time, never the timestamp embedded in the filename.
      nexusTask(nexus, name, 2);
      var policy = createPolicy(candidate, name, repo.path("id").asLong(), 2);
      policyId = policy.path("id").asLong(); revision = policy.path("revision").asLong();
      assertEquals(0, cleanup(candidate, policyId, revision, "EXECUTE").path("run").path("deletedSubjects").asInt());
      assertEquals(200, nexus.request("GET", "/repository/" + name + "/" + root("1.0") + "demo-1.0-20260101.120000-1.jar", null).statusCode());
      candidate.json("DELETE", "/internal/cleanup/policies/" + policyId + "?revision=" + revision, null, 204);
      policyId = null;
      nexusTask(nexus, name, 0);
      policy = createPolicy(candidate, name, repo.path("id").asLong(), 0);
      policyId = policy.path("id").asLong(); revision = policy.path("revision").asLong();
      var preview = cleanup(candidate, policyId, revision, "TRY_RUN");
      assertEquals(6, preview.path("run").path("scannedSubjects").asInt(), preview.toString());
      assertEquals(2, preview.path("run").path("matchedSubjects").asInt(), preview.toString());
      var executed = cleanup(candidate, policyId, revision, "EXECUTE");
      assertEquals(2, executed.path("run").path("deletedSubjects").asInt(), executed.toString());
      for (String base : List.of("1.0", "2.0")) {
        for (int build : new int[] {1, 9, 10}) {
          for (String suffix : List.of(".jar", "-sources.jar", ".jar.asc", ".jar.sha1", "-sources.jar.sha1", ".jar.asc.sha1")) {
            String path = "/repository/" + name + "/" + root(base) + "demo-" + base + "-20260101.120000-" + build + suffix;
            var reference = nexus.request("GET", path, null);
            var actual = candidate.request("GET", path, null);
            assertEquals(build == 1 ? 404 : 200, reference.statusCode(), path);
            assertEquals(reference.statusCode(), actual.statusCode(), path);
            if (build != 1) assertArrayEquals(reference.body(), actual.body(), path);
            assertEquals(reference.statusCode(), candidate.request("HEAD", path, null).statusCode(), path);
          }
        }
        for (Endpoint endpoint : List.of(nexus, candidate)) {
          var xml = endpoint.request("GET", "/repository/" + name + "/" + root(base) + "maven-metadata.xml", null);
          assertEquals(200, xml.statusCode());
          assertTrue(new String(xml.body(), StandardCharsets.UTF_8).contains(base + "-20260101.120000-10"));
        }
      }
    } finally {
      if (policyId != null) candidate.json("DELETE", "/internal/cleanup/policies/" + policyId + "?revision=" + revision, null, 204);
      nexus.request("DELETE", "/service/rest/v1/repositories/" + name, null);
      candidate.request("DELETE", "/internal/repositories/" + name, null);
    }
  }

  private static JsonNode createPolicy(Endpoint endpoint, String name, long repository, int days) throws Exception {
    return endpoint.json("POST", "/internal/cleanup/policies", Map.of("name", name + "-" + days, "format", "maven2",
        "repositoryIds", List.of(repository), "criteria", Map.of("mode", "MAVEN_SNAPSHOT_BUILDS", "snapshot",
            Map.of("retainCount", 2, "publishedOlderThanDays", days)),
        "scanLimitPerRepository", 100, "deleteLimitPerRepository", 100), 201).path("policy");
  }
  private static JsonNode cleanup(Endpoint endpoint, long policy, long revision, String mode) throws Exception {
    var queued = endpoint.json("POST", "/internal/cleanup/policies/" + policy + "/runs",
        Map.of("mode", mode, "expectedPolicyRevision", revision, "scanLimitPerRepository", 100), 202);
    long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < until) {
      var run = endpoint.json("GET", "/internal/cleanup/runs/" + queued.path("run").path("id").asLong(), null, 200);
      String state = run.path("run").path("state").asText();
      if (List.of("SUCCEEDED", "SUCCEEDED_TRUNCATED", "PARTIAL", "PARTIAL_LIMIT_REACHED", "FAILED", "CANCELLED").contains(state)) {
        assertEquals("SUCCEEDED", state, run.toString());
        if (run.toString().contains("SNAPSHOT_INDEX_WARMING_UP")) {
          Thread.sleep(200);
          queued = endpoint.json("POST", "/internal/cleanup/policies/" + policy + "/runs",
              Map.of("mode", mode, "expectedPolicyRevision", revision, "scanLimitPerRepository", 100), 202);
          continue;
        }
        return run;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Snapshot cleanup timed out");
  }
  private static void nexusTask(Endpoint endpoint, String repo, int days) throws Exception {
    String script = repo + "-task-" + days;
    // The task is submitted immediately and awaited in the reference process, never scheduled globally.
    String groovy = """
        def scheduler = container.lookup(org.sonatype.nexus.scheduling.TaskScheduler)
        def config = scheduler.createTaskConfigurationInstance('repository.maven.remove-snapshots')
        config.name = '%s'
        config.setString('repositoryName', '%s')
        config.setString('minimumRetained', '2')
        config.setString('snapshotRetentionDays', '%d')
        config.setString('removeIfReleased', 'false')
        def info = scheduler.submit(config)
        info.currentState.future.get(60, java.util.concurrent.TimeUnit.SECONDS)
        return info.currentState.toString()
        """.formatted(script, repo, days);
    endpoint.json("POST", "/service/rest/v1/script", Map.of("name", script, "type", "groovy", "content", groovy), 204);
    try { endpoint.json("POST", "/service/rest/v1/script/" + script + "/run", Map.of(), 200); }
    finally { endpoint.request("DELETE", "/service/rest/v1/script/" + script, null); }
  }
  private static String root(String base) { return "com/example/demo/" + base + "-SNAPSHOT/"; }
  private static String metadata(String base) {
    return """
        <metadata><groupId>com.example</groupId><artifactId>demo</artifactId><version>%s-SNAPSHOT</version>
        <versioning><snapshot><timestamp>20260101.120000</timestamp><buildNumber>10</buildNumber></snapshot>
        <snapshotVersions><snapshotVersion><extension>jar</extension><value>%s-20260101.120000-10</value></snapshotVersion>
        <snapshotVersion><extension>jar</extension><classifier>sources</classifier><value>%s-20260101.120000-10</value></snapshotVersion>
        </snapshotVersions></versioning></metadata>
        """.formatted(base, base, base);
  }
  private record Endpoint(String base, String user, String password) {
    HttpResponse<byte[]> request(String method, String path, byte[] bytes) throws Exception {
      return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", path.startsWith("/repository/") ? "application/octet-stream" : "application/json")
          .method(method, bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes))
          .build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode json(String method, String path, Object body, int expected) throws Exception {
      var response = request(method, path, body == null ? null : JSON.writeValueAsBytes(body));
      assertEquals(expected, response.statusCode(), method + " " + path + ": " + new String(response.body(), StandardCharsets.UTF_8));
      return response.body().length == 0 ? JSON.nullNode() : JSON.readTree(response.body());
    }
    void put(String path, byte[] bytes) throws Exception { assertEquals(201, request("PUT", path, bytes).statusCode(), path); }
  }
}
