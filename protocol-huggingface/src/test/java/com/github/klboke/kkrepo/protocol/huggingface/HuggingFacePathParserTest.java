package com.github.klboke.kkrepo.protocol.huggingface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HuggingFacePathParserTest {
  private final HuggingFacePathParser parser = new HuggingFacePathParser();

  @Test
  void parsesCurrentAndLegacyClientRoutes() {
    HuggingFacePath info = parser.parse("api/models/openai/gpt-oss-20b");
    assertEquals(HuggingFacePath.Kind.MODEL_INFO, info.kind());
    assertEquals("openai/gpt-oss-20b", info.repoId());

    HuggingFacePath revision = parser.parse(
        "api/models/openai/gpt-oss-20b/revision/refs%2Fpr%2F3");
    assertEquals(HuggingFacePath.Kind.REVISION_INFO, revision.kind());
    assertEquals("refs/pr/3", revision.revision());

    HuggingFacePath tree = parser.parse(
        "api/models/openai/gpt-oss-20b/tree/0123456789012345678901234567890123456789/subdir");
    assertEquals(HuggingFacePath.Kind.TREE, tree.kind());
    assertEquals("subdir", tree.filePath());

    HuggingFacePath paths = parser.parse(
        "api/models/bert-base-uncased/paths-info/main");
    assertEquals(HuggingFacePath.Kind.PATHS_INFO, paths.kind());
    assertEquals("bert-base-uncased", paths.repoId());

    assertEquals(HuggingFacePath.Kind.REFS,
        parser.parse("api/models/openai/gpt-oss-20b/refs").kind());
  }

  @Test
  void parsesResolveWithoutConfusingFileHierarchyWithRevision() {
    HuggingFacePath path = parser.parse(
        "openai/gpt-oss-20b/resolve/refs%2Fpr%2F3/weights/model-00001-of-00002.safetensors");
    assertEquals(HuggingFacePath.Kind.RESOLVE, path.kind());
    assertEquals("openai/gpt-oss-20b", path.repoId());
    assertEquals("refs/pr/3", path.revision());
    assertEquals("weights/model-00001-of-00002.safetensors", path.filePath());
    assertEquals("openai", path.namespace());
    assertEquals("gpt-oss-20b", path.modelName());
  }

  @Test
  void rejectsTraversalDoubleEncodingAndEncodedFileSeparators() {
    assertUnsupported("openai/model/resolve/main/a/%2e%2e/b");
    assertUnsupported("openai/model/resolve/main/a%2Fb");
    assertUnsupported("openai/model/resolve/main/%252e%252e/file");
    assertUnsupported("api/models/openai/model/tree/main/%2Fetc");
    assertUnsupported("api/models/openai/model/paths-info/main/extra");
  }

  @Test
  void identifiesXetTokenRouteWithoutTreatingItAsMetadata() {
    HuggingFacePath path = parser.parse(
        "api/models/openai/model/xet-read-token/0123456789012345678901234567890123456789");
    assertEquals(HuggingFacePath.Kind.XET_TOKEN, path.kind());
    assertTrue(!path.apiMetadata());
    assertNull(path.filePath());
  }

  private void assertUnsupported(String value) {
    assertEquals(HuggingFacePath.Kind.UNSUPPORTED, parser.parse(value).kind(), value);
  }
}
