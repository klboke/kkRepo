package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.HttpTextResponse;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.SourceProbe;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class NexusRestClientTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void reportsWarningWhenSourceScriptApiDoesNotExposePasswordHashes() throws Exception {
    FakeNexus nexus = new FakeNexus(false);
    NexusInventory inventory = client(nexus).readInventory();

    assertEquals(1, inventory.securityExport().users().size());
    assertFalse(inventory.securityExport().users().get(0).containsKey("passwordHash"));
    assertEquals(2, inventory.warnings().size());
    assertTrue(inventory.warnings().stream().allMatch(value -> value.contains("HTTP 410")));
  }

  @Test
  void mergesLocalUserPasswordHashesReturnedBySourceScriptApi() throws Exception {
    FakeNexus nexus = new FakeNexus(true);
    NexusInventory inventory = client(nexus).readInventory();

    assertEquals("$shiro1$SHA-512$hash", inventory.securityExport().users().get(0).get("passwordHash"));
    assertEquals(1, inventory.securityExport().apiKeys().size());
    assertEquals("NpmToken", inventory.securityExport().apiKeys().get(0).get("domain"));
    assertEquals("alice-token", inventory.securityExport().apiKeys().get(0).get("api_key"));
    assertEquals(1, inventory.securityExport().roles().size());
    assertEquals("nx-admin", inventory.securityExport().roles().get(0).get("id"));
    assertEquals(List.of(), inventory.securityExport().realmOrder());
    assertEquals(false, inventory.securityExport().anonymous().get("enabled"));
    assertEquals(List.of(), inventory.warnings());
    assertEquals("3.77.2-02", inventory.probe().nexusVersion());
    assertEquals("DATASTORE_H2", inventory.probe().metadataEngine());
    assertEquals("H2 2.3.232", inventory.probe().jdbcProduct());
    assertTrue(nexus.scriptDeleted);
    assertEquals("/service/rest/v1/security/users?source=default", nexus.usersPath);
    assertEquals("/service/rest/v1/security/roles?source=default", nexus.rolesPath);
  }

  @Test
  void sourceProfileProbeRunsScriptWithTextPlainBody() throws Exception {
    FakeNexus nexus = new FakeNexus(true);
    NexusInventory inventory = client(nexus).readInventory();
    SourceProbe probe = inventory.probe();

    assertEquals("ok", probe.scriptApiStatus());
    assertTrue(probe.scriptApiCreatable());
    assertTrue(probe.scriptApiRunnable());
    assertTrue(probe.scriptApiDeleted());
    assertTrue(nexus.profileRunContentType.contains("text/plain"));
  }

  @Test
  void sourceProfileProbeIgnoresEmptyRestStatusBody() throws Exception {
    FakeNexus nexus = new FakeNexus(true);
    nexus.emptyStatusBody = true;

    NexusInventory inventory = client(nexus).readInventory();

    assertEquals("3.77.2-02", inventory.probe().nexusVersion());
    assertFalse(inventory.warnings().stream()
        .anyMatch(value -> value.contains("No content to map")));
  }

  @Test
  void includesApiPathWhenSourceRequestTimesOut() {
    NexusRestClient client = new NexusRestClient(
        "http://source.example/",
        "admin",
        "secret",
        OBJECT_MAPPER,
        false,
        request -> {
          throw new HttpTimeoutException("request timed out");
        });

    IOException thrown = assertThrows(IOException.class, client::readInventory);

    assertEquals("Nexus API /service/rest/v1/repositories timed out after 30s", thrown.getMessage());
    assertTrue(thrown.getCause() instanceof HttpTimeoutException);
  }

  @Test
  void resolvesDockerDefaultGatewayForLocalSourceUrl() {
    String routeTable = """
        Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT
        eth0 00000000 01D7A8C0 0003 0 0 0 00000000 0 0 0
        """;

    assertEquals("192.168.215.1", NexusRestClient.dockerDefaultGatewayHost(routeTable));
    assertEquals(
        URI.create("http://192.168.215.1:28090/"),
        NexusRestClient.dockerGatewayBaseUri(URI.create("http://localhost:28090/"), routeTable));
  }

  @Test
  void sourceSecurityScriptUsesClassLookupForNamedComponents() throws Exception {
    var field = NexusRestClient.class.getDeclaredField("LOCAL_SECURITY_EXPORT_SCRIPT");
    field.setAccessible(true);
    String script = (String) field.get(null);

    assertTrue(script.contains("container.lookup('org.sonatype.nexus.security.config.SecurityConfigurationManager')"));
    assertTrue(script.contains("new JsonSlurper().parseText(args)"));
    assertTrue(script.contains("metadataEngine == 'ORIENTDB'"));
    assertTrue(script.contains("metadataEngine.startsWith('DATASTORE')"));
    assertTrue(script.contains("container.lookup('org.sonatype.nexus.security.authc.apikey.ApiKeyLowLevelService')"));
    assertTrue(script.contains("'PubToken'"));
    assertTrue(script.contains("select distinct domain from api_key_v2 order by domain"));
    assertTrue(script.contains("loadClass('org.sonatype.nexus.orient.DatabaseInstance')"));
    assertTrue(script.contains("loadClass('org.sonatype.nexus.orient.DatabaseInstanceNames')"));
    assertTrue(script.contains("container.lookup(databaseClass, securityName)"));
    assertTrue(script.contains("container.lookup(databaseClass, 'security')"));
    assertTrue(script.contains("loadClass('com.orientechnologies.orient.core.sql.query.OSQLSynchQuery')"));
    assertTrue(script.contains("SecurityConfigurationManager unavailable"));
    assertTrue(script.contains("jdkClass('java.lang.ClassLoader')"));
    assertTrue(script.contains("jdkClass('java.io.InputStream')"));
    assertTrue(script.contains("jdkClass('java.io.ByteArrayInputStream')"));
    assertFalse(script.contains("import org.sonatype.nexus.orient.DatabaseInstance"));
    assertFalse(script.contains("import org.sonatype.nexus.orient.DatabaseInstanceNames"));
    assertFalse(script.contains("import org.sonatype.nexus.security.config.SecurityConfigurationManager"));
    assertFalse(script.contains("import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery"));
    assertFalse(script.contains("DatabaseInstance.class.name,"));
    assertFalse(script.contains("SecurityConfigurationManager.class.name, 'default'"));
    assertFalse(script.contains("ClassLoader.class.name, 'nexus-uber'"));
    assertFalse(script.contains("ClassLoader.class"));
    assertFalse(script.contains("java.io.InputStream.class"));
    assertFalse(script.contains("catch (Throwable"));
    assertFalse(script.contains("new LinkedHashSet"));
  }

  @Test
  void securityExportScriptReceivesSourceMetadataEngine() throws Exception {
    FakeNexus nexus = new FakeNexus(true);

    client(nexus).readInventory();

    Map<String, Object> request = OBJECT_MAPPER.readValue(nexus.securityExportRequestBody, Map.class);
    assertEquals("DATASTORE_H2", request.get("metadataEngine"));
  }

  @Test
  void sourceProfileScriptGuardsNullApplicationStatusSource() throws Exception {
    var field = NexusRestClient.class.getDeclaredField("SOURCE_PROFILE_PROBE_SCRIPT");
    field.setAccessible(true);
    String script = (String) field.get(null);

    assertTrue(script.contains("container.lookup('org.sonatype.nexus.common.app.ApplicationStatusSource')"));
    assertTrue(script.contains("applicationStatus == null ? null : applicationStatus.getSystemStatus()"));
    assertTrue(script.contains("not actionable migration warnings"));
    assertFalse(script.contains("def status = applicationStatus.getSystemStatus()"));
    assertFalse(script.contains("ApplicationStatusSource did not return system status"));
    assertFalse(script.contains("version probe failed:"));
    assertFalse(script.contains("import org.sonatype.nexus.common.app.ApplicationStatusSource"));
  }

  @Test
  void sourceProfileScriptDetectsDatastoreFromComponentConnection() throws Exception {
    var field = NexusRestClient.class.getDeclaredField("SOURCE_PROFILE_PROBE_SCRIPT");
    field.setAccessible(true);
    String script = (String) field.get(null);

    assertTrue(script.contains("container.lookup('org.sonatype.nexus.datastore.api.DataStoreManager')"));
    assertTrue(script.contains("jdkClass('javax.sql.DataSource')"));
    assertTrue(script.contains("datastore.openConnection()"));
    assertTrue(script.contains("inspectConnection(connection, 'DataStoreManager')"));
    assertTrue(script.contains("out.metadataEngine = 'DATASTORE_H2'"));
    assertTrue(script.contains("out.metadataEngine = 'DATASTORE_POSTGRESQL'"));
    assertTrue(script.contains("composer: 'COMPOSER'"));
    assertFalse(script.contains("javax.sql.DataSource.class"));
    assertFalse(script.contains("catch (Throwable"));
    assertFalse(script.contains(" as Set"));
    assertFalse(script.contains("new File("));
    assertFalse(script.contains("nexus-store.properties"));
  }

  @Test
  void repositoryDataScriptPagesAssetsByBucketAndNameCursor() throws Exception {
    var field = NexusRestClient.class.getDeclaredField("LOCAL_REPOSITORY_DATA_EXPORT_SCRIPT");
    field.setAccessible(true);
    String script = (String) field.get(null);

    assertTrue(script.contains("loadClass('org.sonatype.nexus.orient.DatabaseInstance')"));
    assertTrue(script.contains("loadClass('org.sonatype.nexus.orient.DatabaseInstanceNames')"));
    assertTrue(script.contains("metadataEngine.startsWith('DATASTORE')"));
    assertTrue(script.contains("repositoryFormat"));
    assertTrue(script.contains("composer: 'COMPOSER'"));
    assertTrue(script.contains("_CONTENT_REPOSITORY"));
    assertTrue(script.contains("_ASSET_BLOB"));
    assertTrue(script.contains("def sourcePathCursor = { value ->"));
    assertTrue(script.contains("def repositoryRelativePath = { value ->"));
    assertTrue(script.contains("def datastoreAfterPath = sourcePathCursor(afterPath)"));
    assertTrue(script.contains("path: path,"));
    assertTrue(script.contains("nextAfterPath: repositoryRelativePath(lastPath)"));
    assertTrue(script.contains("where bucket = ? and name > ? order by name limit"));
    assertTrue(script.contains("def changedSinceOrient = { asset ->"));
    assertTrue(script.contains("def changedSinceDatastore = { row ->"));
    assertTrue(script.contains("asset.field('blob_updated')"));
    assertTrue(script.contains("asset.field('blob_created')"));
    assertTrue(script.contains("blobUpdated == null && blobCreated == null"));
    assertTrue(script.contains("def nextAfterPath = rows.isEmpty()"));
    assertTrue(script.contains("complete: rows.size() < pageSize"));
    assertFalse(script.contains("blob_updated >= ?"));
    assertFalse(script.contains("blob_created >= ?"));
    assertTrue(script.contains("def instantClass = jdkClass('java.time.Instant')"));
    assertTrue(script.contains("def parseInstant = { value ->"));
    assertTrue(script.contains("new JsonSlurper().parseText(args)"));
    assertFalse(script.contains("import org.sonatype.nexus.orient.DatabaseInstance"));
    assertFalse(script.contains("import org.sonatype.nexus.orient.DatabaseInstanceNames"));
    assertFalse(script.contains("import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery"));
    assertFalse(script.contains("import java.time.Instant"));
    assertFalse(script.contains("Date.from(Instant.parse(since))"));
    assertFalse(script.contains("Math.max"));
    assertFalse(script.contains("new LinkedHashMap"));
    assertFalse(script.contains("catch (Throwable"));
  }

  @Test
  void readsRepositoryDataPageFromSourceScriptApi() throws Exception {
    RepositoryDataFakeNexus nexus = new RepositoryDataFakeNexus();
    NexusRestClient client = new NexusRestClient(
        "http://source.example/",
        "admin",
        "secret",
        OBJECT_MAPPER,
        false,
        nexus::send);

    NexusRestClient.RepositoryAssetPage page;
    try (NexusRestClient.RepositoryDataScriptSession session = client.openRepositoryDataScript()) {
      page = session.readPage("maven-releases", "maven2", "UNKNOWN", "com/acme/demo", 1000, null);
    }

    assertEquals("maven-releases", page.repositoryName());
    assertEquals("com/acme/demo", page.afterPath());
    assertEquals("com/acme/demo/1.0/demo-1.0.jar", page.nextAfterPath());
    assertTrue(page.complete());
    assertEquals(1, page.assets().size());
    assertEquals("com.acme", page.assets().get(0).namespace());
    assertEquals("demo", page.assets().get(0).name());
    assertEquals("1.0", page.assets().get(0).version());
    Map<String, Object> request = OBJECT_MAPPER.readValue(nexus.lastRunBody, Map.class);
    assertEquals("maven2", request.get("repositoryFormat"));
    assertEquals("UNKNOWN", request.get("metadataEngine"));
    assertTrue(nexus.scriptDeleted);
  }

  @Test
  void dockerManifestAssetDownloadsRequestSchema2ManifestMediaTypes() {
    String accept = NexusRestClient.repositoryAssetAccept("v2/team/app/manifests/latest");

    assertTrue(accept.contains("application/vnd.docker.distribution.manifest.v2+json"));
    assertTrue(accept.contains("application/vnd.oci.image.manifest.v1+json"));
    assertEquals("*/*", NexusRestClient.repositoryAssetAccept("v2/team/app/blobs/sha256:abc"));
  }

  private static NexusRestClient client(FakeNexus nexus) {
    return new NexusRestClient(
        "http://source.example/",
        "admin",
        "secret",
        OBJECT_MAPPER,
        false,
        nexus::send);
  }

  private static final class FakeNexus {
    private final boolean scriptApiEnabled;
    private boolean scriptDeleted;
    private String usersPath;
    private String rolesPath;
    private String securityExportRequestBody = "{}";
    private int scriptCreates;
    private int scriptRuns;
    private String profileRunContentType = "";
    private boolean emptyStatusBody;

    private FakeNexus(boolean scriptApiEnabled) {
      this.scriptApiEnabled = scriptApiEnabled;
    }

    private HttpTextResponse send(HttpRequest request) throws IOException {
      String method = request.method();
      String path = request.uri().getPath();
      if (request.uri().getRawQuery() != null) {
        path += "?" + request.uri().getRawQuery();
      }
      if ("GET".equals(method) && "/service/rest/v1/status".equals(path)) {
        if (emptyStatusBody) {
          return empty(204);
        }
        return json(200, Map.of("version", "3.77.2-02"));
      }
      if ("GET".equals(method) && "/service/rest/v1/repositories".equals(path)) {
        return json(200, List.of());
      }
      if ("GET".equals(method) && "/service/rest/v1/blobstores".equals(path)) {
        return json(200, List.of());
      }
      if ("GET".equals(method) && "/service/rest/v1/security/users?source=default".equals(path)) {
        usersPath = path;
        return json(200, List.of(
            Map.of(
                "userId", "alice",
                "source", "default",
                "roles", List.of("nx-admin")),
            Map.of(
                "userId", "ldap-user",
                "source", "LDAP",
                "roles", List.of("nx-anonymous"))));
      }
      if ("GET".equals(method) && "/service/rest/v1/security/roles?source=default".equals(path)) {
        rolesPath = path;
        return json(200, List.of(
            Map.of(
                "id", "nx-admin",
                "name", "nx-admin",
                "source", "default",
                "privileges", List.of("nx-all"),
                "roles", List.of()),
            Map.of(
                "id", "ldap-role",
                "name", "ldap-role",
                "source", "LDAP",
                "privileges", List.of(),
                "roles", List.of())));
      }
      if ("GET".equals(method) && "/service/rest/v1/security/privileges".equals(path)) {
        return json(200, List.of());
      }
      if ("GET".equals(method) && "/service/rest/v1/security/content-selectors".equals(path)) {
        return json(200, List.of());
      }
      if ("GET".equals(method) && "/service/rest/v1/security/realms/active".equals(path)) {
        return json(200, List.of("NexusAuthenticatingRealm"));
      }
      if ("GET".equals(method) && "/service/rest/v1/security/anonymous".equals(path)) {
        return json(200, Map.of("enabled", false));
      }
      if ("POST".equals(method) && "/service/rest/v1/script".equals(path)) {
        if (!scriptApiEnabled) {
          return json(410, Map.of("message", "scripts disabled"));
        }
        scriptCreates++;
        return empty(204);
      }
      if ("POST".equals(method)
          && path.startsWith("/service/rest/v1/script/")
          && path.endsWith("/run")) {
        scriptRuns++;
        if (scriptRuns == 1) {
          profileRunContentType = request.headers()
              .firstValue("Content-Type")
              .orElse("");
          String result = OBJECT_MAPPER.writeValueAsString(Map.of(
              "nexusVersion", "3.77.2-02",
              "metadataEngine", "DATASTORE_H2",
              "datastore", Map.of(
                  "jdbcProduct", "H2 2.3.232",
                  "jdbcUrl", "jdbc:h2:file:/nexus-data/db/nexus"),
              "repositorySchema", Map.of(
                  "columns", List.of("ID", "NAME", "RECIPE_NAME", "ATTRIBUTES")),
              "warnings", List.of()));
          return json(200, Map.of("name", "source-profile", "result", result));
        }
        securityExportRequestBody = bodyString(request).orElse("");
        String result = OBJECT_MAPPER.writeValueAsString(Map.of(
            "users", List.of(Map.of(
                "userId", "alice",
                "source", "default",
                "passwordHash", "$shiro1$SHA-512$hash")),
            "apiKeys", List.of(Map.of(
                "domain", "NpmToken",
                "api_key", "alice-token",
                "primary_principal", "alice",
                "principals", Map.of(
                    "primaryPrincipal", "alice",
                    "realmNames", List.of("NexusAuthenticatingRealm"))))));
        return json(200, Map.of("name", "password-export", "result", result));
      }
      if ("DELETE".equals(method) && path.startsWith("/service/rest/v1/script/")) {
        scriptDeleted = true;
        return empty(204);
      }
      return json(404, Map.of("message", "not found", "path", path));
    }

    private static HttpTextResponse json(int status, Object body) throws IOException {
      return new HttpTextResponse(status, OBJECT_MAPPER.writeValueAsString(body));
    }

    private static HttpTextResponse empty(int status) {
      return new HttpTextResponse(status, "", Map.of("Server", List.of("Nexus/3.77.2-02 (COMMUNITY)")));
    }
  }

  private static Optional<String> bodyString(HttpRequest request) {
    return request.bodyPublisher().map(NexusRestClientTest::bodyString);
  }

  private static String bodyString(HttpRequest.BodyPublisher publisher) {
    StringBuilder body = new StringBuilder();
    CompletableFuture<Void> complete = new CompletableFuture<>();
    publisher.subscribe(new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(ByteBuffer item) {
        body.append(StandardCharsets.UTF_8.decode(item.duplicate()));
      }

      @Override
      public void onError(Throwable throwable) {
        complete.completeExceptionally(throwable);
      }

      @Override
      public void onComplete() {
        complete.complete(null);
      }
    });
    complete.join();
    return body.toString();
  }

  private static final class RepositoryDataFakeNexus {
    private boolean scriptDeleted;
    private String lastRunBody = "{}";

    private HttpTextResponse send(HttpRequest request) throws IOException {
      String method = request.method();
      String path = request.uri().getPath();
      if ("POST".equals(method) && "/service/rest/v1/script".equals(path)) {
        return new HttpTextResponse(204, "");
      }
      if ("POST".equals(method)
          && path.startsWith("/service/rest/v1/script/")
          && path.endsWith("/run")) {
        lastRunBody = bodyString(request).orElse("");
        LinkedHashMap<String, Object> asset = new LinkedHashMap<>();
        asset.put("repositoryName", "maven-releases");
        asset.put("assetId", "#45:1");
        asset.put("componentId", "#44:1");
        asset.put("path", "com/acme/demo/1.0/demo-1.0.jar");
        asset.put("format", "maven2");
        asset.put("namespace", "com.acme");
        asset.put("name", "demo");
        asset.put("version", "1.0");
        asset.put("contentType", "application/java-archive");
        asset.put("size", 123L);
        asset.put("sourceBlobRef", "default@abc");
        String result = OBJECT_MAPPER.writeValueAsString(Map.of(
            "repositoryName", "maven-releases",
            "afterPath", "com/acme/demo",
            "nextAfterPath", "com/acme/demo/1.0/demo-1.0.jar",
            "complete", true,
            "warnings", List.of(),
            "assets", List.of(asset)));
        return new HttpTextResponse(200, OBJECT_MAPPER.writeValueAsString(Map.of(
            "name", "repository-data-export",
            "result", result)));
      }
      if ("DELETE".equals(method) && path.startsWith("/service/rest/v1/script/")) {
        scriptDeleted = true;
        return new HttpTextResponse(204, "");
      }
      return new HttpTextResponse(404, OBJECT_MAPPER.writeValueAsString(Map.of("message", "not found")));
    }
  }

}
