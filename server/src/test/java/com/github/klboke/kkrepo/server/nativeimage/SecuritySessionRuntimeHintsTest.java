package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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

    assertTrue(RuntimeHintsPredicates.reflection()
        .onJavaSerialization(SecuritySessionRuntimeHints.SESSION_SUBJECT, true)
        .test(hints));

    new FileNativeConfigurationWriter(output).write(hints);
    JsonNode metadata = new ObjectMapper().readTree(Files.readString(
        output.resolve("META-INF/native-image/reachability-metadata.json")));
    JsonNode sessionSubject = StreamSupport.stream(metadata.path("reflection").spliterator(), false)
        .filter(entry -> SecuritySessionRuntimeHints.SESSION_SUBJECT.getName()
            .equals(entry.path("type").asText()))
        .findFirst()
        .orElseThrow();

    assertTrue(sessionSubject.path("serializable").asBoolean());
  }
}
