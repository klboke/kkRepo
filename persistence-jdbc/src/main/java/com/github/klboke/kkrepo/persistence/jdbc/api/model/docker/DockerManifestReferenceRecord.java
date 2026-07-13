package com.github.klboke.kkrepo.persistence.jdbc.api.model.docker;

import java.util.Map;

public record DockerManifestReferenceRecord(
    Long id,
    long manifestId,
    long repositoryId,
    String imageName,
    String digest,
    byte[] digestHash,
    String referenceKind,
    String mediaType,
    Long size,
    Map<String, Object> platform,
    Map<String, Object> annotations) {
}
