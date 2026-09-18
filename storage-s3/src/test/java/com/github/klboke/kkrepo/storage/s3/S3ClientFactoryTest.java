package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

class S3ClientFactoryTest {
  @TempDir Path tempDir;
  private HttpServer server;
  private String endpoint;
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final List<String> sessionTokens = new CopyOnWriteArrayList<>();
  private final List<String> credentialRequests = new CopyOnWriteArrayList<>();
  private final AtomicInteger issuedCredentials = new AtomicInteger();

  @BeforeEach
  void startFixture() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stopFixture() {
    server.stop(0);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void staticCredentialsOverrideAmbientSessionCredentialsAndClientsAreRebuiltOnChange() {
    String oldAccess = System.setProperty("aws.accessKeyId", "AMBIENT");
    String oldSecret = System.setProperty("aws.secretAccessKey", "ambient-secret");
    String oldToken = System.setProperty("aws.sessionToken", "ambient-token");
    S3ClientFactory factory = new S3ClientFactory();
    try {
      var config = config(endpoint, 1, Map.of("accessKey", "STATIC", "secretKey", "static-secret"));
      var client = factory.client(config);
      assertSame(client, factory.client(config));
      roundTrip(client);
      assertTrue(authorizations.stream().allMatch(header -> header.contains("Credential=STATIC/")));
      assertTrue(sessionTokens.stream().allMatch(String::isEmpty));
      var defaultClient = factory.client(config(endpoint, 1, Map.of()));
      assertNotSame(client, defaultClient);
      roundTrip(defaultClient);
      assertTrue(authorizations.getLast().contains("Credential=AMBIENT/"));
      assertEquals("ambient-token", sessionTokens.getLast());
    } finally {
      factory.shutdown();
      restore("aws.accessKeyId", oldAccess);
      restore("aws.secretAccessKey", oldSecret);
      restore("aws.sessionToken", oldToken);
    }
  }

  @Test
  void defaultChainUsesEnvironmentSessionCredentialsForS3ReadWrite() throws Exception {
    probe(Map.of("AWS_ACCESS_KEY_ID", "ENVKEY", "AWS_SECRET_ACCESS_KEY", "env-secret",
        "AWS_SESSION_TOKEN", "env-token"));
    assertTrue(authorizations.stream().allMatch(header -> header.contains("Credential=ENVKEY/")));
    assertTrue(sessionTokens.stream().allMatch("env-token"::equals));
  }

  @Test
  void defaultChainUsesPodIdentityContainerEndpointAndAuthorizationTokenFile() throws Exception {
    Path token = tempDir.resolve("pod-token");
    Files.writeString(token, "pod-identity-token");
    probe(Map.of("AWS_CONTAINER_CREDENTIALS_FULL_URI", endpoint + "/credentials",
        "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", token.toString()));
    assertFalse(credentialRequests.isEmpty());
    assertTrue(credentialRequests.stream().allMatch("pod-identity-token"::equals));
    assertTrue(authorizations.stream().allMatch(header -> header.contains("Credential=ROLE")));
    assertTrue(sessionTokens.stream().allMatch(value -> value.startsWith("role-token-")));
  }

  @Test
  void defaultChainUsesEc2InstanceProfileWithImdsV2() throws Exception {
    probe(Map.of("AWS_EC2_METADATA_DISABLED", "false", "AWS_EC2_METADATA_SERVICE_ENDPOINT", endpoint));
    assertFalse(credentialRequests.isEmpty());
    assertTrue(credentialRequests.stream().allMatch("imds-token"::equals));
    assertTrue(authorizations.stream().allMatch(header -> header.contains("Credential=ROLE")));
    assertTrue(sessionTokens.stream().allMatch(value -> value.startsWith("role-token-")));
  }

  @Test
  void defaultChainLoadsStsForIrsaAndRefreshesTemporaryCredentials() throws Exception {
    Path token = tempDir.resolve("web-identity-token");
    Files.writeString(token, "web-identity-token");
    probe(Map.of("AWS_WEB_IDENTITY_TOKEN_FILE", token.toString(),
        "AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/kkrepo",
        "AWS_ROLE_SESSION_NAME", "kkrepo-test", "AWS_ENDPOINT_URL_STS", endpoint + "/sts"));
    assertTrue(issuedCredentials.get() >= 3, "Two clients and refresh must resolve independently");
    assertTrue(credentialRequests.stream().allMatch(body ->
        body.contains("Action=AssumeRoleWithWebIdentity") && body.contains("WebIdentityToken=web-identity-token")));
    assertTrue(authorizations.stream().allMatch(header -> header.contains("Credential=ROLE")));
    assertTrue(sessionTokens.stream().distinct().count() >= 3);
  }

  private void probe(Map<String, String> credentialsEnvironment) throws Exception {
    Path output = tempDir.resolve("probe.log");
    ProcessBuilder builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        Probe.class.getName(), endpoint);
    // Never read a developer's real AWS credentials or contact AWS from these protocol fixtures.
    builder.environment().keySet().removeIf(name -> name.startsWith("AWS_")
        || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(name));
    builder.environment().putAll(Map.of("AWS_REGION", "us-east-1", "AWS_EC2_METADATA_DISABLED", "true",
        "AWS_CONFIG_FILE", tempDir.resolve("no-config").toString(),
        "AWS_SHARED_CREDENTIALS_FILE", tempDir.resolve("no-credentials").toString()));
    builder.environment().putAll(credentialsEnvironment);
    Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Credential probe timed out");
      assertEquals(0, process.exitValue(), () -> {
        try { return Files.readString(output); } catch (IOException e) { return e.toString(); }
      });
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    assertEquals(6, authorizations.size());
    assertTrue(objects.isEmpty(), "Read/write probes must delete their fixture objects");
  }

  public static class Probe {
    public static void main(String[] args) {
      S3ClientFactory factory = new S3ClientFactory();
      try {
        var first = factory.client(config(args[0], 1, Map.of()));
        var second = factory.client(config(args[0], 2, Map.of()));
        roundTrip(first);
        // Resolve the second provider before closing the first client, then use it again afterwards.
        second.putObject(b -> b.bucket("bucket").key("probe"), RequestBody.fromString("payload"));
        factory.invalidate(1);
        if (!"payload".equals(second.getObjectAsBytes(b -> b.bucket("bucket").key("probe")).asUtf8String())) {
          throw new AssertionError("S3 read did not match write");
        }
        second.deleteObject(b -> b.bucket("bucket").key("probe"));
      } finally {
        factory.shutdown();
      }
    }
  }

  private static void roundTrip(S3Client client) {
    client.putObject(b -> b.bucket("bucket").key("probe"), RequestBody.fromString("payload"));
    assertEquals("payload", client.getObjectAsBytes(b -> b.bucket("bucket").key("probe")).asUtf8String());
    client.deleteObject(b -> b.bucket("bucket").key("probe"));
  }

  private static S3BlobStoreConfig config(String endpoint, long id, Map<String, Object> attrs) {
    return S3BlobStoreConfig.of(id, "store-" + id, endpoint, "us-east-1", "bucket", "", attrs);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
      boolean instanceCredentials = path.equals("/latest/meta-data/iam/security-credentials/kkrepo-role");
      if (path.equals("/latest/api/token") || path.equals("/latest/meta-data/iam/security-credentials")) {
        boolean tokenRequest = path.equals("/latest/api/token");
        if (!tokenRequest) {
          credentialRequests.add(exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"));
        }
        byte[] bytes = (tokenRequest ? "imds-token" : "kkrepo-role").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        return;
      }
      if (path.equals("/credentials") || instanceCredentials || exchange.getRequestMethod().equals("POST")) {
        int generation = issuedCredentials.incrementAndGet();
        // The first short-lived STS credentials are already inside the SDK's stale window.
        String expiration = Instant.now().plusSeconds(exchange.getRequestMethod().equals("POST") && generation == 1 ? 30 : 3600).toString();
        String response;
        if (path.equals("/credentials") || instanceCredentials) {
          credentialRequests.add(exchange.getRequestHeaders().getFirst(
              instanceCredentials ? "X-aws-ec2-metadata-token" : "Authorization"));
          response = "{\"Code\":\"Success\",\"AccessKeyId\":\"ROLE" + generation + "\",\"SecretAccessKey\":\"role-secret\","
              + "\"Token\":\"role-token-" + generation + "\",\"Expiration\":\"" + expiration + "\"}";
          exchange.getResponseHeaders().set("Content-Type", "application/json");
        } else {
          credentialRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          response = "<AssumeRoleWithWebIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<AssumeRoleWithWebIdentityResult><Credentials><AccessKeyId>ROLE" + generation
              + "</AccessKeyId><SecretAccessKey>role-secret</SecretAccessKey><SessionToken>role-token-" + generation
              + "</SessionToken><Expiration>" + expiration + "</Expiration></Credentials>"
              + "</AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>";
          exchange.getResponseHeaders().set("Content-Type", "text/xml");
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        return;
      }
      authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      String token = exchange.getRequestHeaders().getFirst("X-Amz-Security-Token");
      sessionTokens.add(token == null ? "" : token);
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          objects.put(path, exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(200, -1);
        }
        case "GET" -> {
          byte[] bytes = objects.get(path);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
        }
        case "DELETE" -> {
          objects.remove(path);
          exchange.sendResponseHeaders(204, -1);
        }
        default -> exchange.sendResponseHeaders(405, -1);
      }
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
