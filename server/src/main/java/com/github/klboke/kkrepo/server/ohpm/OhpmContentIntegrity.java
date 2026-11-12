package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.util.Base64;
import java.util.HexFormat;

final class OhpmContentIntegrity {
  private static final String ATTRIBUTE = "ohpm.contentIntegrity";

  private OhpmContentIntegrity() {}

  static MavenResponse attach(MavenResponse response, Object sha512) {
    if (sha512 instanceof String hex && hex.matches("[0-9a-f]{128}")) {
      response.withInternalAttribute(ATTRIBUTE, "sha512-" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex)));
    }
    return response;
  }

  static boolean matches(MavenResponse response, OhpmIntegrity expected) {
    return expected.value().equals(response.internalAttribute(ATTRIBUTE));
  }
}
