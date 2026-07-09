package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class RepositoryFormatJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void repositoryFormatUsesNexusWireIds() throws Exception {
    assertEquals("\"maven2\"", mapper.writeValueAsString(RepositoryFormat.MAVEN2));
    assertEquals("\"npm\"", mapper.writeValueAsString(RepositoryFormat.NPM));
    assertEquals("\"nuget\"", mapper.writeValueAsString(RepositoryFormat.NUGET));
    assertEquals("\"rubygems\"", mapper.writeValueAsString(RepositoryFormat.RUBYGEMS));
    assertEquals("\"yum\"", mapper.writeValueAsString(RepositoryFormat.YUM));
    assertEquals("\"pub\"", mapper.writeValueAsString(RepositoryFormat.PUB));
    assertEquals(RepositoryFormat.MAVEN2, mapper.readValue("\"maven2\"", RepositoryFormat.class));
    assertEquals(RepositoryFormat.NPM, mapper.readValue("\"npm\"", RepositoryFormat.class));
    assertEquals(RepositoryFormat.NUGET, mapper.readValue("\"nuget\"", RepositoryFormat.class));
    assertEquals(RepositoryFormat.RUBYGEMS, mapper.readValue("\"rubygems\"", RepositoryFormat.class));
    assertEquals(RepositoryFormat.YUM, mapper.readValue("\"yum\"", RepositoryFormat.class));
    assertEquals(RepositoryFormat.PUB, mapper.readValue("\"pub\"", RepositoryFormat.class));
  }

  @Test
  void repositoryFormatDoesNotAcceptLegacyMavenAlias() {
    assertThrows(
        JsonProcessingException.class,
        () -> mapper.readValue("\"maven\"", RepositoryFormat.class));
  }
}
