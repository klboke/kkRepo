package com.github.klboke.kkrepo.protocol.apt;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryProtocol;

/** Debian APT archive capabilities exposed by kkrepo. */
public final class AptRepositoryProtocol implements RepositoryProtocol {
  @Override
  public RepositoryFormat format() {
    return RepositoryFormat.APT;
  }

  @Override
  public ProtocolCapability capability() {
    return new ProtocolCapability(true, true, true, false, true);
  }
}
