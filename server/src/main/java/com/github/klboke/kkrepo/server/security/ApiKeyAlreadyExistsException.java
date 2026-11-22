package com.github.klboke.kkrepo.server.security;

final class ApiKeyAlreadyExistsException extends RuntimeException {
  ApiKeyAlreadyExistsException() {
    super("An API key already exists for this token type. Use Reset to replace it.");
  }
}
