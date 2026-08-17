package com.github.klboke.kkrepo.protocol.huggingface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HuggingFaceJsonTransformerTest {
  private final HuggingFaceJsonTransformer transformer = new HuggingFaceJsonTransformer();

  @Test
  void stripsXetHintsButKeepsLfsIntegrityEvidence() {
    byte[] raw = """
        [{
          "type":"file",
          "path":"model.safetensors",
          "oid":"0123456789012345678901234567890123456789",
          "xetHash":"xet-reconstruction-id",
          "lfs":{"oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":42}
        }]
        """.getBytes(StandardCharsets.UTF_8);

    HuggingFaceJsonTransformer.Result result = transformer.transform(
        raw, "https://huggingface.co", "https://repo.example/repository/models");

    assertEquals(1, result.removedXetHints());
    assertFalse(result.json().get(0).has("xetHash"));
    assertTrue(result.sourceJson().get(0).has("xetHash"));
    assertEquals(42, result.json().get(0).path("lfs").path("size").longValue());
    assertEquals(64, result.json().get(0).path("lfs").path("oid").textValue().length());
  }

  @Test
  void rewritesOnlyHubUrlsAndDropsXetPagination() {
    byte[] raw = """
        {"url":"https://huggingface.co/openai/model/resolve/main/config.json",
         "other":"https://example.invalid/value",
         "nested":{"downloadUrl":"https://huggingface.co/api/models/openai/model/xet-read-token/main"}}
        """.getBytes(StandardCharsets.UTF_8);
    var result = transformer.transform(
        raw, "https://huggingface.co", "https://repo.example/repository/models");
    assertEquals(
        "https://repo.example/repository/models/openai/model/resolve/main/config.json",
        result.json().path("url").textValue());
    assertEquals("https://example.invalid/value", result.json().path("other").textValue());
    assertFalse(result.json().path("nested").has("downloadUrl"));
    assertEquals(1, result.removedXetHints());

    assertEquals(
        "<https://repo.example/repository/models/api/models/openai/model/tree/main?cursor=next>; rel=\"next\"",
        transformer.rewriteLink(
            "<https://huggingface.co/api/models/openai/model/tree/main?cursor=next>; rel=\"next\"",
            "https://huggingface.co", "https://repo.example/repository/models"));
    assertNull(transformer.rewriteLink(
        "<https://huggingface.co/xet>; rel=\"xet-auth\"",
        "https://huggingface.co", "https://repo.example/repository/models"));
  }

  @Test
  void classifiesModelFilesWithoutExecutingOrGuessingGenericBin() {
    assertEquals(HuggingFaceFileKind.SAFETENSORS,
        HuggingFaceFileKind.classify("weights/model.safetensors"));
    assertEquals(HuggingFaceFileKind.PICKLE,
        HuggingFaceFileKind.classify("pytorch_model.bin"));
    assertEquals(HuggingFaceFileKind.OTHER,
        HuggingFaceFileKind.classify("unrelated.bin"));
    assertTrue(HuggingFaceFileKind.classify("model.gguf").modelBinary());
  }

  @Test
  void rejectsDuplicateKeysAndOversizedMetadata() {
    assertThrows(IllegalArgumentException.class, () -> transformer.transform(
        "{\"sha\":\"a\",\"sha\":\"b\"}".getBytes(StandardCharsets.UTF_8),
        "https://huggingface.co", "https://repo.example/repository/models"));
    assertThrows(IllegalArgumentException.class, () -> transformer.transform(
        new byte[HuggingFaceJsonTransformer.MAX_BYTES + 1],
        "https://huggingface.co", "https://repo.example/repository/models"));
  }
}
