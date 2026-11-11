package com.github.klboke.kkrepo.plugin.api;

/** Contributes one repository format to the runtime. */
public interface RepositoryFormatProvider {

  /** Stable format id, for example {@code maven2} or {@code my-format}. */
  FormatId id();

  /** Human-readable metadata and capabilities. */
  RepositoryFormatDescriptor formatDescriptor();
}
