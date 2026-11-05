package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmGroupMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class OhpmGroupService {
  private final OhpmHostedService hosted;
  private final OhpmProxyService proxy;
  private final RepositoryRuntimeRegistry repositories;
  private final ObjectMapper json;

  public OhpmGroupService(OhpmHostedService hosted, OhpmProxyService proxy,
      RepositoryRuntimeRegistry repositories, ObjectMapper json) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.repositories = repositories;
    this.json = json;
  }

  public MavenResponse metadata(RepositoryRuntime runtime, OhpmPackageId packageId, String base, boolean head)
      throws IOException {
    Snapshot snapshot = snapshot(runtime, packageId, base);
    byte[] bytes = json.writeValueAsBytes(snapshot.metadata().rewritten(base));
    String etag = HexFormat.of().formatHex(PersistenceHashes.sha256(new String(bytes, StandardCharsets.UTF_8)));
    return (head ? MavenResponse.noBody(200, bytes.length, "application/json", etag, null)
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", etag, null))
        .withHeader("Cache-Control", "private, no-cache");
  }

  public MavenResponse download(RepositoryRuntime runtime, OhpmPackageId packageId, String path,
      String fingerprint, String base, boolean head) throws IOException {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new OhpmException(400, "Use the OHPM group download URL from current metadata");
    }
    Snapshot snapshot = snapshot(runtime, packageId, base);
    var selected = snapshot.metadata().selection(path)
        .orElseThrow(() -> new OhpmException(404, "OHPM group file does not exist"));
    if (!selected.fingerprint().equals(fingerprint)) {
      throw new OhpmException(409, "OHPM group source changed; refresh package metadata");
    }
    RepositoryRuntime member = snapshot.runtime().members().stream()
        .filter(value -> value.id() == selected.repositoryId()).findFirst().orElseThrow();
    MavenResponse response = member.isHosted() ? hosted.download(member, path, head)
        : proxy.download(member, packageId, path, head);
    if (!OhpmContentIntegrity.matches(response, selected.download().integrity())) {
      response.closeBodyIfOpen();
      throw new OhpmException(409, "OHPM group content changed; refresh package metadata");
    }
    return response;
  }

  private Snapshot snapshot(RepositoryRuntime requested, OhpmPackageId packageId, String base) throws IOException {
    RepositoryRuntime runtime = repositories.resolveFreshById(requested.id())
        .orElseThrow(() -> new OhpmException(404, "OHPM group does not exist"));
    if (runtime.format() != RepositoryFormat.OHPM || !runtime.isGroup()) {
      throw new OhpmException(405, "Operation requires an OHPM group repository");
    }
    if (!runtime.online()) throw new OhpmException(503, "Repository is offline");
    var sources = new ArrayList<OhpmGroupMetadata.Source>();
    for (RepositoryRuntime member : runtime.members()) {
      if (member.format() != RepositoryFormat.OHPM || !member.isHosted() && !member.isProxy()) {
        throw new OhpmException(502, "Invalid OHPM group member");
      }
      if (!member.online()) throw new OhpmException(503, "OHPM group member is offline");
      try {
        MavenResponse response = member.isHosted() ? hosted.metadata(member, packageId, base, false)
            : proxy.metadata(member, packageId, base, false);
        try (var body = response.body()) {
          sources.add(new OhpmGroupMetadata.Source(member.id(), OhpmPackageMetadata.read(packageId, body)));
        }
      } catch (OhpmException failure) {
        if (failure.status() != 404 && failure.status() != 410) throw failure;
      } catch (IllegalArgumentException | IOException invalid) {
        throw new OhpmException(502, "Invalid OHPM group member metadata");
      }
    }
    OhpmGroupMetadata merged = OhpmGroupMetadata.merge(runtime.id(), packageId, sources);
    if (merged.isEmpty()) throw new OhpmException(404, "OHPM group package does not exist");
    return new Snapshot(runtime, merged);
  }

  private record Snapshot(RepositoryRuntime runtime, OhpmGroupMetadata metadata) {}
}
