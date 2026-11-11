package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;

/** Executes one remote request. */
public interface RemoteFetcher {

  RemoteResponse fetch(RemoteRequest request) throws IOException;
}
