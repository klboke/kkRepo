package com.github.klboke.kkrepo.server.pypi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.web.util.UriUtils;

/** Decodes URL path segments without applying form-style {@code +} to space conversion. */
public final class PypiRequestPath {
  private PypiRequestPath() {
  }

  public static String decode(String rawPath) {
    String path = rawPath == null ? "" : rawPath;
    return Arrays.stream(path.split("/", -1))
        .map(PypiRequestPath::decodeSegment)
        .collect(Collectors.joining("/"));
  }

  static String decodeSegment(String rawSegment) {
    String decoded;
    try {
      decoded = UriUtils.decode(rawSegment, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new PypiExceptions.BadRequestException("Invalid percent-encoding in PyPI path");
    }
    if (decoded.equals(".") || decoded.equals("..")
        || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
        || decoded.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
      throw new PypiExceptions.BadRequestException("Invalid PyPI path segment");
    }
    return decoded;
  }
}
