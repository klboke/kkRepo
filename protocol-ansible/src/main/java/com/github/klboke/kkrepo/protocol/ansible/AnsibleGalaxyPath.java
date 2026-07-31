package com.github.klboke.kkrepo.protocol.ansible;

public record AnsibleGalaxyPath(
    Kind kind,
    String rawPath,
    String namespace,
    String name,
    String version,
    String filename,
    String taskId,
    boolean longAlias) {

  public enum Kind {
    DISCOVERY,
    COLLECTION,
    VERSION_LIST,
    VERSION_DETAIL,
    PUBLISH,
    IMPORT_TASK,
    ARTIFACT,
    UNKNOWN
  }

  public String coordinate() {
    return namespace == null || name == null || version == null
        ? null
        : namespace + "." + name + ":" + version;
  }
}
