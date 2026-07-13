package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MySqlDatabaseBackendTest {
  @Test
  void identifiesTheMySqlBackend() {
    assertEquals("mysql", new MySqlDatabaseBackend().id());
  }
}
