package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;

/** Decodes URL path segments without applying form-style {@code +} to space conversion. */
public final class PypiRequestPath {
  private PypiRequestPath() {
  }

  public static String decode(String rawPath) {
    try {
      return UriPathDecoder.decodePath(rawPath == null ? "" : rawPath);
    } catch (IllegalArgumentException e) {
      throw new PypiExceptions.BadRequestException("Invalid PyPI path: " + e.getMessage());
    }
  }

  static String decodeSegment(String rawSegment) {
    try {
      return UriPathDecoder.decodeSegment(rawSegment);
    } catch (IllegalArgumentException e) {
      throw new PypiExceptions.BadRequestException("Invalid PyPI path: " + e.getMessage());
    }
  }
}
