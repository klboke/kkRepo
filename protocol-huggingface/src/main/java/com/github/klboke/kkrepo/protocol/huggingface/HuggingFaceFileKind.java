package com.github.klboke.kkrepo.protocol.huggingface;

import java.util.Locale;

/** Bounded, non-executing classification used by Browse and security scanning. */
public enum HuggingFaceFileKind {
  SAFETENSORS(true, "safetensors"),
  PICKLE(true, "pickle-family"),
  ONNX(true, "onnx"),
  TENSORFLOW(true, "tensorflow-keras"),
  FLAX(true, "flax-msgpack"),
  OPENVINO(true, "openvino"),
  GGUF(true, "gguf"),
  SHARD_INDEX(false, "shard-index"),
  TOKENIZER(false, "tokenizer"),
  CONFIG(false, "config"),
  MODEL_CARD(false, "model-card"),
  CODE(false, "code"),
  OTHER(false, "unknown");

  private final boolean modelBinary;
  private final String modelFormat;

  HuggingFaceFileKind(boolean modelBinary, String modelFormat) {
    this.modelBinary = modelBinary;
    this.modelFormat = modelFormat;
  }

  public boolean modelBinary() {
    return modelBinary;
  }

  public String modelFormat() {
    return modelFormat;
  }

  public static HuggingFaceFileKind classify(String path) {
    String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (value.endsWith(".safetensors.index.json") || value.endsWith(".bin.index.json")) {
      return SHARD_INDEX;
    }
    if (value.endsWith(".safetensors")) return SAFETENSORS;
    if (value.endsWith(".pkl") || value.endsWith(".pickle") || value.endsWith(".pt")
        || value.endsWith(".pth") || value.endsWith("pytorch_model.bin")) return PICKLE;
    if (value.endsWith(".onnx")) return ONNX;
    if (value.endsWith(".h5") || value.endsWith(".keras") || value.endsWith(".pb")) {
      return TENSORFLOW;
    }
    if (value.endsWith(".msgpack")) return FLAX;
    if (value.endsWith(".xml") && value.contains("openvino")) return OPENVINO;
    if (value.endsWith(".gguf")) return GGUF;
    if (value.endsWith("tokenizer.json") || value.endsWith("tokenizer.model")
        || value.endsWith("vocab.json") || value.endsWith("vocab.txt")
        || value.endsWith("merges.txt")) return TOKENIZER;
    if (value.endsWith("config.json") || value.endsWith("generation_config.json")) return CONFIG;
    if (value.equals("readme.md") || value.endsWith("/readme.md")) return MODEL_CARD;
    if (value.endsWith(".py")) return CODE;
    return OTHER;
  }
}
