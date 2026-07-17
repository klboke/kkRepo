package com.github.klboke.kkrepo.protocol.swift;

/** A validated path plus the endpoint-specific query value, if any. */
public record SwiftRequestTarget(
    SwiftPath path,
    String swiftVersion,
    String repositoryUrl) {
}
