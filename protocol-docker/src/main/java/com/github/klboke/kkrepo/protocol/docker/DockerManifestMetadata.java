package com.github.klboke.kkrepo.protocol.docker;

import java.util.List;
import java.util.Map;

public record DockerManifestMetadata(
    String mediaType,
    String artifactType,
    String subjectDigest,
    Map<String, Object> annotations,
    List<DockerManifestDescriptor> references) {
}
