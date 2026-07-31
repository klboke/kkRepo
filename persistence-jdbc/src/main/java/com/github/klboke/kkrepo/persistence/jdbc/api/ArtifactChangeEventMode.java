package com.github.klboke.kkrepo.persistence.jdbc.api;

/**
 * Startup-time activation for the feature-neutral artifact content-change stream.
 *
 * <p>The persistence layer deliberately does not know which optional consumer requested the
 * stream. Server wiring owns that deployment decision.
 */
public record ArtifactChangeEventMode(boolean enabled) {
}
