package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GoResponsesTest {
  @Test
  void mapsVersionInfoSerializationFailures() throws Exception {
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsBytes(any()))
        .thenThrow(new JsonProcessingException("broken mapper") {});

    assertThrows(IllegalStateException.class,
        () -> GoResponses.infoBytes(mapper, "v1.0.0", Instant.EPOCH));
  }
}
