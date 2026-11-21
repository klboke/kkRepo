package com.github.klboke.kkrepo.server.security;

/**
 * Published inside the reset transaction after an API key or user token got a new secret under the
 * same id. Stores of bearer tokens issued from that key (Docker, Conan) delete them, because
 * {@link SecurityAuthenticationService#authenticateStoredSubject} only sees that the id is active.
 */
public record ApiKeyRotatedEvent(long apiKeyId) {
}
