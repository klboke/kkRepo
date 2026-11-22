package com.github.klboke.kkrepo.server.security;

/**
 * Published inside the reset transaction after an API key or user token got a new secret under the
 * same id. Stores of bearer tokens issued from that key (Docker, Conan) delete them as eager cleanup;
 * {@link SecurityAuthenticationService#authenticateStoredSubject} also rejects any bearer whose stored
 * secret generation no longer matches, including ones a replica issued from a stale cached credential.
 */
public record ApiKeyRotatedEvent(long apiKeyId) {
}
