package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Live public-CDN checks. Requires disposable Nexus/kkRepo, internet access and the real clients.
 * Fixture repositories and Docker images remain available for diagnostics until stack teardown. */
class PublicHttpsContentRedirectCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  // Never follow a test-side redirect with the configured admin Authorization header.
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  @Test
  void goClientDownloadsPublicHttpsZipAndMatchesNexus(@TempDir Path temp) throws Exception {
    requireLive("go");
    var repos = repositories("go", "https://proxy.golang.org/", "https://proxy.golang.org/");
    String path = "github.com/gorilla/mux/@v/v1.8.1.zip";
    byte[] candidate = get(candidate() + "/repository/" + repos.candidate + "/" + path, false, null);
    byte[] reference = get(reference() + "/repository/" + repos.reference + "/" + path, true, null);
    assertArrayEquals(reference, candidate);
    var env = Map.of("GOPROXY", candidate() + "/repository/" + repos.candidate + "/",
        "GOSUMDB", "off", "GOMODCACHE", temp.resolve("modules").toString(),
        "GOCACHE", temp.resolve("build").toString(), "GOTOOLCHAIN", "local");
    run(temp, env, null, "go", "mod", "download", "-json", "github.com/gorilla/mux@v1.8.1");
    assertArrayEquals(candidate, Files.readAllBytes(temp.resolve("modules/cache/download/" + path)));
  }

  @Test
  void helmClientDownloadsMetadataSelectedGithubChartAndMatchesNexus(@TempDir Path temp) throws Exception {
    requireLive("helm");
    var repos = repositories("helm", "https://kubernetes.github.io/ingress-nginx/", "https://kubernetes.github.io/ingress-nginx/");
    String path = "ingress-nginx-4.12.0.tgz";
    run(temp, Map.of("HELM_CACHE_HOME", temp.resolve("cache").toString(),
        "HELM_CONFIG_HOME", temp.resolve("config").toString(), "HELM_DATA_HOME", temp.resolve("data").toString()),
        null, "helm", "pull", "ingress-nginx", "--version", "4.12.0", "--repo",
        candidate() + "/repository/" + repos.candidate + "/", "--username", candidateUser(), "--password", candidatePassword());
    byte[] downloaded = Files.readAllBytes(temp.resolve(path));
    byte[] expected = get(reference() + "/repository/" + repos.reference + "/" + path, true, null);
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
        MessageDigest.getInstance("SHA-256").digest(downloaded));
  }

  @Test
  void dockerClientPullsPublicHttpsLayersAndManifestMatchesNexus(@TempDir Path temp) throws Exception {
    requireLive("docker");
    var repos = repositories("docker", "https://registry-1.docker.io", "https://registry-1.docker.io");
    String accept = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json";
    byte[] candidateManifest = get(candidate() + "/v2/" + repos.candidate + "/library/alpine/manifests/3.12.0", false, accept);
    byte[] referenceManifest = get(reference() + "/repository/" + repos.reference + "/v2/library/alpine/manifests/3.12.0", true, accept);
    assertEquals(JSON.readTree(referenceManifest), JSON.readTree(candidateManifest));
    String registry = URI.create(candidate()).getAuthority();
    var env = Map.of("DOCKER_CONFIG", temp.resolve("docker").toString());
    run(temp, env, candidatePassword(), "docker", "login", registry, "--username", candidateUser(), "--password-stdin");
    String image = registry + "/" + repos.candidate + "/library/alpine:3.12.0";
    run(temp, env, null, "docker", "pull", "--platform=linux/amd64", image);
    run(temp, env, null, "docker", "image", "inspect", image);
    run(temp, env, null, "docker", "image", "rm", image);
  }

  private static Repositories repositories(String format, String upstream, String nexusUpstream) throws Exception {
    String name = "public-https-" + format + "-" + System.nanoTime();
    var candidate = new LinkedHashMap<String, Object>(Map.of("name", name, "recipe", format + "-proxy",
        "online", true, "blobStoreName", "default", "proxy", Map.of("remoteUrl", upstream,
        "autoBlock", false, "allowedRedirectHosts", List.of("*"))));
    if (format.equals("docker")) candidate.put("docker", Map.of("connectorEnabled", false));
    admin(candidate() + "/internal/repositories", false, candidate);
    var nexus = new LinkedHashMap<String, Object>(Map.of("name", name, "online", true,
        "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false),
        "proxy", Map.of("remoteUrl", nexusUpstream, "contentMaxAge", 1440, "metadataMaxAge", 1440),
        "negativeCache", Map.of("enabled", false, "timeToLive", 1),
        "httpClient", Map.of("blocked", false, "autoBlock", false)));
    if (format.equals("docker")) {
      nexus.put("docker", Map.of("v1Enabled", false, "forceBasicAuth", false));
      nexus.put("dockerProxy", Map.of("indexType", "HUB", "indexUrl", "https://index.docker.io"));
    }
    admin(reference() + "/service/rest/v1/repositories/" + format + "/proxy", true, nexus);
    return new Repositories(name, name);
  }

  private static void admin(String url, boolean reference, Map<String, Object> body) throws Exception {
    var response = HTTP.send(request(url, reference).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
        "Create disposable repository failed with HTTP " + response.statusCode());
  }

  private static byte[] get(String url, boolean reference, String accept) throws Exception {
    var request = request(url, reference);
    if (accept != null) request.header("Accept", accept);
    var response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), "Repository content request failed");
    return response.body();
  }

  private static HttpRequest.Builder request(String url, boolean reference) {
    String credentials = reference ? CompatDefaults.nexusUsername().orElseThrow() + ":" + CompatDefaults.nexusPassword().orElseThrow()
        : candidateUser() + ":" + candidatePassword();
    return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static void requireLive(String client) throws Exception {
    assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("COMPAT_WRITE_ENABLED", "false")), "Requires disposable write-enabled reference and candidate");
    // Missing client is an explicit skip; a failed installed client is a failed test.
    assumeTrue(new ProcessBuilder("sh", "-c", "command -v " + client).start().waitFor() == 0, "Requires " + client);
  }

  private static void run(Path directory, Map<String, String> env, String input, String... command) throws Exception {
    Path output = Files.createTempFile(directory, "client-", ".log");
    var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().putAll(env);
    var process = builder.start();
    try (var stdin = process.getOutputStream()) {
      if (input != null) stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    boolean done = process.waitFor(180, TimeUnit.SECONDS);
    if (!done) process.destroyForcibly();
    String diagnostic = Files.readString(output).replace(candidatePassword(), "[redacted]")
        .replace(CompatDefaults.nexusPassword().orElseThrow(), "[redacted]");
    Files.writeString(output, diagnostic);
    assertTrue(done, command[0] + " timed out");
    assertEquals(0, process.exitValue(), command[0] + " failed: " + diagnostic);
  }

  private static String candidate() { return CompatDefaults.nexusPlusBaseUrl().orElseThrow(); }
  private static String reference() { return CompatDefaults.nexusBaseUrl().orElseThrow(); }
  private static String candidateUser() { return CompatDefaults.nexusPlusUsername().orElseThrow(); }
  private static String candidatePassword() { return CompatDefaults.nexusPlusPassword().orElseThrow(); }
  private record Repositories(String candidate, String reference) {}
}
