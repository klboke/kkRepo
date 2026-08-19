package com.github.klboke.kkrepo.server.version;

import java.io.IOException;
import java.net.URI;

interface LatestReleaseSource {
  LatestRelease fetch() throws IOException;

  record LatestRelease(String version, URI url) {}
}
