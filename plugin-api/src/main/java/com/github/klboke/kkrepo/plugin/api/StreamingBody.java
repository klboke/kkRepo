package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;
import java.io.OutputStream;

/** Framework-agnostic streaming response body. The caller owns the output stream lifecycle. */
@FunctionalInterface
public interface StreamingBody {

  void writeTo(OutputStream output) throws IOException;
}
