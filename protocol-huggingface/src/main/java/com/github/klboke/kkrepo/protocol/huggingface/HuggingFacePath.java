package com.github.klboke.kkrepo.protocol.huggingface;

/** Parsed path below one Hugging Face Models proxy repository root. */
public record HuggingFacePath(
    Kind kind,
    String rawPath,
    String canonicalPath,
    String repoId,
    String revision,
    String filePath) {

  public enum Kind {
    ROOT,
    MODEL_INFO,
    REVISION_INFO,
    TREE,
    PATHS_INFO,
    REFS,
    RESOLVE,
    XET_TOKEN,
    UNSUPPORTED
  }

  public boolean apiMetadata() {
    return kind == Kind.MODEL_INFO
        || kind == Kind.REVISION_INFO
        || kind == Kind.TREE
        || kind == Kind.PATHS_INFO
        || kind == Kind.REFS;
  }

  public boolean file() {
    return kind == Kind.RESOLVE;
  }

  public String namespace() {
    if (repoId == null) return null;
    int slash = repoId.indexOf('/');
    return slash < 0 ? "" : repoId.substring(0, slash);
  }

  public String modelName() {
    if (repoId == null) return null;
    int slash = repoId.indexOf('/');
    return slash < 0 ? repoId : repoId.substring(slash + 1);
  }
}
