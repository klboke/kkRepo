package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ZstdForeignReachabilityMetadataTest {
  private static final String METADATA_RESOURCE =
      "META-INF/native-image/com.github.klboke/kkrepo-server-runtime/reachability-metadata.json";

  private static final Set<String> ZSTD_FFM_DOWNCALLS = Set.of(
      "size_t()",
      "void*()",
      "size_t(void*)",
      "size_t(void*,int)",
      "size_t(void*,int,int)",
      "size_t(void*,void*)",
      "size_t(void*,long long)",
      "struct(long long,long long,long long,long long,int,int)(void*)",
      "size_t(void*,void*,size_t):critical-heap",
      "size_t(void*,void*,size_t,void*,size_t):critical-heap",
      "size_t(void*,void*,size_t,void*,void*,size_t,void*,int):critical-heap",
      "size_t(void*,void*,size_t,void*,void*,size_t,void*):critical-heap");

  @Test
  void registersEveryZstdFfmDowncallCreatedDuringBindingInitialization() throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(METADATA_RESOURCE)) {
      assertNotNull(input, () -> "Missing native-image metadata resource " + METADATA_RESOURCE);
      JsonNode metadata = new ObjectMapper().readTree(input);

      Set<String> registered = StreamSupport.stream(
              metadata.path("foreign").path("downcalls").spliterator(), false)
          .map(ZstdForeignReachabilityMetadataTest::signature)
          .collect(Collectors.toSet());

      assertEquals(ZSTD_FFM_DOWNCALLS, registered);
    }
  }

  private static String signature(JsonNode downcall) {
    String parameters = StreamSupport.stream(
            downcall.path("parameterTypes").spliterator(), false)
        .map(JsonNode::asText)
        .collect(Collectors.joining(","));
    boolean criticalHeap = downcall.path("options")
        .path("critical")
        .path("allowHeapAccess")
        .asBoolean(false);
    return downcall.path("returnType").asText()
        + "(" + parameters + ")"
        + (criticalHeap ? ":critical-heap" : "");
  }
}
