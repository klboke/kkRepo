package com.github.klboke.kkrepo.protocol.conda;

public record CondaPath(
    Kind kind,
    String rawPath,
    String channel,
    String subdir,
    String filename,
    Encoding encoding) {

  public enum Kind {
    ROOT,
    REPODATA,
    CURRENT_REPODATA,
    SHARDED_REPODATA,
    CHANNELDATA,
    NOTICES,
    PACKAGE,
    UNKNOWN
  }

  public enum Encoding {
    NONE,
    JSON,
    BZIP2,
    ZSTD,
    MSGPACK_ZSTD
  }

  public boolean metadata() {
    return switch (kind) {
      case REPODATA, CURRENT_REPODATA, SHARDED_REPODATA, CHANNELDATA, NOTICES -> true;
      default -> false;
    };
  }

  public boolean packageFile() {
    return kind == Kind.PACKAGE;
  }

  public String canonicalPath() {
    StringBuilder result = new StringBuilder();
    if (channel != null && !channel.isBlank()) {
      result.append(channel).append('/');
    }
    if (subdir != null && !subdir.isBlank()) {
      result.append(subdir).append('/');
    }
    if (filename != null) {
      result.append(filename);
    }
    return result.toString();
  }
}
