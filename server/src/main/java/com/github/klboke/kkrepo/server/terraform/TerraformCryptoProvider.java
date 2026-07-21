package com.github.klboke.kkrepo.server.terraform;

import java.security.Provider;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.NativeDetector;

/** Selects the crypto provider without embedding Bouncy Castle provider state in native images. */
final class TerraformCryptoProvider {
  private TerraformCryptoProvider() {}

  static Provider current() {
    return current(NativeDetector.inNativeImage());
  }

  static Provider current(boolean nativeImage) {
    if (nativeImage) return null;
    Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (provider == null) {
      Security.addProvider(new BouncyCastleProvider());
      provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    }
    if (provider == null) {
      throw new IllegalStateException("Bouncy Castle security provider could not be installed");
    }
    return provider;
  }
}
