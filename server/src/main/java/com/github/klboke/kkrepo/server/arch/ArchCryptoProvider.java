package com.github.klboke.kkrepo.server.arch;

import java.security.Provider;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.NativeDetector;

/** Lazy Bouncy Castle provider for pacman OpenPGP signing. */
final class ArchCryptoProvider {
  private ArchCryptoProvider() {
  }

  static Provider current() {
    if (NativeDetector.inNativeImage()) return null;
    Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (provider == null) {
      Security.addProvider(new BouncyCastleProvider());
      provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    }
    if (provider == null) {
      throw new IllegalStateException("Bouncy Castle provider is unavailable");
    }
    return provider;
  }
}
