package com.github.klboke.kkrepo.server.huggingface;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Projects one immutable Hub commit to the Nexus-compatible model revision component. */
@Component
final class HuggingFaceComponentFactory {
  ComponentRecord component(
      RepositoryRuntime runtime,
      String repoId,
      String commit,
      String requestedRef,
      boolean privateModel,
      boolean gated,
      String library,
      String pipeline,
      String license,
      Instant updatedAt) {
    if (runtime == null || runtime.format() != RepositoryFormat.HUGGINGFACE || !runtime.isProxy()) {
      throw new IllegalArgumentException("Hugging Face components require a proxy repository");
    }
    int slash = repoId.indexOf('/');
    String namespace = slash < 0 ? "" : repoId.substring(0, slash);
    String name = slash < 0 ? repoId : repoId.substring(slash + 1);
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("repoId", repoId);
    attributes.put("commit", commit);
    if (requestedRef != null && !requestedRef.isBlank()) attributes.put("requestedRef", requestedRef);
    attributes.put("private", privateModel);
    attributes.put("gated", gated);
    if (library != null && !library.isBlank()) attributes.put("library", library);
    if (pipeline != null && !pipeline.isBlank()) attributes.put("pipeline", pipeline);
    if (license != null && !license.isBlank()) attributes.put("license", license);
    attributes.put("browsePath", revisionBrowsePath(repoId, commit));
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.HUGGINGFACE,
        namespace,
        name,
        commit,
        "model-revision",
        PersistenceHashes.sha256("huggingface", repoId, commit),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  String fileBrowsePath(String repoId, String commit, String filePath) {
    return revisionBrowsePath(repoId, commit) + "/" + filePath;
  }

  private static String revisionBrowsePath(String repoId, String commit) {
    return repoId + "/" + commit;
  }
}
