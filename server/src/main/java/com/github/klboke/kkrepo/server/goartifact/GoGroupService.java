package com.github.klboke.kkrepo.server.goartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.goartifact.GoVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class GoGroupService {
  private static final int MAX_AGGREGATED_METADATA_BYTES = 32 * 1024 * 1024;

  private final GoHostedService hosted;
  private final GoProxyService proxy;
  private final ObjectMapper objectMapper;

  public GoGroupService(
      GoHostedService hosted,
      GoProxyService proxy,
      ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(RepositoryRuntime group, String rawPath, boolean headOnly) {
    ensureGroup(group);
    GoPath path = parse(rawPath);
    if (group.members().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    return switch (path.kind()) {
      case LIST -> list(group, path, headOnly);
      case LATEST -> latest(group, path, headOnly);
      case PACKAGE, INFO, MODULE -> first(group, path, headOnly);
    };
  }

  private MavenResponse list(RepositoryRuntime group, GoPath path, boolean headOnly) {
    Set<String> versions = new TreeSet<>(
        GoVersions.COMPARATOR.thenComparing(Comparator.naturalOrder()));
    MavenExceptions.BadUpstreamException lastUpstream = null;
    boolean successfulMember = false;
    int metadataBytes = 0;
    for (RepositoryRuntime member : group.members()) {
      if (!eligible(member)) continue;
      try {
        VersionList memberVersions = memberVersions(
            member, path, MAX_AGGREGATED_METADATA_BYTES - metadataBytes);
        metadataBytes += memberVersions.serializedBytes();
        versions.addAll(memberVersions.versions());
        successfulMember = true;
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Continue in configured member order.
      } catch (MavenExceptions.BadUpstreamException error) {
        lastUpstream = error;
      } catch (IOException error) {
        lastUpstream = new MavenExceptions.BadUpstreamException(
            "Failed reading Go group member version list", error);
      }
    }
    if (!successfulMember) {
      if (lastUpstream != null) throw lastUpstream;
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    String body = String.join("\n", versions);
    // Nexus group lists are generated metadata and expose neither ETag nor Last-Modified.
    return GoResponses.text(body, null, headOnly);
  }

  private VersionList memberVersions(
      RepositoryRuntime member, GoPath path, int remainingBytes) throws IOException {
    return switch (member.type()) {
      case HOSTED -> {
        List<String> versions = hosted.listVersions(member, path);
        yield new VersionList(versions, serializedListBytes(versions, remainingBytes));
      }
      case PROXY -> {
        MavenResponse response = proxy.get(member, path.path(), false);
        byte[] body = readBounded(response, remainingBytes);
        List<String> versions = new ArrayList<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\\R")) {
          if (line.isBlank()) continue;
          String version = line.trim();
          if (!GoVersions.isCanonical(version)) {
            throw new MavenExceptions.BadUpstreamException(
                "Go group member returned an invalid version: " + version);
          }
          if (!GoVersions.isPseudoVersion(version)) {
            versions.add(version);
          }
        }
        yield new VersionList(versions, body.length);
      }
      case GROUP -> throw new MavenExceptions.MethodNotAllowed(
          "Nested Go group repositories are not supported: " + member.name());
    };
  }

  private static int serializedListBytes(List<String> versions, int remainingBytes) {
    if (remainingBytes < 0) {
      throw metadataLimitExceeded();
    }
    long bytes = Math.max(0, versions.size() - 1);
    for (String version : versions) {
      bytes += version.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > remainingBytes) {
        throw metadataLimitExceeded();
      }
    }
    return (int) bytes;
  }

  private MavenResponse latest(RepositoryRuntime group, GoPath path, boolean headOnly) {
    List<LatestCandidate> candidates = new ArrayList<>();
    MavenExceptions.BadUpstreamException lastUpstream = null;
    int metadataBytes = 0;
    for (RepositoryRuntime member : group.members()) {
      if (!eligible(member)) continue;
      MavenResponse response;
      try {
        response = memberGet(member, path.path(), false);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Continue in configured member order.
        continue;
      } catch (MavenExceptions.BadUpstreamException error) {
        lastUpstream = error;
        continue;
      }
      try {
        byte[] body = readBounded(response, MAX_AGGREGATED_METADATA_BYTES - metadataBytes);
        metadataBytes += body.length;
        JsonNode info = objectMapper.readTree(body);
        String version = requiredText(info, "Version");
        Instant time = Instant.parse(requiredText(info, "Time"));
        candidates.add(new LatestCandidate(
            new GoVersions.Candidate(version, time), response.lastModified()));
      } catch (MavenExceptions.BadUpstreamException error) {
        lastUpstream = error;
      } catch (IOException | IllegalArgumentException | DateTimeException error) {
        lastUpstream = new MavenExceptions.BadUpstreamException(
            "Go group member returned invalid @latest metadata", error);
      }
    }
    GoVersions.Candidate selected = GoVersions.latest(
        candidates.stream().map(LatestCandidate::candidate).toList()).orElse(null);
    if (selected == null) {
      if (lastUpstream != null) throw lastUpstream;
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    Instant selectedLastModified = candidates.stream()
        .filter(candidate -> candidate.candidate().equals(selected))
        .filter(candidate -> candidate.lastModified() != null)
        .map(LatestCandidate::lastModified)
        .findFirst()
        .orElse(null);
    return GoResponses.info(
        objectMapper, selected.version(), selected.time(), selectedLastModified, headOnly);
  }

  private MavenResponse first(RepositoryRuntime group, GoPath path, boolean headOnly) {
    MavenExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : group.members()) {
      if (!eligible(member)) continue;
      try {
        return memberGet(member, path.path(), headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Continue in configured member order.
      } catch (MavenExceptions.BadUpstreamException error) {
        lastUpstream = error;
      }
    }
    if (lastUpstream != null) throw lastUpstream;
    throw new MavenExceptions.MavenNotFoundException(path.path());
  }

  private MavenResponse memberGet(
      RepositoryRuntime member,
      String rawPath,
      boolean headOnly) {
    return switch (member.type()) {
      case HOSTED -> hosted.get(member, rawPath, headOnly);
      case PROXY -> proxy.get(member, rawPath, headOnly);
      case GROUP -> throw new MavenExceptions.MethodNotAllowed(
          "Nested Go group repositories are not supported: " + member.name());
    };
  }

  private static byte[] readBounded(MavenResponse response, int remaining) throws IOException {
    if (remaining < 0) {
      throw metadataLimitExceeded();
    }
    try (InputStream body = response.body()) {
      if (body == null) return new byte[0];
      byte[] bytes = body.readNBytes(remaining + 1);
      if (bytes.length > remaining) {
        throw metadataLimitExceeded();
      }
      return bytes;
    }
  }

  private static MavenExceptions.BadUpstreamException metadataLimitExceeded() {
    return new MavenExceptions.BadUpstreamException(
        "Go group metadata exceeds the aggregation limit");
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("Missing Go version info field: " + field);
    }
    return value.textValue();
  }

  private static boolean eligible(RepositoryRuntime member) {
    return member.online() && member.format() == RepositoryFormat.GO;
  }

  private static GoPath parse(String rawPath) {
    try {
      return GoPath.parse(rawPath);
    } catch (IllegalArgumentException error) {
      throw new MavenExceptions.MavenNotFoundException(error.getMessage());
    }
  }

  private static void ensureGroup(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.GO || !runtime.isGroup()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Operation is only valid on Go group repositories");
    }
  }

  private record LatestCandidate(GoVersions.Candidate candidate, Instant lastModified) {
  }

  private record VersionList(List<String> versions, int serializedBytes) {
  }
}
