package com.github.klboke.kkrepo.persistence.jdbc.spi;

import org.springframework.jdbc.core.JdbcOperations;

/** Idempotent security seed and relationship operations. */
public interface SecurityPersistenceDialect {
  void insertPrivilegeIfAbsent(JdbcOperations jdbc, PrivilegeInsert privilege);

  void assignRoleIfAbsent(JdbcOperations jdbc, long userId, String roleId);

  void grantPrivilegeIfAbsent(JdbcOperations jdbc, String roleId, String privilegeId);

  void inheritRoleIfAbsent(JdbcOperations jdbc, String roleId, String childRoleId);

  record PrivilegeInsert(
      String privilegeId,
      String name,
      String description,
      String type,
      boolean readOnly,
      String propertiesJson) {
  }
}
