package com.github.klboke.kkrepo.plugin.api;

/** One uploaded multipart part exposed to a plugin handler. */
public record MultipartPart(
    String name,
    String filename,
    String contentType,
    byte[] content) {

  public MultipartPart {
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }
}
