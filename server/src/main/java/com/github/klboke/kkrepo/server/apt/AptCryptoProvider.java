package com.github.klboke.kkrepo.server.apt;

import java.security.Provider;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.NativeDetector;

final class AptCryptoProvider {
  private AptCryptoProvider() {
  }

  static Provider current() {
    if (NativeDetector.inNativeImage()) return null;
    Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (provider == null) {
      Security.addProvider(new BouncyCastleProvider());
      provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    }
    if (provider == null) throw new IllegalStateException("Bouncy Castle provider is unavailable");
    return provider;
  }
}
