package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RoutingRulesBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15)).build();

  @ParameterizedTest
  @ValueSource(strings = {"swift", "pub", "cargo", "nuget", "r", "apt", "alpine", "conda", "yum", "rubygems", "ansiblegalaxy", "docker"})
  void canonicalPackageAliasesAreBlockedBeforeUpstreamAccess(String format) throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.routingRules", "ROUTING_RULES_COMPAT_ENABLED").orElse("false")));
    assumeTrue(!Boolean.getBoolean("compat.routingRules.referenceOnly"));
    Endpoint e = new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
        CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false, format);
    String prefix = "rr452-" + UUID.randomUUID().toString().substring(0, 8);
    String rule = prefix + "-rule", proxy = prefix + "-proxy", rules = "/service/rest/v1/routing-rules";
    List<String> matchers = switch (format) {
      case "swift" -> List.of("/private/.*");
      case "pub" -> List.of("/api/packages/private", "/api/packages/private/versions/1.0.0");
      case "cargo" -> List.of("/api/v1/crates/private/.*");
      case "ansiblegalaxy" -> List.of("/api/v3/collections/private/pkg/versions");
      case "docker" -> List.of("/v2/private/pkg/blobs/sha256:" + "a".repeat(64), "/v2/private/pkg/tags/list");
      case "r" -> List.of("/src/contrib/private_.*\\.tar\\.gz", "/src/contrib/PACKAGES");
      case "apt" -> List.of("/dists/stable/Release");
      case "alpine" -> List.of("/v3.20/main/x86_64/APKINDEX.tar.gz");
      case "conda" -> List.of("/linux-64/repodata.json");
      case "yum" -> List.of("/private.rpm");
      case "rubygems" -> List.of("/gems/private-1.0.0.gem");
      default -> List.of("/v3-flatcontainer/private/.*", "/v3/registration5-semver[12]/private/.*");
    };
    List<String> aliases = switch (format) {
      case "swift" -> List.of("PRIVATE/pkg/1.0.0.zip", "PRIVATE/PKG/1.0.0/Package.swift", "PRIVATE/pkg.json");
      case "pub" -> List.of("api/packages/private/", "api/packages/private/versions/1.0.0/");
      case "cargo" -> List.of("api/v1/crates/PRIVATE/1.0.0/download", "api/v1/crates/%20private/1.0.0/download");
      case "ansiblegalaxy" -> List.of("api/v3/collections/private/pkg/versions/");
      case "docker" -> List.of("private/pkg/blobs/SHA256:" + "A".repeat(64), "private/pkg/tags/list/");
      case "r" -> List.of("src/contrib/private_1.0.tar.gz/", "src/contrib/PACKAGES/");
      case "apt" -> List.of("dists/stable/Release/");
      case "alpine" -> List.of("v3.20/main/x86_64/APKINDEX.tar.gz/");
      case "conda" -> List.of("linux-64/repodata.json/");
      case "yum" -> List.of("private.rpm/");
      case "rubygems" -> List.of("gems/private-1.0.0.gem/");
      default -> List.of("v3-flatcontainer/PRIVATE/index.json", "v3/registration5-semver1/PRIVATE/index.json",
          "v3/registration5-semver2/PRIVATE/index.json");
    };
    try {
      check(e, "POST", rules, rule(rule, "BLOCK", matchers), 204);
      e.create(proxy, "proxy", format.equals("swift") ? "https://github.com/" : e.upstreamBase() + "/repository/" + prefix + "-missing/", rule, List.of());
      boolean first = true;
      for (String alias : aliases) {
        String url = (format.equals("docker") ? "/v2/" : "/repository/") + proxy + "/" + alias;
        if (first) awaitCheck(e, url, 403);
        else check(e, "GET", url, null, 403);
        first = false;
        check(e, "HEAD", url, null, 403);
      }
    } finally {
      e.removeRepository(proxy);
      e.send("DELETE", rules + "/" + rule, null);
    }
  }

  @Test
  void pypiCanonicalProjectAliasesAreRejectedBeforeUpstreamAccess() throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.routingRules", "ROUTING_RULES_COMPAT_ENABLED").orElse("false")));
    assumeTrue(!Boolean.getBoolean("compat.routingRules.referenceOnly"));
    Endpoint e = new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
        CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false, "pypi");
    String prefix = "rr452-" + UUID.randomUUID().toString().substring(0, 8);
    String rule = prefix + "-rule", proxy = prefix + "-proxy", rules = "/service/rest/v1/routing-rules";
    try {
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("/simple/private/.*", "/simple/private-name/.*")), 204);
      // The missing upstream would return 404 if a request escaped the policy boundary.
      e.create(proxy, "proxy", e.upstreamBase() + "/repository/" + prefix + "-missing/", rule, List.of());
      awaitCheck(e, "/repository/" + proxy + "/simple/private/", 403);
      for (String alias : List.of("private", "private/", "PRIVATE/", "Private__Name", "PRIVATE..NAME/", "private;x=1/")) {
        check(e, "GET", "/repository/" + proxy + "/simple/" + alias, null, 403);
        check(e, "HEAD", "/repository/" + proxy + "/simple/" + alias, null, 403);
      }
    } finally {
      e.removeRepository(proxy);
      e.send("DELETE", rules + "/" + rule, null);
    }
  }

  @Test
  void npmCachedPackageIdentityCannotBypassRoutingRules() throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.routingRules", "ROUTING_RULES_COMPAT_ENABLED").orElse("false")));
    var endpoints = new java.util.ArrayList<Endpoint>();
    endpoints.add(new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
        CompatDefaults.nexusUsername().orElseThrow(), CompatDefaults.nexusPassword().orElseThrow(), true, "npm"));
    if (!Boolean.getBoolean("compat.routingRules.referenceOnly")) {
      endpoints.add(new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false, "npm"));
    }
    for (Endpoint e : endpoints) {
      String prefix = "rr452-" + UUID.randomUUID().toString().substring(0, 8);
      String upstream = prefix + "-upstream", proxy = prefix + "-proxy", group = prefix + "-group";
      String rule = prefix + "-rule", rules = "/service/rest/v1/routing-rules";
      Endpoint raw = new Endpoint(e.base, e.username, e.password, e.nexus, "raw");
      try {
        check(e, "POST", rules, rule(rule, "BLOCK", List.of("/never")), 204);
        raw.create(upstream, "hosted", null, null, List.of());
        String fixture = JSON.writeValueAsString(Map.of("name", "private", "dist-tags", Map.of("latest", "1.0.0"),
            "versions", Map.of("1.0.0", Map.of("name", "private", "version", "1.0.0", "dist",
                Map.of("tarball", "https://example.com/package.tgz")))));
        check(raw, "PUT", "/repository/" + upstream + "/private", fixture, 201);
        e.create(proxy, "proxy", e.upstreamBase() + "/repository/" + upstream + "/", rule, List.of());
        e.create(group, "group", null, null, List.of(proxy));
        assertEquals("private", JSON.readTree(check(e, "GET", "/repository/" + proxy + "/private", null, 200).body()).path("name").asText());
        check(e, "GET", "/repository/" + group + "/private", null, 200);
        check(e, "PUT", rules + "/" + rule, rule(rule, "BLOCK", List.of("/private")), 204);
        awaitCheck(e, "/repository/" + proxy + "/private", 403);
        if (!e.nexus) {
          // Nexus 3.68 can retain its warmed npm group packument after a rule change.
          // kkRepo projects members before serving group metadata and excludes that cache.
          check(e, "GET", "/repository/" + group + "/private", null, 404);
          for (String alias : List.of("%20private", "private%20", "private/")) {
            check(e, "GET", "/repository/" + proxy + "/" + alias, null, 403);
            check(e, "HEAD", "/repository/" + proxy + "/" + alias, null, 403);
            check(e, "GET", "/repository/" + group + "/" + alias, null, 404);
          }
        }
      } finally {
        for (String name : List.of(group, proxy, upstream)) e.removeRepository(name);
        e.send("DELETE", rules + "/" + rule, null);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"raw", "maven2"})
  void managementAndCachedProxyGroupBehaviorMatchNexus(String format) throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.routingRules", "ROUTING_RULES_COMPAT_ENABLED").orElse("false")));
    exercise(new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
        CompatDefaults.nexusUsername().orElseThrow(), CompatDefaults.nexusPassword().orElseThrow(), true, format));
    if (!Boolean.getBoolean("compat.routingRules.referenceOnly")) {
      exercise(new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false, format));
    }
  }

  private void exercise(Endpoint e) throws Exception {
    String prefix = "rr452-" + UUID.randomUUID().toString().substring(0, 8);
    String rule = prefix + "-rule", upstream = prefix + "-upstream", fallback = prefix + "-fallback";
    String proxy = prefix + "-proxy", group = prefix + "-group";
    String rules = "/service/rest/v1/routing-rules";
    try {
      check(e, "POST", rules, rule(rule, "BLOCK", List.of()), 400);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("[")), 400);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("/never")), 204);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("/never")), 400);
      JsonNode saved = JSON.readTree(check(e, "GET", rules + "/" + rule, null, 200).body());
      assertEquals("BLOCK", saved.path("mode").asText());
      assertEquals(List.of("/never"), JSON.convertValue(saved.path("matchers"), List.class));
      assertTrue(JSON.readTree(check(e, "GET", rules, null, 200).body()).toString().contains(rule));
      e.create(upstream, "hosted", null, null, List.of());
      e.create(fallback, "hosted", null, null, List.of());
      check(e, "PUT", "/repository/" + upstream + "/blocked/component/1.0/component-1.0.jar", "upstream", 201);
      check(e, "PUT", "/repository/" + fallback + "/blocked/component/1.0/component-1.0.jar", "fallback", 201);
      check(e, "PUT", "/repository/" + upstream + "/allowed/component/1.0/component-1.0.jar", "allowed", 201);
      if (!e.nexus && e.format.equals("raw")) {
        check(e, "PUT", "/repository/" + upstream + "/allowed;private/item.jar", "private", 201);
        check(e, "PUT", "/repository/" + fallback + "/allowed;private/item.jar", "fallback", 201);
        check(e, "PUT", "/repository/" + upstream + "/allowed/item;public.jar", "public", 201);
      }
      // The server's upstream address may differ from the host-side test client's address.
      String remote = e.upstreamBase() + "/repository/" + upstream + "/";
      e.create(proxy, "proxy", remote, rule, List.of());
      e.create(group, "group", null, null, List.of(proxy, fallback));
      if (!e.nexus && e.format.equals("raw")) {
        check(e, "GET", "/repository/" + proxy + "/allowed;private/item.jar", null, 200);
        assertEquals("private", check(e, "GET", "/repository/" + group + "/allowed;private/item.jar", null, 200).body());
      }
      assertEquals("upstream", check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      assertEquals("upstream", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      check(e, "DELETE", rules + "/" + rule, null, 400);
      check(e, "PUT", rules + "/" + rule, rule(rule, "BLOCK", List.of("/blocked/.*", "/unused")), 204);
      awaitCheck(e, "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", 403);
      check(e, "HEAD", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 403);
      check(e, "GET", "/repository/" + proxy + "/%62locked/component/1.0/component-1.0.jar", null, 403);
      for (String alternate : List.of("//blocked/component/1.0/component-1.0.jar",
          "/allowed/../blocked/component/1.0/component-1.0.jar")) {
        check(e, "GET", "/repository/" + proxy + alternate, null, 403);
        check(e, "HEAD", "/repository/" + proxy + alternate, null, 403);
        if (alternate.startsWith("//")) {
          assertEquals("fallback", check(e, "GET", "/repository/" + group + alternate, null, 200).body());
        }
      }
      assertEquals("fallback", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      assertEquals("allowed", check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar", null, 200).body());
      check(e, "PUT", rules + "/" + rule, rule(rule, "ALLOW", List.of("/allowed/.*\\.jar")), 204);
      awaitCheck(e, "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar?x=1", 403);
      if (!e.nexus && e.format.equals("raw")) {
        // Raw handlers preserve semicolons as asset data, unlike MVC PyPI index paths.
        check(e, "GET", "/repository/" + proxy + "/allowed;private/item.jar", null, 403);
        check(e, "HEAD", "/repository/" + proxy + "/allowed;private/item.jar", null, 403);
        assertEquals("fallback", check(e, "GET", "/repository/" + group + "/allowed;private/item.jar", null, 200).body());
        assertEquals("public", check(e, "GET", "/repository/" + proxy + "/allowed/item;public.jar", null, 200).body());
      }
      check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 403);
      check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar?x=1", null, 403);
      check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar", null, 200);
      check(e, "PUT", rules + "/" + rule, rule(rule, "BLOCK", List.of("blocked")), 204);
      // Java matches(), not substring/find(). Previously warmed contents remain available after unblocking.
      assertEquals("upstream", awaitCheck(e, "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", 200).body());
      assertEquals("upstream", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
    } finally {
      for (String repository : List.of(group, proxy, fallback, upstream)) {
        e.removeRepository(repository);
      }
      e.send("DELETE", rules + "/" + rule, null);
    }
    check(e, "GET", rules + "/" + rule, null, 404);
  }

  private static Map<String, Object> rule(String name, String mode, List<String> matchers) {
    return Map.of("name", name, "description", "Routing compatibility fixture", "mode", mode, "matchers", matchers);
  }

  /** Only transition boundaries poll; subsequent alias/group assertions must pass immediately. */
  private static HttpResponse<String> awaitCheck(Endpoint e, String path, int status) throws Exception {
    if (e.nexus) return check(e, "GET", path, null, status);
    long seconds = Long.parseLong(CompatDefaults.setting("compat.routingRules.convergenceTimeoutSeconds",
        "ROUTING_RULES_CONVERGENCE_TIMEOUT_SECONDS").orElse("45"));
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    HttpResponse<String> response;
    do {
      response = e.send("GET", path, null);
      if (response.statusCode() == status) return response;
      Thread.sleep(200);
    } while (System.nanoTime() < deadline);
    assertEquals(status, response.statusCode(), "Routing policy did not converge: " + path + ": " + response.body());
    return response;
  }

  private static HttpResponse<String> check(Endpoint e, String method, String path, Object body, int status)
      throws Exception {
    HttpResponse<String> response = e.send(method, path, body);
    assertEquals(status, response.statusCode(), e.base + " " + method + " " + path + ": " + response.body());
    return response;
  }

  private record Endpoint(String base, String username, String password, boolean nexus, String format) {
    String upstreamBase() {
      return CompatDefaults.stripTrailingSlash(nexus ? "http://localhost:8081"
          : CompatDefaults.setting("compat.routingRules.kkrepoUpstreamBaseUrl",
              "ROUTING_RULES_KKREPO_UPSTREAM_BASE_URL").orElse(base));
    }

    HttpResponse<String> send(String method, String path, Object body) throws Exception {
      String serialized = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
      return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
              (username + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", body instanceof String ? "text/plain" : "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(serialized))
          .build(), HttpResponse.BodyHandlers.ofString());
    }

    void removeRepository(String name) throws Exception {
      if (!nexus && List.of("raw", "maven2", "npm").contains(format)) {
        // Only the cache-warming fixtures populate content. Explicit source also removes Maven group-owned cache assets, not member contents.
        for (String path : List.of("blocked", "allowed", "allowed;private", "private")) {
          HttpResponse<String> removed = send("DELETE", "/internal/browse/" + name
              + "?path=" + path + "&source=" + name, null);
          assertTrue(removed.statusCode() == 200 || removed.statusCode() == 404, removed.body());
        }
      }
      HttpResponse<String> removed = send("DELETE",
          (nexus ? "/service/rest/v1/repositories/" : "/internal/repositories/") + name, null);
      assertTrue(removed.statusCode() == 204 || removed.statusCode() == 404, removed.body());
    }

    void create(String name, String type, String remote, String rule, List<String> members) throws Exception {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("name", name); body.put("online", true);
      if (nexus) {
        body.put("storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false,
            "writePolicy", "ALLOW"));
        if (type.equals("proxy")) {
          body.put("proxy", Map.of("remoteUrl", remote, "contentMaxAge", 1440, "metadataMaxAge", 1440));
          body.put("negativeCache", Map.of("enabled", true, "timeToLive", 1440));
          body.put("httpClient", Map.of("blocked", false, "autoBlock", false, "authentication",
              Map.of("type", "username", "username", username, "password", password)));
          body.put("routingRuleName", rule);
          if (nexus) body.put("routingRule", rule);
        }
      } else {
        body.put("recipe", format + "-" + type); body.put("blobStoreName", "default");
        body.put("strictContentTypeValidation", false);
        if (type.equals("hosted")) body.put("hosted", Map.of("writePolicy", "ALLOW", "versionPolicy", "MIXED", "layoutPolicy", "PERMISSIVE"));
        if (type.equals("proxy")) {
          body.put("proxy", Map.of("remoteUrl", remote, "contentMaxAgeMinutes", 1440,
              "metadataMaxAgeMinutes", 1440, "remoteUsername", username, "remotePassword", password));
          body.put("routingRuleName", rule);
        }
      }
      if (nexus && format.equals("maven2")) body.put("maven", Map.of("versionPolicy", "MIXED", "layoutPolicy", "PERMISSIVE"));
      if (type.equals("group")) body.put("group", Map.of("memberNames", members));
      check(this, "POST", nexus ? "/service/rest/v1/repositories/" + (format.equals("maven2") ? "maven" : format) + "/" + type : "/internal/repositories", body, 201);
    }
  }
}
