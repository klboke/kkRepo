package com.github.klboke.kkrepo.plugin.api;

/** Factory for one network transport implementation. */
public interface RemoteTransportProvider {

  /** Stable transport type, for example {@code http} or {@code http-proxy}. */
  String type();

  /** Creates a fetcher for one repository or global configuration. */
  RemoteFetcher create(RemoteTransportConfig config);
}
