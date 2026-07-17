package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPart;

class SwiftMultipartRequestTest {

  @Test
  void parsesTheFourOfficialPublishFields() throws Exception {
    MockPart archive = part("source-archive", "source.zip", "application/zip", new byte[] {1, 2, 3});
    MockPart sourceSignature = part(
        "source-archive-signature", "source.cms", "application/octet-stream", new byte[] {4, 5});
    MockPart metadata = part(
        "metadata", null, "application/json", "{\"repositoryURLs\":[]}".getBytes(StandardCharsets.UTF_8));
    MockPart metadataSignature = part(
        "metadata-signature", "metadata.cms", "application/octet-stream", new byte[] {6});

    SwiftMultipartRequest parsed = SwiftMultipartRequest.parse(
        List.of(archive, sourceSignature, metadata, metadataSignature));

    assertArrayEquals(new byte[] {1, 2, 3}, parsed.openArchive().readAllBytes());
    assertArrayEquals(new byte[] {4, 5}, parsed.sourceArchiveSignature());
    assertEquals("{\"repositoryURLs\":[]}", parsed.metadataJson());
    assertArrayEquals(new byte[] {6}, parsed.metadataSignature());
  }

  @Test
  void defaultsOmittedMetadataToAnEmptyObject() {
    SwiftMultipartRequest parsed = SwiftMultipartRequest.parse(List.of(
        part("source-archive", "source.zip", "application/zip", new byte[] {1})));

    assertEquals("{}", parsed.metadataJson());
  }

  @Test
  void decodesOfficialContentTransferEncodingsBeforeProtocolValidation() throws Exception {
    byte[] archiveBytes = new byte[] {'P', 'K', 1, 2, 3};
    MockPart archive = part(
        "source-archive", "source.zip", "application/zip",
        Base64.getMimeEncoder().encode(archiveBytes));
    archive.getHeaders().set("Content-Transfer-Encoding", "base64");
    MockPart metadata = part(
        "metadata", null, "application/json",
        "=7B=22description=22=3A=22hello=22=7D".getBytes(StandardCharsets.US_ASCII));
    metadata.getHeaders().set("Content-Transfer-Encoding", "quoted-printable");

    SwiftMultipartRequest parsed = SwiftMultipartRequest.parse(List.of(archive, metadata));

    assertArrayEquals(archiveBytes, parsed.openArchive().readAllBytes());
    assertEquals("{\"description\":\"hello\"}", parsed.metadataJson());
  }

  @Test
  void rejectsUnknownOrMalformedContentTransferEncoding() {
    MockPart archive = part("source-archive", "source.zip", "application/zip", new byte[] {1});
    archive.getHeaders().set("Content-Transfer-Encoding", "rot13");
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(archive)));

    MockPart validArchive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {1});
    MockPart metadata = part(
        "metadata", null, "application/json", "=XZ".getBytes(StandardCharsets.US_ASCII));
    metadata.getHeaders().set("Content-Transfer-Encoding", "quoted-printable");
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(validArchive, metadata)));
  }

  @Test
  void rejectsMissingDuplicateUnknownAndWrongRepresentationFields() {
    MockPart archive = part("source-archive", "source.zip", "application/zip", new byte[] {1});

    assertThrows(SwiftExceptions.BadRequest.class, () -> SwiftMultipartRequest.parse(List.of()));
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(archive, archive)));
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(
            archive, part("unexpected", null, null, new byte[] {1}))));
    assertThrows(SwiftExceptions.UnsupportedMediaType.class,
        () -> SwiftMultipartRequest.parse(List.of(
            part("source-archive", "source.tgz", "application/gzip", new byte[] {1}))));
    assertThrows(SwiftExceptions.UnsupportedMediaType.class,
        () -> SwiftMultipartRequest.parse(List.of(
            archive, part("metadata", null, "text/plain", "{}".getBytes(StandardCharsets.UTF_8)))));
    assertThrows(SwiftExceptions.UnsupportedMediaType.class,
        () -> SwiftMultipartRequest.parse(List.of(
            archive, part("source-archive-signature", "source.cms", "text/plain", new byte[] {1}))));
    assertThrows(SwiftExceptions.UnsupportedMediaType.class,
        () -> SwiftMultipartRequest.parse(List.of(
            archive, part("metadata-signature", "metadata.cms", "application/json", new byte[] {1}))));
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(
            archive, part("metadata", null, "application/json", new byte[] {(byte) 0xc3, 0x28}))));
  }

  @Test
  void enforcesReportedAndStreamedFieldLimitsAndWrapsReadFailures() throws Exception {
    MockPart archive = part("source-archive", "source.zip", "application/zip", new byte[] {1});
    Part oversized = mock(Part.class);
    when(oversized.getName()).thenReturn("metadata");
    when(oversized.getContentType()).thenReturn("application/json");
    when(oversized.getSize()).thenReturn(1024L * 1024 + 1);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> SwiftMultipartRequest.parse(List.of(archive, oversized)));

    Part unreadable = mock(Part.class);
    when(unreadable.getName()).thenReturn("metadata");
    when(unreadable.getContentType()).thenReturn("application/json");
    when(unreadable.getSize()).thenReturn(1L);
    when(unreadable.getInputStream()).thenThrow(new IOException("broken multipart stream"));
    assertThrows(SwiftExceptions.BadRequest.class,
        () -> SwiftMultipartRequest.parse(List.of(archive, unreadable)));

    Part misleadingSize = mock(Part.class);
    when(misleadingSize.getName()).thenReturn("metadata");
    when(misleadingSize.getContentType()).thenReturn("application/json");
    when(misleadingSize.getSize()).thenReturn(1L);
    when(misleadingSize.getInputStream()).thenReturn(
        new ByteArrayInputStream(new byte[1024 * 1024 + 1]));
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> SwiftMultipartRequest.parse(List.of(archive, misleadingSize)));

    MockPart oversizedSourceSignature = part(
        "source-archive-signature",
        "source.cms",
        "application/octet-stream",
        new byte[SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES + 1]);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> SwiftMultipartRequest.parse(List.of(archive, oversizedSourceSignature)));
  }

  private static MockPart part(String name, String filename, String contentType, byte[] bytes) {
    MockPart part = new MockPart(name, filename, bytes);
    if (contentType != null) {
      part.getHeaders().set("Content-Type", contentType);
    }
    return part;
  }
}
