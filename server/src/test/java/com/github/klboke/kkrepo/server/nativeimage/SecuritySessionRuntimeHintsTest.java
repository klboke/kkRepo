package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.nativex.FileNativeConfigurationWriter;

class SecuritySessionRuntimeHintsTest {
  @Test
  void registersJdbcSessionSubjectForNativeJavaSerialization(@TempDir Path output)
      throws Exception {
    RuntimeHints hints = new RuntimeHints();
    new SecuritySessionRuntimeHints().registerHints(hints, getClass().getClassLoader());

    SecuritySessionRuntimeHints.JAVA_SERIALIZATION_TYPES.forEach(type -> assertTrue(
        RuntimeHintsPredicates.reflection()
            .onJavaSerialization(type, true)
            .test(hints),
        () -> "Missing Java serialization hints for " + type.getName()));

    new FileNativeConfigurationWriter(output).write(hints);
    JsonNode metadata = new ObjectMapper().readTree(Files.readString(
        output.resolve("META-INF/native-image/reachability-metadata.json")));
    Set<String> serializableTypes = StreamSupport.stream(
            metadata.path("reflection").spliterator(), false)
        .filter(entry -> entry.path("serializable").asBoolean())
        .map(entry -> entry.path("type").asText())
        .collect(Collectors.toSet());

    SecuritySessionRuntimeHints.JAVA_SERIALIZATION_TYPES.forEach(type -> assertTrue(
        serializableTypes.contains(type.getName()),
        () -> "Generated metadata is missing serializable type " + type.getName()));
  }
}
