package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HuggingFaceModelInspectorTest {
  private final HuggingFaceModelInspector inspector = new HuggingFaceModelInspector();
  @TempDir Path temporary;

  @Test
  void validatesSafeTensorsHeaderShapeOffsetsWithoutDeserializingPayload() throws Exception {
    String header = "{\"weight\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}";
    Path model = temporary.resolve("model.safetensors");
    Files.write(model, safeTensors(header, new byte[] {1, 2, 3, 4}));

    Map<String, Object> result = inspector.inspect(
        model, ScannerArtifactType.SAFETENSORS, limits(), new ScanDeadline(5));

    assertEquals("huggingface-model-file-v1", result.get("inputSchema"));
    assertEquals(1, result.get("safetensorsTensorCount"));
    assertEquals(true, result.get("safetensorsOffsetsValid"));
  }

  @Test
  void rejectsSafeTensorsOffsetsOutsideThePayload() throws Exception {
    String header = "{\"weight\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,8]}}";
    Path model = temporary.resolve("bad.safetensors");
    Files.write(model, safeTensors(header, new byte[] {1, 2, 3, 4}));

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> inspector.inspect(
            model, ScannerArtifactType.SAFETENSORS, limits(), new ScanDeadline(5)));

    assertEquals("SAFETENSORS_OFFSET_INVALID", failure.code());
  }

  @Test
  void rejectsShardIndexTraversalAndReportsPickleRiskOpcodes() throws Exception {
    Path index = temporary.resolve("model.safetensors.index.json");
    Files.writeString(index, "{\"weight_map\":{\"weight\":\"../escape.safetensors\"}}");
    ScannerRequestException indexFailure = assertThrows(
        ScannerRequestException.class,
        () -> inspector.inspect(
            index, ScannerArtifactType.MODEL_INDEX, limits(), new ScanDeadline(5)));
    assertEquals("MODEL_INDEX_PATH_INVALID", indexFailure.code());

    Path pickle = temporary.resolve("model.pkl");
    Files.write(pickle, new byte[] {
        (byte) 0x80, 4, 'c', 'o', 's', '\n', 's', 'y', 's', 't', 'e', 'm', '\n', 'R', '.'
    });
    Map<String, Object> result = inspector.inspect(
        pickle, ScannerArtifactType.PKL, limits(), new ScanDeadline(5));
    assertEquals(1, result.get("pickleGlobalOpcodes"));
    assertEquals(1, result.get("pickleReduceOpcodes"));
    assertTrue((Boolean) result.get("pickleRequiresReview"));
  }

  @Test
  void summarizesValidShardIndexesAndIgnoresUnsupportedArtifacts() throws Exception {
    Path index = temporary.resolve("model.safetensors.index.json");
    Files.writeString(index, """
        {"weight_map":{"a":"model-00001-of-00002.safetensors",
                       "b":"nested/model-00002-of-00002.safetensors",
                       "c":"model-00001-of-00002.safetensors"}}
        """);

    Map<String, Object> result = inspector.inspect(
        index, ScannerArtifactType.MODEL_INDEX, limits(), new ScanDeadline(5));

    assertEquals(3, result.get("modelIndexEntries"));
    assertEquals(2, result.get("modelIndexShardCount"));
    assertEquals(true, result.get("modelIndexPathsValid"));
    assertTrue(inspector.inspect(
        index, ScannerArtifactType.ZIP, limits(), new ScanDeadline(5)).isEmpty());
    assertFalse(inspector.supports(ScannerArtifactType.ZIP));
  }

  @Test
  void rejectsMalformedSafeTensorsWithoutReadingModelPayloadAsCode() throws Exception {
    Path truncated = temporary.resolve("truncated.safetensors");
    Files.write(truncated, new byte[9]);
    assertFailure("SAFETENSORS_TRUNCATED", truncated, ScannerArtifactType.SAFETENSORS, limits());

    Path badLength = temporary.resolve("bad-length.safetensors");
    Files.write(badLength, ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(1).put((byte) '{').put((byte) '}').array());
    assertFailure("SAFETENSORS_HEADER_LIMIT", badLength, ScannerArtifactType.SAFETENSORS, limits());

    Path nonObject = temporary.resolve("non-object.safetensors");
    Files.write(nonObject, safeTensors("[]", new byte[0]));
    assertFailure("SAFETENSORS_HEADER_INVALID", nonObject, ScannerArtifactType.SAFETENSORS, limits());

    Path invalidTensor = temporary.resolve("invalid-tensor.safetensors");
    Files.write(invalidTensor, safeTensors(
        "{\"weight\":{\"shape\":[1],\"data_offsets\":[0,0]}}", new byte[0]));
    assertFailure("SAFETENSORS_TENSOR_INVALID", invalidTensor,
        ScannerArtifactType.SAFETENSORS, limits());

    Path invalidShape = temporary.resolve("invalid-shape.safetensors");
    Files.write(invalidShape, safeTensors(
        "{\"weight\":{\"dtype\":\"F32\",\"shape\":[-1],\"data_offsets\":[0,0]}}",
        new byte[0]));
    assertFailure("SAFETENSORS_SHAPE_INVALID", invalidShape,
        ScannerArtifactType.SAFETENSORS, limits());

    Path overlap = temporary.resolve("overlap.safetensors");
    Files.write(overlap, safeTensors("""
        {"a":{"dtype":"F32","shape":[1],"data_offsets":[0,3]},
         "b":{"dtype":"F32","shape":[1],"data_offsets":[2,4]}}
        """, new byte[] {1, 2, 3, 4}));
    assertFailure("SAFETENSORS_OFFSET_OVERLAP", overlap,
        ScannerArtifactType.SAFETENSORS, limits());

    assertFailure("SAFETENSORS_INVALID", temporary.resolve("missing.safetensors"),
        ScannerArtifactType.SAFETENSORS, limits());
  }

  @Test
  void rejectsMalformedShardIndexesAndUnsafeRelativePaths() throws Exception {
    Path missingMap = temporary.resolve("missing-map.json");
    Files.writeString(missingMap, "{}");
    assertFailure("MODEL_INDEX_INVALID", missingMap, ScannerArtifactType.MODEL_INDEX, limits());

    Path nonText = temporary.resolve("non-text.json");
    Files.writeString(nonText, "{\"weight_map\":{\"a\":42}}");
    assertFailure("MODEL_INDEX_LIMIT", nonText, ScannerArtifactType.MODEL_INDEX, limits());

    for (String unsafe : new String[] {
        "/absolute.safetensors", "back\\slash.safetensors", "a//b.safetensors",
        "a/./b.safetensors", "a/../b.safetensors", "control\u0001.safetensors"
    }) {
      Path index = temporary.resolve("unsafe-" + Integer.toHexString(unsafe.hashCode()) + ".json");
      Files.writeString(index, "{\"weight_map\":{\"a\":"
          + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(unsafe) + "}}");
      assertFailure("MODEL_INDEX_PATH_INVALID", index, ScannerArtifactType.MODEL_INDEX, limits());
    }

    ResourceLimits oneByte = new ResourceLimits(1, 1000, 1, 1, 2, 5);
    assertFailure("MODEL_INDEX_LIMIT", missingMap, ScannerArtifactType.MODEL_INDEX, oneByte);
    assertFailure("MODEL_INDEX_INVALID", temporary.resolve("missing-index.json"),
        ScannerArtifactType.MODEL_INDEX, limits());
  }

  @Test
  void countsEverySecurityRelevantPickleOpcodeWithoutExecutingIt() throws Exception {
    Path pickle = temporary.resolve("all-opcodes.pkl");
    Files.write(pickle, pickleOpcodes());

    Map<String, Object> result = inspector.inspect(
        pickle, ScannerArtifactType.PYTORCH_WEIGHTS, limits(), new ScanDeadline(5));

    assertEquals(2, result.get("pickleGlobalOpcodes"));
    assertEquals(1, result.get("pickleReduceOpcodes"));
    assertEquals(7, result.get("pickleConstructorOpcodes"));
    assertEquals(10, result.get("pickleDangerousOpcodeCount"));
    assertTrue((Boolean) result.get("pickleRequiresReview"));

    Path harmless = temporary.resolve("harmless.pkl");
    Files.write(harmless, new byte[] {'.'});
    assertFalse((Boolean) inspector.inspect(
        harmless, ScannerArtifactType.PICKLE, limits(), new ScanDeadline(5))
        .get("pickleRequiresReview"));
  }

  @Test
  void rejectsTruncatedAndOverflowingPickleOperands() throws Exception {
    Path truncatedLine = temporary.resolve("truncated-line.pkl");
    Files.write(truncatedLine, new byte[] {'c', 'x'});
    assertFailure("PICKLE_INSPECTION_FAILED", truncatedLine, ScannerArtifactType.PKL, limits());

    Path truncatedLength = temporary.resolve("truncated-length.pkl");
    Files.write(truncatedLength, new byte[] {(byte) 0x8d, 1, 0});
    assertFailure("PICKLE_INSPECTION_FAILED", truncatedLength, ScannerArtifactType.PKL, limits());

    Path overflow = temporary.resolve("overflow.pkl");
    Files.write(overflow, new byte[] {
        (byte) 0x8d, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80
    });
    assertFailure("PICKLE_INSPECTION_FAILED", overflow, ScannerArtifactType.PKL, limits());
  }

  private void assertFailure(
      String expectedCode, Path input, ScannerArtifactType type, ResourceLimits resourceLimits) {
    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> inspector.inspect(input, type, resourceLimits, new ScanDeadline(5)));
    assertEquals(expectedCode, failure.code());
  }

  private static byte[] pickleOpcodes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(new byte[] {'c', 'm', '\n', 'n', '\n', (byte) 0x93, 'R'});
    bytes.writeBytes(new byte[] {'i', 'm', '\n', 'n', '\n', 'o', (byte) 0x81, (byte) 0x92});
    bytes.writeBytes(new byte[] {(byte) 0x82, 1, (byte) 0x83, 1, 2,
        (byte) 0x84, 1, 2, 3, 4, (byte) 0x80, 5});
    for (int opcode : new int[] {0x95, 'G'}) {
      bytes.write(opcode);
      bytes.writeBytes(new byte[8]);
    }
    for (int opcode : new int[] {0x8d, 0x8e, 0x96}) {
      bytes.write(opcode);
      bytes.writeBytes(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 7});
    }
    for (int opcode : new int[] {'J', 'X', 'B', 'T', 0x8b}) {
      bytes.write(opcode);
      bytes.writeBytes(new byte[] {1, 0, 0, 0, 7});
    }
    for (int opcode : new int[] {'K', 'U', 'C', 0x8c, 0x8a}) {
      bytes.write(opcode);
      bytes.writeBytes(new byte[] {1, 7});
    }
    bytes.writeBytes(new byte[] {'M', 1, 2});
    for (int opcode : new int[] {'F', 'I', 'L', 'P', 'S', 'V', 'g', 'p'}) {
      bytes.write(opcode);
      bytes.write('x');
      bytes.write('\n');
    }
    bytes.write('.');
    return bytes.toByteArray();
  }

  private static byte[] safeTensors(String header, byte[] payload) {
    byte[] json = header.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(Long.BYTES + json.length + payload.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(json.length)
        .put(json)
        .put(payload)
        .array();
  }

  private static ResourceLimits limits() {
    return new ResourceLimits(32 * 1024 * 1024, 1000, 32 * 1024 * 1024,
        32 * 1024 * 1024, 2, 5);
  }
}
