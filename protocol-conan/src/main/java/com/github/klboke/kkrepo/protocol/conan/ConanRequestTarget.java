package com.github.klboke.kkrepo.protocol.conan;

/** Parsed Conan path plus the small endpoint-specific query contract. */
public record ConanRequestTarget(
    ConanPath path,
    String searchPattern,
    boolean ignoreCase,
    boolean listOnly) {
}
