package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NexusRepositorySecurityContributor {
  private static final List<Action> VIEW_ACTIONS = List.of(
      new Action("*", "All privileges"),
      new Action("browse", "Browse privilege"),
      new Action("read", "Read privilege"),
      new Action("edit", "Edit privilege"),
      new Action("add", "Add privilege"),
      new Action("delete", "Delete privilege"));
  private static final List<Action> ADMIN_REPOSITORY_ACTIONS = List.of(
      new Action("*", "All privileges"),
      new Action("browse", "Browse privilege"),
      new Action("read", "Read privilege"),
      new Action("edit", "Edit privilege"),
      new Action("delete", "Delete privilege"));

  private NexusRepositorySecurityContributor() {
  }

  static void ensureRepositoryPrivileges(SecurityDao securityDao, RepositoryFormat format, String repositoryName) {
    for (SecurityPrivilegeRecord record : formatViewPrivileges(format)) {
      securityDao.insertPrivilegeIfAbsent(record);
    }
    for (SecurityPrivilegeRecord record : repositoryPrivileges(format, repositoryName)) {
      securityDao.insertPrivilegeIfAbsent(record);
    }
  }

  static void removeRepositoryPrivileges(SecurityDao securityDao, RepositoryFormat format, String repositoryName) {
    for (SecurityPrivilegeRecord record : repositoryPrivileges(format, repositoryName)) {
      securityDao.removePrivilegeReferences(record.privilegeId());
      securityDao.deletePrivilege(record.privilegeId());
    }
  }

  static List<SecurityPrivilegeRecord> formatViewPrivileges(RepositoryFormat format) {
    String formatName = nexusFormat(format);
    List<SecurityPrivilegeRecord> records = new ArrayList<>();
    for (Action action : VIEW_ACTIONS) {
      records.add(repositoryPrivilege(
          "repository-view",
          formatName,
          "*",
          action.value(),
          action.label() + " for all '" + formatName + "'-format repository views"));
    }
    return records;
  }

  static List<SecurityPrivilegeRecord> repositoryPrivileges(RepositoryFormat format, String repositoryName) {
    String formatName = nexusFormat(format);
    List<SecurityPrivilegeRecord> records = new ArrayList<>();
    for (Action action : VIEW_ACTIONS) {
      records.add(repositoryPrivilege(
          "repository-view",
          formatName,
          repositoryName,
          action.value(),
          action.label() + " for " + repositoryName + " repository views"));
    }
    for (Action action : ADMIN_REPOSITORY_ACTIONS) {
      records.add(repositoryPrivilege(
          "repository-admin",
          formatName,
          repositoryName,
          action.value(),
          action.label() + " for " + repositoryName + " repository administration"));
    }
    return records;
  }

  private static SecurityPrivilegeRecord repositoryPrivilege(
      String type,
      String format,
      String repository,
      String action,
      String description) {
    String privilegeId = "nx-" + type + "-" + format + "-" + repository + "-" + action;
    return new SecurityPrivilegeRecord(
        privilegeId,
        privilegeId,
        description,
        type,
        true,
        Map.of("format", format, "repository", repository, "actions", action));
  }

  private static String nexusFormat(RepositoryFormat format) {
    return format.name().toLowerCase(Locale.ROOT);
  }

  private record Action(String value, String label) {
  }
}
