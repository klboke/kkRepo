package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Iterator;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.springframework.stereotype.Component;

/** Verifies upstream detached SHA256SUMS signatures before publishing proxy metadata. */
@Component
final class TerraformSignatureVerifier {
  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  void verify(byte[] shasums, byte[] signatureBytes, List<String> publicKeys) {
    try {
      PGPSignature signature = signature(signatureBytes);
      for (String publicKey : publicKeys) {
        PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(publicKey.getBytes(StandardCharsets.UTF_8))),
            new JcaKeyFingerprintCalculator());
        PGPPublicKey key = rings.getPublicKey(signature.getKeyID());
        if (key == null) continue;
        signature.init(new JcaPGPContentVerifierBuilderProvider()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME), key);
        signature.update(shasums);
        if (signature.verify()) return;
      }
      throw invalid();
    } catch (MavenExceptions.BadUpstreamException e) {
      throw e;
    } catch (Exception e) {
      throw new MavenExceptions.BadUpstreamException(
          "Terraform upstream checksum signature is invalid", e);
    }
  }

  private static PGPSignature signature(byte[] bytes) throws Exception {
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(bytes)), new JcaKeyFingerprintCalculator());
    Object value = objects.nextObject();
    if (value instanceof PGPCompressedData compressed) {
      objects = new PGPObjectFactory(compressed.getDataStream(), new JcaKeyFingerprintCalculator());
      value = objects.nextObject();
    }
    if (value instanceof PGPSignatureList signatures && !signatures.isEmpty()) return signatures.get(0);
    throw invalid();
  }

  private static MavenExceptions.BadUpstreamException invalid() {
    return new MavenExceptions.BadUpstreamException("Terraform upstream checksum signature is invalid");
  }
}
