package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DockerManifestParserTest {
  private final DockerManifestParser parser = new DockerManifestParser(new ObjectMapper());

  @Test
  void parsesLegacyConfigMediaTypeAsArtifactTypeForOciReferrers() {
    String subject = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String blob = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String manifest = """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.manifest.v1+json",
          "config": {
            "mediaType": "application/vnd.nhl.peanut.butter.bagel",
            "digest": "%s",
            "size": 42
          },
          "subject": {
            "mediaType": "application/vnd.oci.image.manifest.v1+json",
            "digest": "%s",
            "size": 2
          },
          "layers": [],
          "annotations": {
            "org.opencontainers.conformance.test": "test config a"
          }
        }
        """.formatted(blob, subject);

    DockerManifestMetadata metadata = parser.parse(
        manifest.getBytes(StandardCharsets.UTF_8),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST);

    assertEquals("application/vnd.nhl.peanut.butter.bagel", metadata.artifactType());
    assertEquals(subject, metadata.subjectDigest());
    assertEquals("test config a", metadata.annotations().get("org.opencontainers.conformance.test"));
  }

  @Test
  void doesNotTreatStandardImageConfigAsArtifactType() {
    String config = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String manifest = """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.manifest.v1+json",
          "config": {
            "mediaType": "application/vnd.oci.image.config.v1+json",
            "digest": "%s",
            "size": 42
          },
          "layers": []
        }
        """.formatted(config);

    DockerManifestMetadata metadata = parser.parse(
        manifest.getBytes(StandardCharsets.UTF_8),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST);

    assertNull(metadata.artifactType());
    assertFalse(metadata.references().isEmpty());
  }
}
