package com.github.klboke.kkrepo.protocol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DockerPathParserTest {
  private final DockerPathParser parser = new DockerPathParser();

  @Test
  void decodesEachReferenceAndUploadIdOnlyOnce() {
    assertEquals("latest", parser.parse("library/alpine/manifests/%6Catest").reference());
    assertEquals("%6Catest", parser.parse("library/alpine/manifests/%256Catest").reference());
    assertEquals("id+suffix", parser.parse("library/alpine/blobs/uploads/id+suffix").uploadUuid());
    assertEquals("id%2Fsuffix", parser.parse("library/alpine/blobs/uploads/id%252Fsuffix").uploadUuid());
    assertThrows(DockerProtocolException.class,
        () -> parser.parse("library%2Falpine/manifests/latest"));
    assertThrows(DockerProtocolException.class,
        () -> parser.parse("library/alpine/manifests/%C3%28"));
  }

  @Test
  void parsesMultiSegmentManifestFromRightSentinel() {
    DockerPath path = parser.parse("team/platform/api/manifests/latest");

    assertEquals(DockerPath.Kind.MANIFEST, path.kind());
    assertEquals("team/platform/api", path.imageName());
    assertEquals("latest", path.reference());
  }

  @Test
  void allowsEndpointWordsInsideImageName() {
    DockerPath path = parser.parse("team/manifests/cache/blobs/app/manifests/latest");

    assertEquals(DockerPath.Kind.MANIFEST, path.kind());
    assertEquals("team/manifests/cache/blobs/app", path.imageName());
    assertEquals("latest", path.reference());
  }

  @Test
  void parsesUploadSessionWithoutFixedImageDepth() {
    DockerPath path = parser.parse("team/a/b/blobs/uploads/abc-123");

    assertEquals(DockerPath.Kind.UPLOAD_SESSION, path.kind());
    assertEquals("team/a/b", path.imageName());
    assertEquals("abc-123", path.uploadUuid());
  }

  @Test
  void parsesBlobDigest() {
    DockerPath path = parser.parse(
        "library/alpine/blobs/sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    assertEquals(DockerPath.Kind.BLOB, path.kind());
    assertEquals("library/alpine", path.imageName());
    assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        path.digest().value());
  }

  @Test
  void rejectsUppercaseImageName() {
    assertThrows(DockerProtocolException.class, () -> parser.parse("Team/app/tags/list"));
  }
}
