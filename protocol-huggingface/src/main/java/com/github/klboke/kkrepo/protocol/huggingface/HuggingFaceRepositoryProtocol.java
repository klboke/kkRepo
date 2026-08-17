package com.github.klboke.kkrepo.protocol.huggingface;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryProtocol;

/** Hugging Face Models read-through proxy capabilities exposed by kkrepo. */
public final class HuggingFaceRepositoryProtocol implements RepositoryProtocol {
  @Override
  public RepositoryFormat format() {
    return RepositoryFormat.HUGGINGFACE;
  }

  @Override
  public ProtocolCapability capability() {
    return new ProtocolCapability(false, false, true, false, true);
  }
}
