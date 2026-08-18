package com.github.klboke.kkrepo.security.scan;

import java.util.Locale;

/**
 * Closed set of file identities that may influence a scanner workspace filename.
 *
 * <p>The wire value selects an enum constant only. Untrusted request text is never appended to a
 * path or command argument.
 */
public enum ScannerArtifactType {
  UNKNOWN("", "artifact"),
  TAR_GZ(".tar.gz", "artifact.tar.gz"),
  TAR_BZ2(".tar.bz2", "artifact.tar.bz2"),
  TAR_XZ(".tar.xz", "artifact.tar.xz"),
  TAR_ZST(".tar.zst", "artifact.tar.zst"),
  XZ(".xz", "artifact.tar.xz"),
  ZIP(".zip", "artifact.zip"),
  TAR(".tar", "artifact.tar"),
  TGZ(".tgz", "artifact.tgz"),
  TBZ2(".tbz2", "artifact.tbz2"),
  TXZ(".txz", "artifact.txz"),
  TZST(".tzst", "artifact.tzst"),
  JAR(".jar", "artifact.jar"),
  WAR(".war", "artifact.war"),
  EAR(".ear", "artifact.ear"),
  AAR(".aar", "artifact.aar"),
  WHL(".whl", "artifact.whl"),
  EGG(".egg", "artifact.egg"),
  CRATE(".crate", "artifact.crate"),
  CONDA(".conda", "artifact.conda"),
  GEM(".gem", "artifact.gem"),
  NUPKG(".nupkg", "artifact.nupkg"),
  RPM(".rpm", "artifact.rpm"),
  DEB(".deb", "artifact.deb"),
  APK(".apk", "artifact.apk"),
  IPA(".ipa", "artifact.ipa"),
  MODEL_INDEX(".safetensors.index.json", "model.safetensors.index.json"),
  SAFETENSORS(".safetensors", "model.safetensors"),
  PICKLE(".pickle", "model.pickle"),
  PKL(".pkl", "model.pkl"),
  PYTORCH(".pt", "model.pt"),
  PYTORCH_WEIGHTS(".pth", "model.pth"),
  CHECKPOINT(".ckpt", "model.ckpt"),
  PYTORCH_BIN(".bin", "pytorch_model.bin"),
  ONNX(".onnx", "model.onnx"),
  KERAS(".keras", "model.keras"),
  TENSORFLOW(".pb", "model.pb"),
  FLAX_MSGPACK(".msgpack", "flax_model.msgpack"),
  GGUF(".gguf", "model.gguf");

  private final String suffix;
  private final String safeFilename;

  ScannerArtifactType(String suffix, String safeFilename) {
    this.suffix = suffix;
    this.safeFilename = safeFilename;
  }

  public String suffix() {
    return suffix;
  }

  public String safeFilename() {
    return safeFilename;
  }

  public String wireValue() {
    return name();
  }

  public static ScannerArtifactType fromPath(String path) {
    if (path == null || path.isBlank()) {
      return UNKNOWN;
    }
    String normalized = path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    int separator = normalized.lastIndexOf('/');
    String filename = normalized.substring(separator + 1);
    if (filename.endsWith(".bin.index.json")) return MODEL_INDEX;
    for (ScannerArtifactType type : values()) {
      if (type != UNKNOWN && filename.endsWith(type.suffix)) {
        return type;
      }
    }
    return UNKNOWN;
  }

  public static ScannerArtifactType fromWireValue(String value) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "UNKNOWN" -> UNKNOWN;
      case "TAR_GZ" -> TAR_GZ;
      case "TAR_BZ2" -> TAR_BZ2;
      case "TAR_XZ" -> TAR_XZ;
      case "TAR_ZST" -> TAR_ZST;
      case "XZ" -> XZ;
      case "ZIP" -> ZIP;
      case "TAR" -> TAR;
      case "TGZ" -> TGZ;
      case "TBZ2" -> TBZ2;
      case "TXZ" -> TXZ;
      case "TZST" -> TZST;
      case "JAR" -> JAR;
      case "WAR" -> WAR;
      case "EAR" -> EAR;
      case "AAR" -> AAR;
      case "WHL" -> WHL;
      case "EGG" -> EGG;
      case "CRATE" -> CRATE;
      case "CONDA" -> CONDA;
      case "GEM" -> GEM;
      case "NUPKG" -> NUPKG;
      case "RPM" -> RPM;
      case "DEB" -> DEB;
      case "APK" -> APK;
      case "IPA" -> IPA;
      case "MODEL_INDEX" -> MODEL_INDEX;
      case "SAFETENSORS" -> SAFETENSORS;
      case "PICKLE" -> PICKLE;
      case "PKL" -> PKL;
      case "PYTORCH" -> PYTORCH;
      case "PYTORCH_WEIGHTS" -> PYTORCH_WEIGHTS;
      case "CHECKPOINT" -> CHECKPOINT;
      case "PYTORCH_BIN" -> PYTORCH_BIN;
      case "ONNX" -> ONNX;
      case "KERAS" -> KERAS;
      case "TENSORFLOW" -> TENSORFLOW;
      case "FLAX_MSGPACK" -> FLAX_MSGPACK;
      case "GGUF" -> GGUF;
      default -> throw new IllegalArgumentException("Unsupported scanner artifact type");
    };
  }
}
