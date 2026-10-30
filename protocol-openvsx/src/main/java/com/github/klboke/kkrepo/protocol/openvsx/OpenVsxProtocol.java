package com.github.klboke.kkrepo.protocol.openvsx;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryProtocol;

/** Open VSX registry capabilities exposed by kkrepo. */
public final class OpenVsxProtocol implements RepositoryProtocol {
  public static final String DEFAULT_REMOTE_URL = "https://open-vsx.org/";
  public static final String METADATA_MEDIA_TYPE = "application/json";
  public static final String VSIX_MEDIA_TYPE = "application/octet-stream";

  @Override
  public RepositoryFormat format() {
    return RepositoryFormat.OPENVSX;
  }

  @Override
  public ProtocolCapability capability() {
    return new ProtocolCapability(true, true, true, true, true);
  }
}
