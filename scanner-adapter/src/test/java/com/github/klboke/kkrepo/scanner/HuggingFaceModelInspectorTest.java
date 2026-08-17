package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
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
