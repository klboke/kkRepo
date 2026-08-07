package com.github.klboke.kkrepo.server.cleanup;

public class CleanupNotFoundException extends RuntimeException {
  public CleanupNotFoundException(String resource, long id) {
    super(resource + " not found: " + id);
  }
}
