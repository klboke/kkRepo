package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmLoginDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.security.OhpmLoginTokenPolicy;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class OhpmLoginRuntimeChecks {
  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static void verify(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      HttpClient client, String adminToken, Path directory) throws Exception {
    String firstBase = base(first);
    String secondBase = base(second);
    String firstLogin = firstBase + "/repository/ohpm/login";
    String secondLogin = secondBase + "/repository/ohpm/login";
    String management = "/internal/ohpm/login";
    assertEquals(401, send(client, "GET", firstBase + management, null, null).statusCode());
    var config = send(client, "GET", secondBase + management, "Bearer " + adminToken, null);
    assertEquals(200, config.statusCode(), config.body());
    assertTrue(JSON.readTree(config.body()).path("enabled").asBoolean());
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair key = generator.generateKeyPair();
    String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
    String command = JSON.writeValueAsString(Map.of(
        "source", "Local", "userId", "admin", "publicKey", pem, "enabled", true));
    var registered = send(client, "PUT", firstBase + management + "/keys", "Bearer " + adminToken, command);
    assertEquals(200, registered.statusCode(), registered.body());
    var binding = JSON.readTree(registered.body());
    String publishId = binding.path("publishId").asText();
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getPublic().getEncoded())),
        binding.path("fingerprint").asText());
    assertFalse(binding.has("publicKey"));
    String firstNonce = nonce();
    String signed = signed(key, publishId, Instant.now().toEpochMilli(), firstNonce);
    var loggedIn = send(client, "POST", secondLogin, null, signed);
    assertEquals(200, loggedIn.statusCode(), loggedIn.body());
    assertTrue(JSON.readTree(loggedIn.body()).path("success").asBoolean());
    assertEquals("no-store", loggedIn.headers().firstValue("Cache-Control").orElseThrow());
    String token = JSON.readTree(loggedIn.body()).path("token").asText();
    assertTrue(token.startsWith("OhpmLogin."));
    String metadata = "/repository/ohpm/@scope%2fexample";
    assertEquals(200, send(client, "GET", firstBase + metadata, token, null).statusCode());
    assertEquals(200, send(client, "GET", secondBase + metadata, token, null).statusCode());
    assertEquals(401, send(client, "GET", secondBase + management, "Bearer " + token, null).statusCode());
    assertEquals(401, send(client, "POST", firstLogin, null, signed).statusCode());
    assertEquals(401, send(client, "POST", secondLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), firstNonce.toUpperCase(java.util.Locale.ROOT))).statusCode());
    assertEquals(401, send(client, "POST", secondLogin, null,
        signed(key, publishId, Instant.now().minusSeconds(301).toEpochMilli(), nonce())).statusCode());
    assertEquals(401, send(client, "POST", firstLogin, null,
        signed(key, publishId, Instant.now().plusSeconds(310).toEpochMilli(), nonce())).statusCode());
    assertEquals(401, send(client, "POST", firstLogin, null,
        signed(generator.generateKeyPair(), publishId, Instant.now().toEpochMilli(), nonce())).statusCode());
    assertEquals(400, send(client, "POST", firstLogin, null, "{}").statusCode());
    assertEquals(404, send(client, "POST", firstBase + "/repository/ohpm/login_pss", null, signed).statusCode());

    var firstAttempt = client.sendAsync(request("POST", firstLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce())), HttpResponse.BodyHandlers.ofString());
    var secondAttempt = client.sendAsync(request("POST", secondLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce())), HttpResponse.BodyHandlers.ofString());
    for (var pending : List.of(firstAttempt, secondAttempt)) {
      var response = pending.get(30, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(token, JSON.readTree(response.body()).path("token").asText());
    }
    String racing = signed(key, publishId, Instant.now().toEpochMilli(), nonce());
    var replayFirst = client.sendAsync(request("POST", firstLogin, null, racing), HttpResponse.BodyHandlers.ofString());
    var replaySecond = client.sendAsync(request("POST", secondLogin, null, racing), HttpResponse.BodyHandlers.ofString());
    assertEquals(List.of(200, 401), List.of(replayFirst.get(30, TimeUnit.SECONDS).statusCode(),
        replaySecond.get(30, TimeUnit.SECONDS).statusCode()).stream().sorted().toList());
    if (Boolean.getBoolean("kkrepo.ohpm.client")) {
      verifyClient(directory, firstBase, secondBase, key, publishId, token);
    }

    var security = first.getBean(SecurityDao.class);
    var apiKey = security.findApiKey(OhpmLoginTokenPolicy.DOMAIN, "Local", "admin").orElseThrow();
    assertTrue(apiKey.expiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(301)),
        "JDBC must preserve the UTC expiry without applying the JVM timezone");
    assertFalse(new OhpmLoginTokenPolicy(first.getBean(OhpmLoginDao.class), false).valid(apiKey));
    security.upsertApiKey(new ApiKeyRecord(apiKey.id(), apiKey.domain(), apiKey.ownerSource(), apiKey.ownerUserId(),
        apiKey.displayName(), apiKey.status(), apiKey.apiKeyHash(), apiKey.tokenPrefix(), apiKey.scopes(),
        apiKey.encryptedPayload(), apiKey.createdAt(), apiKey.updatedAt(),
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), apiKey.lastUsedAt()));
    assertEquals(401, send(client, "GET", secondBase + metadata, token, null).statusCode());
    var renewed = send(client, "POST", secondLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce()));
    assertEquals(200, renewed.statusCode(), renewed.body());
    String renewedToken = JSON.readTree(renewed.body()).path("token").asText();
    assertNotEquals(token, renewedToken);
    assertEquals(401, send(client, "GET", firstBase + metadata, token, null).statusCode());
    assertEquals(200, send(client, "GET", firstBase + metadata, renewedToken, null).statusCode());

    var owner = security.findUser("Local", "admin").orElseThrow();
    security.updateUser(new SecurityUserRecord(owner.id(), owner.source(), owner.userId(), owner.firstName(),
        owner.lastName(), owner.email(), owner.passwordHash(), "DISABLED", owner.externalId(), owner.attributes()));
    try {
      assertEquals(401, send(client, "GET", secondBase + metadata, renewedToken, null).statusCode());
      assertEquals(401, send(client, "POST", secondLogin, null,
          signed(key, publishId, Instant.now().toEpochMilli(), nonce())).statusCode());
    } finally {
      security.updateUser(owner);
    }
    var disabled = send(client, "PUT", firstBase + management + "/keys/" + publishId + "/enabled",
        "Bearer " + adminToken, "{\"enabled\":false}");
    assertEquals(200, disabled.statusCode(), disabled.body());
    assertEquals(publishId, JSON.readTree(disabled.body()).path("publishId").asText());
    assertEquals(401, send(client, "GET", secondBase + metadata, renewedToken, null).statusCode());
    assertEquals(401, send(client, "POST", secondLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce())).statusCode());
    assertEquals(200, send(client, "PUT", secondBase + management + "/keys/" + publishId + "/enabled",
        "Bearer " + adminToken, "{\"enabled\":true}").statusCode());
    assertEquals(401, send(client, "GET", firstBase + metadata, renewedToken, null).statusCode());
    var reenabled = send(client, "POST", firstLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce()));
    assertEquals(200, reenabled.statusCode(), reenabled.body());
    String currentToken = JSON.readTree(reenabled.body()).path("token").asText();
    KeyPair replacement = generator.generateKeyPair();
    String replacementPem = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getEncoder().encodeToString(replacement.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    var rotation = send(client, "PUT", secondBase + management + "/keys", "Bearer " + adminToken,
        JSON.writeValueAsString(Map.of("source", "Local", "userId", "admin", "publicKey", replacementPem, "enabled", true)));
    assertEquals(200, rotation.statusCode(), rotation.body());
    assertEquals(publishId, JSON.readTree(rotation.body()).path("publishId").asText());
    assertEquals(401, send(client, "GET", firstBase + metadata, currentToken, null).statusCode());
    assertEquals(401, send(client, "POST", firstLogin, null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce())).statusCode());
    assertEquals(200, send(client, "POST", secondLogin, null,
        signed(replacement, publishId, Instant.now().toEpochMilli(), nonce())).statusCode());
    assertEquals(204, send(client, "DELETE", firstBase + management + "/keys/" + publishId,
        "Bearer " + adminToken, null).statusCode());
    assertTrue(second.getBean(OhpmLoginDao.class).findKey(publishId).isEmpty());
    assertEquals(200, send(client, "GET", secondBase + metadata, adminToken, null).statusCode());
    verifySelector(first, second, client, adminToken, key, pem);
    var audit = first.getBean(SecurityAuditDao.class).search(SecurityAuditDao.AuditLogQuery.sanitize(null));
    assertTrue(audit.items().stream().anyMatch(entry -> "login".equals(entry.details().get("operation"))
        && "SUCCESS".equals(entry.outcome())));
    String auditJson = JSON.writeValueAsString(audit.items());
    assertFalse(auditJson.contains(token));
    assertFalse(auditJson.contains(renewedToken));
    assertFalse(auditJson.contains(JSON.readTree(signed).path("signature").asText()));
  }

  private static void verifySelector(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      HttpClient client, String adminToken, KeyPair key, String pem) throws Exception {
    var security = first.getBean(SecurityManagementService.class);
    security.createContentSelector(new RepositoryTargetCommand("signed-selector", "Signed publisher", "ohpm",
        "format == 'ohpm' and (path == '@scope/example' or path =^ '@scope/example/')", List.of(),
        Map.of("source", "nexus-content-selector", "type", "csel")));
    security.savePrivilege(new PrivilegeCommand("signed-publish", "Signed publish", "", "repository-content-selector",
        false, Map.of("contentSelector", "signed-selector", "format", "ohpm", "repository", "ohpm", "actions", "add,edit,read")));
    security.saveRole(new RoleCommand("signed-publisher", "Local", "Signed publisher", "", false,
        List.of("signed-publish"), List.of(), Map.of()));
    security.saveUser(new UserCommand("Local", "signed-publisher", "Signed", "Publisher", null,
        UUID.randomUUID().toString(), null, "ACTIVE", null, List.of("signed-publisher"), Map.of()));
    String command = JSON.writeValueAsString(Map.of("source", "Local", "userId", "signed-publisher",
        "publicKey", pem, "enabled", true));
    String generic = security.createApiKey(new ApiKeyCommand("GenericToken", "Local", "signed-publisher",
        "Restricted", "ACTIVE", List.of(), null, null, null)).token();
    assertEquals(403, send(client, "PUT", base(first) + "/internal/ohpm/login/keys", "Bearer " + generic, command).statusCode());
    var registration = send(client, "PUT", base(first) + "/internal/ohpm/login/keys", "Bearer " + adminToken, command);
    assertEquals(200, registration.statusCode(), registration.body());
    String publishId = JSON.readTree(registration.body()).path("publishId").asText();
    var login = send(client, "POST", base(second) + "/repository/ohpm/login", null,
        signed(key, publishId, Instant.now().toEpochMilli(), nonce()));
    assertEquals(200, login.statusCode(), login.body());
    String token = JSON.readTree(login.body()).path("token").asText();
    String metadata = "/repository/ohpm/@scope%2fexample";
    assertEquals(200, send(client, "GET", base(first) + metadata, token, null).statusCode());
    assertEquals(403, send(client, "GET", base(second) + "/repository/ohpm/@scope%2fforbidden", token, null).statusCode());
    var published = send(client, "PUT", base(first) + metadata, token, OhpmHostedRuntimeTest.publication("9.0.0"));
    assertEquals(200, published.statusCode(), published.body());
    security.saveRole(new RoleCommand("signed-publisher", "Local", "Signed publisher", "", false,
        List.of(), List.of(), Map.of()));
    assertEquals(403, send(client, "GET", base(second) + metadata, token, null).statusCode());
    assertEquals(403, send(client, "PUT", base(second) + metadata, token, OhpmHostedRuntimeTest.publication("9.1.0")).statusCode());
  }

  private static void verifyClient(Path directory, String firstBase, String secondBase,
      KeyPair key, String publishId, String token) throws Exception {
    Path project = Files.createDirectory(directory.resolve("signed-client"));
    String passphrase = UUID.randomUUID().toString();
    byte[] salt = new byte[8];
    new SecureRandom().nextBytes(salt);
    String algorithm = "PBEWithSHA1AndDESede";
    var encryptionKey = SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(passphrase.toCharArray()));
    Cipher cipher = Cipher.getInstance(algorithm);
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new PBEParameterSpec(salt, 2048));
    byte[] encrypted = new EncryptedPrivateKeyInfo(cipher.getParameters(),
        cipher.doFinal(key.getPrivate().getEncoded())).getEncoded();
    Path privateKey = project.resolve("signing-key.pem");
    String pemLabel = "ENCRYPTED PRIVATE KEY";
    Files.writeString(privateKey, "-----BEGIN " + pemLabel + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encrypted)
        + "\n-----END " + pemLabel + "-----\n");
    String registry = firstBase + "/repository/ohpm/";
    Files.writeString(project.resolve(".ohpmrc"), """
        registry=%s
        publish_registry=%s
        publish_id=%s
        key_path=%s
        cache=%s
        log_level=error
        fetch_timeout=10000
        retry_times=0
        http_proxy=
        https_proxy=
        no_proxy=127.0.0.1,localhost
        """.formatted(registry, registry, publishId, privateKey, project.resolve("cache")));
    Path archive = OhpmHostedRuntimeTest.archive(project, "8.0.0", false);
    Path output = project.resolve("signed-publish.log");
    var command = List.of("python3", Path.of("../scripts/ci/ohpm-signing-client.py").toAbsolutePath().normalize().toString(),
        System.getProperty("kkrepo.ohpm.command", "ohpm"), "publish", archive.toString(), "--publish_registry", registry);
    Process process = new ProcessBuilder(command).directory(project.toFile())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    process.getOutputStream().write((passphrase + "\n").getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertTrue(finished, "OHPM signed publication timed out");
    assertEquals(0, process.exitValue(), Files.readString(output).replace(token, "<redacted>")
        .replace(passphrase, "<redacted>"));
    URI replica = URI.create(secondBase + "/repository/ohpm/");
    Files.writeString(project.resolve(".ohpmrc"), """
        registry=%s
        //%s%s:_auth=%s
        cache=%s
        log_level=error
        http_proxy=
        https_proxy=
        no_proxy=127.0.0.1,localhost
        """.formatted(replica, replica.getAuthority(), replica.getPath(), token, project.resolve("cache")));
    Files.writeString(project.resolve("oh-package.json5"), """
        {"name":"signed-consumer","version":"1.0.0","dependencies":{"@kkrepo/runtime":"8.0.0"}}
        """);
    OhpmHostedRuntimeTest.client(project, token, "install", "--registry", replica.toString());
    assertTrue(Files.exists(project.resolve("oh_modules/@kkrepo/runtime/Index.ets")));
  }

  private static String signed(KeyPair key, String publishId, long timestamp, String nonce) throws Exception {
    var signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key.getPrivate());
    signer.update(("v1-" + publishId + "-" + timestamp + "-" + nonce).getBytes(StandardCharsets.UTF_8));
    return JSON.writeValueAsString(Map.of("version", "v1", "publishId", publishId, "timestamp", timestamp,
        "nonce", nonce, "signature", Base64.getEncoder().encodeToString(signer.sign())));
  }

  private static String nonce() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static HttpRequest request(String method, String url, String token, String body) {
    var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", token);
    return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private static HttpResponse<String> send(HttpClient client, String method, String url, String token, String body)
      throws Exception {
    return client.send(request(method, url, token, body), HttpResponse.BodyHandlers.ofString());
  }

  private static String base(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }
}
