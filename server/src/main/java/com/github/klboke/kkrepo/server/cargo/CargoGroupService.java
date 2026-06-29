package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CargoGroupService {
  private final CargoHostedService hosted;
  private final CargoProxyService proxy;
  private final ObjectMapper objectMapper;

  public CargoGroupService(CargoHostedService hosted, CargoProxyService proxy, ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      CargoPath path,
      String baseUrl,
      CargoSearchQuery search,
      boolean headOnly) {
    ensureGroup(runtime);
    return switch (path.kind()) {
      case ROOT, CONFIG -> config(runtime, baseUrl, headOnly);
      case INDEX -> index(runtime, path.crateName(), headOnly);
      case DOWNLOAD -> download(runtime, path.crateName(), path.version(), headOnly);
      case SEARCH -> search(runtime, search, headOnly);
      case OWNERS -> CargoResponses.json(objectMapper, Map.of("users", List.of()), 200, headOnly);
      default -> throw new CargoExceptions.CargoNotFoundException(path.rawPath());
    };
  }

  MavenResponse index(RepositoryRuntime runtime, String crateName, boolean headOnly) {
    Map<String, String> byVersion = new LinkedHashMap<>();
    Instant lastModified = null;
    CargoExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : runtime.members()) {
      if (!eligible(member)) {
        continue;
      }
      try {
        MavenResponse response = switch (member.type()) {
          case HOSTED -> hosted.index(member, crateName, false);
          case PROXY -> proxy.index(member, crateName, false);
          case GROUP -> throw new CargoExceptions.MethodNotAllowed(
              "Nested Cargo group repositories are not supported: " + member.name());
        };
        lastModified = later(lastModified, response.lastModified());
        try (var body = response.body()) {
          for (String line : new String(body.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
              continue;
            }
            Map<String, Object> entry = objectMapper.readValue(line, CargoProxyService.JSON_MAP);
            String version = CargoVersions.uniquenessKey(String.valueOf(entry.get("vers")));
            byVersion.putIfAbsent(version, line);
          }
        }
      } catch (CargoExceptions.CargoNotFoundException ignored) {
        // Continue with the next member.
      } catch (CargoExceptions.BadUpstreamException e) {
        lastUpstream = e;
      } catch (IOException e) {
        lastUpstream = new CargoExceptions.BadUpstreamException("Failed reading Cargo group member index", e);
      }
    }
    if (byVersion.isEmpty()) {
      if (lastUpstream != null) {
        throw lastUpstream;
      }
      throw new CargoExceptions.CargoIndexNotFoundException(crateName);
    }
    return CargoResponses.text(String.join("\n", byVersion.values()) + "\n", null, lastModified, headOnly);
  }

  MavenResponse download(RepositoryRuntime runtime, String crateName, String version, boolean headOnly) {
    CargoExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : runtime.members()) {
      if (!eligible(member)) {
        continue;
      }
      try {
        return switch (member.type()) {
          case HOSTED -> hosted.download(member, crateName, version, headOnly);
          case PROXY -> proxy.download(member, crateName, version, headOnly);
          case GROUP -> throw new CargoExceptions.MethodNotAllowed(
              "Nested Cargo group repositories are not supported: " + member.name());
        };
      } catch (CargoExceptions.CargoNotFoundException ignored) {
        // Continue with the next member.
      } catch (CargoExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (lastUpstream != null) {
      throw lastUpstream;
    }
    throw new CargoExceptions.CargoNotFoundException(crateName + " " + version);
  }

  MavenResponse search(RepositoryRuntime runtime, CargoSearchQuery query, boolean headOnly) {
    List<Map<String, Object>> crates = new ArrayList<>();
    CargoExceptions.BadUpstreamException lastUpstream = null;
    CargoSearchQuery memberQuery = new CargoSearchQuery(
        query.query(),
        Math.min(CargoSearchQuery.MAX_PER_PAGE, query.offset() + query.perPage()),
        1);
    for (RepositoryRuntime member : runtime.members()) {
      if (!eligible(member)) {
        continue;
      }
      try {
        MavenResponse response = switch (member.type()) {
          case HOSTED -> hosted.search(member, memberQuery, false);
          case PROXY -> proxy.search(member, memberQuery, false);
          case GROUP -> throw new CargoExceptions.MethodNotAllowed(
              "Nested Cargo group repositories are not supported: " + member.name());
        };
        crates.addAll(CargoSearchResults.cratesFromResponse(objectMapper, response));
      } catch (CargoExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (crates.isEmpty() && lastUpstream != null) {
      throw lastUpstream;
    }
    return CargoSearchResults.fromCrates(objectMapper, crates, query, headOnly);
  }

  private MavenResponse config(RepositoryRuntime runtime, String baseUrl, boolean headOnly) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("dl", baseUrl + "/crates");
    config.put("api", baseUrl + "/");
    if (runtime.cargoRequireAuthenticationOrDefault()) {
      config.put("auth-required", true);
    }
    return CargoResponses.jsonWithBodyEtag(objectMapper, config, 200, headOnly);
  }

  private void ensureGroup(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.CARGO || !runtime.isGroup()) {
      throw new CargoExceptions.MethodNotAllowed("Operation is only valid on group Cargo repositories");
    }
  }

  private boolean eligible(RepositoryRuntime member) {
    return member.online()
        && member.format() == RepositoryFormat.CARGO
        && member.type() != RepositoryType.GROUP;
  }

  private static Instant later(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isAfter(right) ? left : right;
  }
}
