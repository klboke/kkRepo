package com.github.klboke.kkrepo.plugin.api;

/** A plugin could not be verified, loaded or registered. */
public class PluginException extends Exception {

  public PluginException(String message) {
    super(message);
  }

  public PluginException(String message, Throwable cause) {
    super(message, cause);
  }
}
