package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AnsibleGalaxyMultipartReaderTest {
  private static final String BOUNDARY = "ansible-galaxy-boundary";
  private static final String SHA256 = "a".repeat(64);

  @Test
  void readsCurrentFormDataUploadWithoutHeapBufferingTheArtifact() throws Exception {
    byte[] artifact = new byte[] {0x1f, (byte) 0x8b, 0, 1, 2, 3};
    MockHttpServletRequest request = request(base64Body(artifact, SHA256));

    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(1024, 1024);
    try (AnsibleGalaxyMultipartReader.Upload upload = reader.read(request);
         var input = upload.openStream()) {
      assertEquals("acme-tools-1.0.0.tar.gz", upload.filename());
      assertEquals(SHA256, upload.sha256());
      assertEquals(artifact.length, upload.size());
      assertArrayEquals(artifact, input.readAllBytes());
    }
  }

  @Test
  void readsAnsible29LegacyFileDispositionAndBinaryBoundaryPrefix() throws Exception {
    byte[] artifact = ("archive\r\n--" + BOUNDARY + "-not-a-delimiter\nend")
        .getBytes(StandardCharsets.ISO_8859_1);
    MockHttpServletRequest request = request(body("file", artifact, SHA256));

    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(1024, 1024);
    AnsibleGalaxyMultipartReader.Upload upload = reader.read(request);
    try (upload; var input = upload.openStream()) {
      assertEquals("acme-tools-1.0.0.tar.gz", upload.filename());
      assertArrayEquals(artifact, input.readAllBytes());
    }
    assertThrows(java.io.IOException.class, upload::openStream);
  }

  @Test
  void rejectsDuplicateFilePartsAndOversizedArtifacts() {
    byte[] duplicate = duplicateFileBody();
    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(1024, 1024);
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> reader.read(request(duplicate)));

    byte[] artifact = new byte[] {1, 2, 3, 4, 5};
    AnsibleGalaxyMultipartReader bounded = new AnsibleGalaxyMultipartReader(4, 1024);
    assertThrows(AnsibleGalaxyExceptions.ContentTooLarge.class,
        () -> bounded.read(request(body("file", artifact, SHA256))));
  }

  @Test
  void rejectsMissingBoundaryAndOversizedShaField() {
    MockHttpServletRequest missing = new MockHttpServletRequest();
    missing.setMethod("POST");
    missing.setContentType("multipart/form-data");
    missing.setContent(new byte[] {1});
    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(1024, 1024);
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> reader.read(missing));

    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> reader.read(request(body("form-data", new byte[] {1}, "b".repeat(129)))));
  }

  @Test
  void rejectsDeclaredAndStreamedBodiesAboveTheirBounds() {
    MockHttpServletRequest declared = request(new byte[32]);
    assertThrows(AnsibleGalaxyExceptions.ContentTooLarge.class,
        () -> new AnsibleGalaxyMultipartReader(1, 1).read(declared));

    MockHttpServletRequest empty = request(new byte[0]);
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> new AnsibleGalaxyMultipartReader(1024, 1024).read(empty));
  }

  @Test
  void rejectsMalformedHeadersDispositionAndMultipartShape() {
    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(4096, 4096);
    for (String payload : new String[] {
        "not-the-opening-boundary",
        "--" + BOUNDARY + "\r\nBad Header\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: attachment; name=\"file\"; filename=\"x.tar.gz\"\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.tar.gz\"\r\n"
            + "Content-Disposition: form-data; name=\"file\"\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.tar.gz\"\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.tar.gz\"\r\n\r\n"
            + "\r\n--" + BOUNDARY + "--\r\n",
        "--" + BOUNDARY + "\nContent-Disposition: form-data; name=\"file\"\n\nx",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"sha256\"\r\n\r\n" + SHA256
            + "\r\n--" + BOUNDARY + "--\r\n"
    }) {
      assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
          () -> reader.read(request(payload.getBytes(StandardCharsets.ISO_8859_1))), payload);
    }
  }

  @Test
  void rejectsMalformedBase64AndDuplicateChecksumParts() {
    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(4096, 4096);
    for (String encoded : new String[] {"abc", "!!!!", "YQ===", "YQ==A"}) {
      assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
          () -> reader.read(request(base64TextBody(encoded, SHA256))), encoded);
    }

    ByteArrayOutputStream duplicateSha = new ByteArrayOutputStream();
    write(duplicateSha, "--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\"acme-tools-1.0.0.tar.gz\"\r\n\r\n"
        + "archive\r\n");
    for (int i = 0; i < 2; i++) {
      write(duplicateSha, "--" + BOUNDARY + "\r\n"
          + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
          + SHA256 + "\r\n");
    }
    write(duplicateSha, "--" + BOUNDARY + "--\r\n");
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> reader.read(request(duplicateSha.toByteArray())));
  }

  @Test
  void rejectsUnsafeOrMalformedBoundaryParameters() {
    AnsibleGalaxyMultipartReader reader = new AnsibleGalaxyMultipartReader(1024, 1024);
    for (String contentType : new String[] {
        "application/json",
        "multipart/form-data; boundary=",
        "multipart/form-data; boundary=" + "x".repeat(71),
        "multipart/form-data; boundary=bad@boundary",
        "multipart/form-data; boundary=\"unterminated\\\""
    }) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setContentType(contentType);
      request.setContent(new byte[] {1});
      assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
          () -> reader.read(request), contentType);
    }
  }

  private static MockHttpServletRequest request(byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("multipart/form-data; boundary=\"" + BOUNDARY + "\"");
    request.setContent(body);
    return request;
  }

  private static byte[] body(String disposition, byte[] artifact, String sha256) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    write(body, "--" + BOUNDARY + "\r\n"
        + "Content-Disposition: " + disposition
        + "; name=\"file\"; filename=\"acme-tools-1.0.0.tar.gz\"\r\n"
        + "Content-Type: application/octet-stream\r\n\r\n");
    body.writeBytes(artifact);
    write(body, "\r\n--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + sha256
        + "\r\n--" + BOUNDARY + "--\r\n");
    return body.toByteArray();
  }

  private static byte[] base64Body(byte[] artifact, String sha256) {
    String encoded = Base64.getMimeEncoder(
        76, "\r\n".getBytes(StandardCharsets.ISO_8859_1)).encodeToString(artifact);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    write(body, "--" + BOUNDARY + "\r\n"
        + "Content-Transfer-Encoding: base64\r\n"
        + "Content-Type: application/octet-stream\r\n"
        + "Content-Disposition: form-data; name=\"file\"; "
        + "filename=\"acme-tools-1.0.0.tar.gz\"\r\n\r\n"
        + encoded + "\r\n"
        + "\r\n--" + BOUNDARY + "\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + sha256
        + "\r\n--" + BOUNDARY + "--\r\n");
    return body.toByteArray();
  }

  private static byte[] base64TextBody(String encoded, String sha256) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    write(body, "--" + BOUNDARY + "\r\n"
        + "Content-Transfer-Encoding: base64\r\n"
        + "Content-Disposition: form-data; name=\"file\"; "
        + "filename=\"acme-tools-1.0.0.tar.gz\"\r\n\r\n"
        + encoded + "\r\n--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + sha256 + "\r\n--" + BOUNDARY + "--\r\n");
    return body.toByteArray();
  }

  private static byte[] duplicateFileBody() {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (int i = 0; i < 2; i++) {
      write(body, "--" + BOUNDARY + "\r\n"
          + "Content-Disposition: form-data; name=\"file\"; filename=\"file-"
          + i + ".tar.gz\"\r\n\r\n"
          + "archive-" + i + "\r\n");
    }
    write(body, "--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + SHA256 + "\r\n--" + BOUNDARY + "--\r\n");
    return body.toByteArray();
  }

  private static void write(ByteArrayOutputStream output, String value) {
    output.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
  }
}
