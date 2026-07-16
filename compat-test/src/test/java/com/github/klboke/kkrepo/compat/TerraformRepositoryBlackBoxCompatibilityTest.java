package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.Test;

/** Black-box contract pinned to Nexus' Terraform hosted and group HTTP behavior. */
class TerraformRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static volatile String nexusPrivateKey;

  @Test
  void preparesNexusHostedMigrationFixtureWhenConfigured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to prepare Terraform migration data");
    ensureNexus(config);
    String version = "1.0." + System.currentTimeMillis();
    byte[] module = zip("main.tf", "output \"message\" { value = \"terraform migrated\" }\n"
        .getBytes(StandardCharsets.UTF_8));
    byte[] provider = zip("terraform-provider-fixture_v" + version,
        "#!/bin/sh\nexit 1\n".getBytes(StandardCharsets.UTF_8));
    assert2xx("Nexus migration module upload", put(
        config.nexusRepository(config.hosted),
        "v1/modules/kkrepo/fixture/aws/" + version + "/fixture.zip",
        module, null, config.nexusAuth));
    assert2xx("Nexus migration provider upload", put(
        config.nexusRepository(config.hosted),
        "v1/providers/kkrepo/fixture/" + version + "/download/linux/amd64",
        provider, "terraform-provider-fixture_" + version + "_linux_amd64.zip", config.nexusAuth));
  }

  @Test
  void hostedModuleAndProviderContractsMatchNexusWhenConfigured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Terraform compatibility");
    ensureNexus(config);
    ensureKkRepo(config);

    String version = "1.0." + System.currentTimeMillis();
    byte[] module = zip("main.tf", "output \"message\" { value = \"terraform compat\" }\n".getBytes(StandardCharsets.UTF_8));
    byte[] provider = zip("terraform-provider-fixture_v" + version,
        "#!/bin/sh\nexit 1\n".getBytes(StandardCharsets.UTF_8));
    String modulePath = "v1/modules/kkrepo/fixture/aws/" + version + "/fixture.zip";
    String providerPath = "v1/providers/kkrepo/fixture/" + version + "/download/linux/amd64";
    String providerFilename = "terraform-provider-fixture_" + version + "_linux_amd64.zip";

    Exchange nexusModulePut = put(
        config.nexusRepository(config.hosted), modulePath, module, null, config.nexusAuth);
    Exchange kkrepoModulePut = put(
        config.kkrepoRepository(config.hosted), modulePath, module, null, config.kkrepoAuth);
    assertEquals(nexusModulePut.status, kkrepoModulePut.status, "module upload status");
    assert2xx("Nexus module upload", nexusModulePut);

    Exchange nexusVersions = get(config.nexusRepository(config.group),
        "v1/modules/kkrepo/fixture/aws/versions", config.nexusAuth);
    Exchange kkrepoVersions = get(config.kkrepoRepository(config.group),
        "v1/modules/kkrepo/fixture/aws/versions", config.kkrepoAuth);
    assertEquals(nexusVersions.status, kkrepoVersions.status, "module versions status");
    assertTrue(nexusVersions.text().contains(version));
    assertTrue(kkrepoVersions.text().contains(version));

    Exchange nexusDownload = get(config.nexusRepository(config.group),
        "v1/modules/kkrepo/fixture/aws/" + version + "/download", config.nexusAuth);
    Exchange kkrepoDownload = get(config.kkrepoRepository(config.group),
        "v1/modules/kkrepo/fixture/aws/" + version + "/download", config.kkrepoAuth);
    assertEquals(204, nexusDownload.status);
    assertEquals(nexusDownload.status, kkrepoDownload.status, "module download status");
    assertTrue(nexusDownload.header("x-terraform-get").contains("/repository/" + config.group + "/"));
    assertTrue(kkrepoDownload.header("x-terraform-get").contains("/repository/" + config.group + "/"));
    String nexusModuleUrl = config.resolve(
        nexusDownload.header("x-terraform-get"), config.nexusRepository(config.group));
    String kkrepoModuleUrl = config.resolve(
        kkrepoDownload.header("x-terraform-get"), config.kkrepoRepository(config.group));
    assertArrayEquals(module, getAbsolute(nexusModuleUrl, config.nexusAuth).body);
    assertArrayEquals(module, getAbsolute(kkrepoModuleUrl, config.kkrepoAuth).body);
    assertDownloadValidatorsAndRangeParity(
        nexusModuleUrl, config.nexusAuth, kkrepoModuleUrl, config.kkrepoAuth,
        nexusModuleUrl.replace("/" + config.group + "/", "/" + config.hosted + "/"),
        kkrepoModuleUrl.replace("/" + config.group + "/", "/" + config.hosted + "/"),
        module);

    Exchange nexusProviderPut = put(config.nexusRepository(config.hosted), providerPath, provider,
        providerFilename, config.nexusAuth);
    Exchange kkrepoProviderPut = put(config.kkrepoRepository(config.hosted), providerPath, provider,
        providerFilename, config.kkrepoAuth);
    assertEquals(nexusProviderPut.status, kkrepoProviderPut.status, "provider upload status");
    assert2xx("Nexus provider upload", nexusProviderPut);

    String providerVersionsPath = "v1/providers/kkrepo/fixture/versions";
    Exchange nexusProviderVersions = get(
        config.nexusRepository(config.group), providerVersionsPath, config.nexusAuth);
    Exchange kkrepoProviderVersions = get(
        config.kkrepoRepository(config.group), providerVersionsPath, config.kkrepoAuth);
    assertEquals(nexusProviderVersions.status, kkrepoProviderVersions.status,
        "provider versions status");
    assertEquals(200, kkrepoProviderVersions.status);
    Map<String, Object> nexusProviderVersionsBody =
        JSON.readValue(nexusProviderVersions.body, new TypeReference<>() {});
    Map<String, Object> kkrepoProviderVersionsBody =
        JSON.readValue(kkrepoProviderVersions.body, new TypeReference<>() {});
    assertEquals(nexusProviderVersionsBody.get("id"), kkrepoProviderVersionsBody.get("id"));
    assertEquals(nexusProviderVersionsBody.get("warnings"),
        kkrepoProviderVersionsBody.get("warnings"));
    assertTrue(kkrepoProviderVersions.text().contains(version));

    Exchange nexusProviderVersionsAlias = get(
        config.nexusRepository(config.group), providerVersionsPath + ".json", config.nexusAuth);
    Exchange kkrepoProviderVersionsAlias = get(
        config.kkrepoRepository(config.group), providerVersionsPath + ".json", config.kkrepoAuth);
    assertEquals(nexusProviderVersions.status, nexusProviderVersionsAlias.status);
    assertEquals(kkrepoProviderVersions.status, kkrepoProviderVersionsAlias.status);
    assertEquals(nexusProviderVersionsBody,
        JSON.readValue(nexusProviderVersionsAlias.body, new TypeReference<>() {}));
    assertEquals(kkrepoProviderVersionsBody,
        JSON.readValue(kkrepoProviderVersionsAlias.body, new TypeReference<>() {}));

    validateProvider(config, config.nexusRepository(config.group), config.nexusAuth,
        providerPath, providerFilename, provider);
    validateProvider(config, config.kkrepoRepository(config.group), config.kkrepoAuth,
        providerPath, providerFilename, provider);

    Exchange badNexus = get(config.nexusRepository(config.group),
        "v1/providers/kkrepo/fixture/" + version + "/download/plan9/amd64", config.nexusAuth);
    Exchange badKkRepo = get(config.kkrepoRepository(config.group),
        "v1/providers/kkrepo/fixture/" + version + "/download/plan9/amd64", config.kkrepoAuth);
    assertEquals(badNexus.status, badKkRepo.status, "missing platform status");
    assertEquals(404, badKkRepo.status);
  }

  @Test
  void missingHostedAndGroupPackagesMatchNexusNotFoundBehaviorWhenConfigured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Terraform compatibility");
    ensureNexus(config);
    ensureKkRepo(config);

    String namespace = "missing" + System.currentTimeMillis();
    String[] paths = {
        "v1/modules/" + namespace + "/fixture/aws/versions",
        "v1/providers/" + namespace + "/fixture/versions"
    };
    for (String repository : new String[] {config.hosted, config.group}) {
      for (String path : paths) {
        Exchange nexus = get(config.nexusRepository(repository), path, config.nexusAuth);
        Exchange kkrepo = get(config.kkrepoRepository(repository), path, config.kkrepoAuth);
        assertEquals(nexus.status, kkrepo.status,
            "missing Terraform package status for " + repository + "/" + path);
        assertEquals(404, nexus.status,
            "Nexus missing Terraform package status for " + repository + "/" + path);
      }
    }
  }

  @Test
  void explicitProviderProtocolsArePersistedWhenConfigured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Terraform compatibility");
    ensureKkRepo(config);

    String version = "1.0." + System.currentTimeMillis();
    String path = "v1/providers/kkrepo/protocol6/" + version + "/download/linux/amd64";
    String filename = "terraform-provider-protocol6_" + version + "_linux_amd64.zip";
    byte[] provider = zip("terraform-provider-protocol6_v" + version,
        "#!/bin/sh\nexit 1\n".getBytes(StandardCharsets.UTF_8));
    assert2xx("kkrepo protocol 6 provider upload", put(
        config.kkrepoRepository(config.hosted), path, provider, filename, config.kkrepoAuth, "6.0"));

    Exchange metadata = get(config.kkrepoRepository(config.hosted), path, config.kkrepoAuth);
    assertEquals(200, metadata.status, metadata.text());
    Map<String, Object> body = JSON.readValue(metadata.body, new TypeReference<>() {});
    assertEquals(List.of("6.0"), body.get("protocols"));
    Exchange versions = get(
        config.kkrepoRepository(config.hosted),
        "v1/providers/kkrepo/protocol6/versions",
        config.kkrepoAuth);
    assertEquals(200, versions.status, versions.text());
    assertTrue(versions.text().contains("\"protocols\":[\"6.0\"]"), versions.text());
  }

  private static void validateProvider(
      Config config, String repository, String authorization, String path, String filename,
      byte[] expectedArchive) throws Exception {
    Exchange metadata = get(repository, path, authorization);
    assertEquals(200, metadata.status, metadata.text());
    Map<String, Object> body = JSON.readValue(metadata.body, new TypeReference<>() {});
    assertEquals(filename, body.get("filename"));
    assertEquals(List.of("5.0"), body.get("protocols"));
    String shasum = body.get("shasum").toString();
    assertEquals(sha256(expectedArchive), shasum);
    String[] coordinates = path.split("/");
    assertProviderAssetPath(
        body.get("download_url").toString(),
        coordinates[2], coordinates[3], coordinates[4], coordinates[6], coordinates[7], filename);
    assertProviderAssetPath(
        body.get("shasums_url").toString(),
        coordinates[2], coordinates[3], coordinates[4], coordinates[6], coordinates[7],
        "SHA256SUMS");
    assertProviderAssetPath(
        body.get("shasums_signature_url").toString(),
        coordinates[2], coordinates[3], coordinates[4], coordinates[6], coordinates[7],
        "SHA256SUMS.sig");
    byte[] archive = getAbsolute(
        config.resolve(body.get("download_url").toString(), repository), authorization).body;
    assertArrayEquals(expectedArchive, archive);
    String sums = getAbsolute(
        config.resolve(body.get("shasums_url").toString(), repository), authorization).text();
    assertTrue(sums.lines().anyMatch(line -> line.trim().equalsIgnoreCase(shasum + "  " + filename)));
    Exchange signature = getAbsolute(config.resolve(
        body.get("shasums_signature_url").toString(), repository), authorization);
    assert2xx("provider signature", signature);
    assertTrue(signature.body.length > 32);
    @SuppressWarnings("unchecked")
    Map<String, Object> signingKeys = (Map<String, Object>) body.get("signing_keys");
    assertFalse(signingKeys.isEmpty());
    assertTrue(signingKeys.toString().contains("BEGIN PGP PUBLIC KEY BLOCK"));
  }

  private static void assertProviderAssetPath(
      String url,
      String namespace,
      String type,
      String version,
      String os,
      String arch,
      String filename) {
    String path = URI.create(url).getPath();
    String marker = "/v1/providers/";
    int markerIndex = path.indexOf(marker);
    assertTrue(markerIndex >= 0, url);
    List<String> segments = List.of(path.substring(markerIndex + marker.length()).split("/"));
    int offset = segments.size() - 7;
    assertTrue(offset == 0 || offset == 1,
        "provider URL must contain only an optional URL-token segment: " + url);
    assertEquals(namespace, segments.get(offset), url);
    assertEquals(type, segments.get(offset + 1), url);
    assertEquals(version, segments.get(offset + 2), url);
    assertEquals("download", segments.get(offset + 3), url);
    assertEquals(os, segments.get(offset + 4), url);
    assertEquals(arch, segments.get(offset + 5), url);
    assertEquals(filename, segments.get(offset + 6), url);
  }

  private static void assertDownloadValidatorsAndRangeParity(
      String nexusUrl, String nexusAuthorization, String kkrepoUrl, String kkrepoAuthorization,
      String nexusHostedUrl, String kkrepoHostedUrl, byte[] expected) throws Exception {
    Exchange nexusHead = send(HttpRequest.newBuilder(URI.create(nexusUrl))
        .header("Authorization", nexusAuthorization).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    Exchange kkrepoHead = send(HttpRequest.newBuilder(URI.create(kkrepoUrl))
        .header("Authorization", kkrepoAuthorization).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(404, nexusHead.status, "Nexus 3.92 Terraform group does not dispatch archive HEAD");
    assertEquals(nexusHead.status, kkrepoHead.status, "group archive HEAD status");

    Exchange nexusRange = send(HttpRequest.newBuilder(URI.create(nexusUrl))
        .header("Authorization", nexusAuthorization).header("Range", "bytes=0-3").GET());
    Exchange kkrepoRange = send(HttpRequest.newBuilder(URI.create(kkrepoUrl))
        .header("Authorization", kkrepoAuthorization).header("Range", "bytes=0-3").GET());
    assertEquals(nexusRange.status, kkrepoRange.status, "archive Range status");
    assertEquals(200, kkrepoRange.status, "Nexus 3.92 Terraform facet ignores Range");
    assertArrayEquals(expected, nexusRange.body);
    assertArrayEquals(expected, kkrepoRange.body);

    Exchange nexusHostedHead = send(HttpRequest.newBuilder(URI.create(nexusHostedUrl))
        .header("Authorization", nexusAuthorization).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    Exchange kkrepoHostedHead = send(HttpRequest.newBuilder(URI.create(kkrepoHostedUrl))
        .header("Authorization", kkrepoAuthorization).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(nexusHostedHead.status, kkrepoHostedHead.status, "hosted archive HEAD status");
    assertEquals(Integer.toString(expected.length), nexusHostedHead.header("content-length"));
    assertEquals(Integer.toString(expected.length), kkrepoHostedHead.header("content-length"));
    assertFalse(nexusHostedHead.header("etag").isBlank());
    assertFalse(kkrepoHostedHead.header("etag").isBlank());
    assertFalse(nexusHostedHead.header("last-modified").isBlank());
    assertFalse(kkrepoHostedHead.header("last-modified").isBlank());

    Exchange nexusConditional = send(HttpRequest.newBuilder(URI.create(nexusHostedUrl))
        .header("Authorization", nexusAuthorization)
        .header("If-None-Match", nexusHostedHead.header("etag")).GET());
    Exchange kkrepoConditional = send(HttpRequest.newBuilder(URI.create(kkrepoHostedUrl))
        .header("Authorization", kkrepoAuthorization)
        .header("If-None-Match", kkrepoHostedHead.header("etag")).GET());
    assertEquals(304, nexusConditional.status);
    assertEquals(nexusConditional.status, kkrepoConditional.status, "archive conditional GET status");
  }

  private static void ensureNexus(Config config) throws Exception {
    String repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).text();
    if (!repositories.contains("\"name\" : \"" + config.hosted + "\"")) {
      assert2xx("create Nexus Terraform hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/terraform/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
              "name", config.hosted,
              "online", true,
              "storage", Map.of(
                  "blobStoreName", "default",
                  "strictContentTypeValidation", true,
                  "writePolicy", "ALLOW_ONCE"),
              "terraformSigning", Map.of(
                  "keypair", nexusPrivateKey(),
                  "passphrase", "")))))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).text();
    if (!repositories.contains("\"name\" : \"" + config.proxy + "\"")) {
      assert2xx("create Nexus Terraform proxy", send(config.nexusAdmin(
          "/service/rest/v1/repositories/terraform/proxy")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"proxy":{"remoteUrl":"https://registry.terraform.io/","contentMaxAge":1440,"metadataMaxAge":1440},"negativeCache":{"enabled":true,"timeToLive":1440},"httpClient":{"blocked":false,"autoBlock":true}}
              """.formatted(config.proxy)))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).text();
    if (!repositories.contains("\"name\" : \"" + config.group + "\"")) {
      assert2xx("create Nexus Terraform group", send(config.nexusAdmin(
          "/service/rest/v1/repositories/terraform/group")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"group":{"memberNames":["%s","%s"]}}
              """.formatted(config.group, config.hosted, config.proxy)))));
    }
  }

  private static void ensureKkRepo(Config config) throws Exception {
    String repositories = send(config.kkrepoAdmin("/internal/repositories").GET()).text();
    if (!repositories.contains("\"name\":\"" + config.hosted + "\"")) {
      assert2xx("create kkrepo Terraform hosted", send(config.kkrepoAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"terraform-hosted","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW_ONCE"}}
              """.formatted(config.hosted)))));
    }
    repositories = send(config.kkrepoAdmin("/internal/repositories").GET()).text();
    if (!repositories.contains("\"name\":\"" + config.proxy + "\"")) {
      assert2xx("create kkrepo Terraform proxy", send(config.kkrepoAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"terraform-proxy","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"proxy":{"remoteUrl":"https://registry.terraform.io/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}}
              """.formatted(config.proxy)))));
    }
    repositories = send(config.kkrepoAdmin("/internal/repositories").GET()).text();
    if (!repositories.contains("\"name\":\"" + config.group + "\"")) {
      assert2xx("create kkrepo Terraform group", send(config.kkrepoAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"terraform-group","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"group":{"memberNames":["%s","%s"]}}
              """.formatted(config.group, config.hosted, config.proxy)))));
    }
  }

  private static Exchange put(
      String repository, String path, byte[] body, String filename, String authorization) throws Exception {
    return put(repository, path, body, filename, authorization, null);
  }

  private static Exchange put(
      String repository, String path, byte[] body, String filename, String authorization,
      String providerProtocols) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(repository + "/" + path))
        .header("Content-Type", "application/zip")
        .header("Authorization", authorization);
    if (filename != null) request.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
    if (providerProtocols != null) {
      request.header("X-Terraform-Provider-Protocols", providerProtocols);
    }
    return send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange get(String repository, String path, String authorization) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(repository + "/" + path))
        .header("Authorization", authorization).GET());
  }

  private static Exchange getAbsolute(String url, String authorization) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", authorization).GET());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(request.timeout(Duration.ofSeconds(120)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static byte[] zip(String name, byte[] content) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static synchronized String nexusPrivateKey() throws Exception {
    if (nexusPrivateKey != null) return nexusPrivateKey;
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
    generator.initialize(2048);
    PGPKeyPair pair = new JcaPGPKeyPair(
        PGPPublicKey.RSA_SIGN, generator.generateKeyPair(), new Date());
    PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME).build().get(HashAlgorithmTags.SHA1);
    PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
    certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
    PGPKeyRingGenerator rings = new PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        pair,
        "kkrepo Terraform compatibility <terraform-compat@kkrepo.test>",
        sha1,
        certification.generate(),
        null,
        new JcaPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME),
        new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(new char[0]));
    ByteArrayOutputStream armored = new ByteArrayOutputStream();
    try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
      rings.generateSecretKeyRing().encode(output);
    }
    nexusPrivateKey = armored.toString(StandardCharsets.UTF_8);
    // Exercise the same parser used by registry clients before sending the fixture key to Nexus.
    new org.bouncycastle.openpgp.PGPSecretKeyRingCollection(
        org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
            new java.io.ByteArrayInputStream(nexusPrivateKey.getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    return nexusPrivateKey;
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status >= 200 && exchange.status < 300,
        label + " expected 2xx but got " + exchange.status + " body=" + exchange.text());
  }

  private record Exchange(int status, byte[] body, Map<String, java.util.List<String>> headers) {
    String text() { return new String(body, StandardCharsets.UTF_8); }
    String header(String name) {
      return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
    }
  }

  private record Config(
      String nexusBase, String kkrepoBase, String nexusAuth, String kkrepoAuth,
      String hosted, String proxy, String group) {
    static Config load() {
      return new Config(
          CompatDefaults.nexusBaseUrl().orElse(""), CompatDefaults.nexusPlusBaseUrl().orElse(""),
          basic(CompatDefaults.nexusUsername().orElse(""), CompatDefaults.nexusPassword().orElse("")),
          basic(CompatDefaults.nexusPlusUsername().orElse(""), CompatDefaults.nexusPlusPassword().orElse("")),
          "terraform-compat-hosted", "terraform-compat-proxy", "terraform-compat-group");
    }

    boolean configured() { return !nexusBase.isBlank() && !kkrepoBase.isBlank(); }
    String nexusRepository(String repository) { return nexusBase + "/repository/" + repository; }
    String kkrepoRepository(String repository) { return kkrepoBase + "/repository/" + repository; }
    HttpRequest.Builder nexusAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(nexusBase + path)).header("Authorization", nexusAuth);
    }
    HttpRequest.Builder kkrepoAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(kkrepoBase + path)).header("Authorization", kkrepoAuth);
    }
    String resolve(String value, String repositoryBase) {
      if (value.startsWith("http://") || value.startsWith("https://")) return value;
      return URI.create(repositoryBase + "/").resolve(value).toString();
    }
    private static String basic(String user, String password) {
      return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
  }
}
