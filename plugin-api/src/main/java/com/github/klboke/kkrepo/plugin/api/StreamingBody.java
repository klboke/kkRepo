package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Framework-agnostic streaming response body. The host calls {@link #writeTo} once, after the
 * status and headers are committed, and owns the output stream lifecycle; the body owns and closes
 * whatever source it reads from.
 */
@FunctionalInterface
public interface StreamingBody {

  void writeTo(OutputStream output) throws IOException;
}
