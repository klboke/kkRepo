package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.plugin.api.PluginException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Verifies that every code entry of a plugin JAR is signed by the same certificate trusted by the
 * configured PKCS12 truststore.
 *
 * <p>The verifier validates the full PKIX certificate path against the truststore and additionally
 * pins the leaf certificate fingerprint. This prevents a JAR from mixing a trusted plugin class with
 * an untrusted helper class.
 */
public final class SignedJarVerifier {
  private static final String MANIFEST = "meta-inf/manifest.mf";

  private final Set<String> trustedFingerprints;
  private final Set<TrustAnchor> trustAnchors;

  public SignedJarVerifier(Path truststore, char[] password) throws PluginException {
    TrustedMaterial material = loadTrustedMaterial(truststore, password);
    this.trustedFingerprints = material.fingerprints();
    this.trustAnchors = material.anchors();
    if (trustedFingerprints.isEmpty() || trustAnchors.isEmpty()) {
      throw new PluginException("Plugin truststore contains no certificate: " + truststore);
    }
  }

  public void verify(Path jar) throws PluginException {
    String signer = null;
    try (JarFile jarFile = new JarFile(jar.toFile(), true)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || isSignatureMetadata(entry.getName())) {
          continue;
        }
        try (InputStream input = jarFile.getInputStream(entry)) {
          input.readAllBytes();
        }
        Certificate[] certificates = entry.getCertificates();
        if (certificates == null || certificates.length == 0) {
          throw new PluginException("Unsigned plugin entry: " + entry.getName() + " in " + jar);
        }
        List<X509Certificate> chain = x509Chain(certificates);
        if (chain.isEmpty()) {
          throw new PluginException("No X509 signer for entry: " + entry.getName() + " in " + jar);
        }
        X509Certificate leaf = chain.getFirst();
        leaf.checkValidity();
        validateChain(chain, jar);
        String fingerprint = fingerprint(leaf);
        if (!trustedFingerprints.contains(fingerprint)) {
          throw new PluginException(
              "Untrusted plugin signer " + fingerprint + " for " + jar);
        }
        if (signer == null) {
          signer = fingerprint;
        } else if (!signer.equals(fingerprint)) {
          throw new PluginException("Plugin JAR mixes signers: " + jar);
        }
      }
    } catch (SecurityException invalid) {
      throw new PluginException("Invalid plugin signature: " + jar, invalid);
    } catch (CertificateException | IOException invalid) {
      throw new PluginException("Unable to verify plugin JAR: " + jar, invalid);
    }
    if (signer == null) {
      throw new PluginException("Plugin JAR contains no signed code entry: " + jar);
    }
  }

  private void validateChain(List<X509Certificate> chain, Path jar) throws PluginException {
    try {
      CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
      PKIXParameters parameters = new PKIXParameters(trustAnchors);
      parameters.setRevocationEnabled(false);
      CertPathValidator.getInstance("PKIX").validate(path, parameters);
      for (X509Certificate certificate : chain) {
        certificate.checkValidity();
      }
    } catch (GeneralSecurityException invalid) {
      throw new PluginException("Plugin certificate chain is not trusted: " + jar, invalid);
    }
  }

  private static TrustedMaterial loadTrustedMaterial(Path truststore, char[] password)
      throws PluginException {
    try (InputStream input = Files.newInputStream(truststore)) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(input, password);
      Set<String> fingerprints = new LinkedHashSet<>();
      Set<TrustAnchor> anchors = new LinkedHashSet<>();
      Enumeration<String> aliases = store.aliases();
      while (aliases.hasMoreElements()) {
        Certificate certificate = store.getCertificate(aliases.nextElement());
        if (certificate instanceof X509Certificate x509) {
          fingerprints.add(fingerprint(x509));
          anchors.add(new TrustAnchor(x509, null));
        }
      }
      return new TrustedMaterial(Set.copyOf(fingerprints), Set.copyOf(anchors));
    } catch (IOException | CertificateException | java.security.KeyStoreException
             | NoSuchAlgorithmException invalid) {
      throw new PluginException("Unable to load plugin truststore: " + truststore, invalid);
    }
  }

  private static List<X509Certificate> x509Chain(Certificate[] certificates) {
    List<X509Certificate> chain = new ArrayList<>();
    for (Certificate certificate : certificates) {
      if (certificate instanceof X509Certificate x509) {
        chain.add(x509);
      }
    }
    return List.copyOf(chain);
  }

  private static boolean isSignatureMetadata(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    if (MANIFEST.equals(normalized)) {
      return true;
    }
    if (!normalized.startsWith("meta-inf/")) {
      return false;
    }
    return normalized.endsWith(".sf")
        || normalized.endsWith(".rsa")
        || normalized.endsWith(".dsa")
        || normalized.endsWith(".ec");
  }

  private static String fingerprint(X509Certificate certificate) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(certificate.getEncoded());
      return HexFormat.of().formatHex(digest);
    } catch (CertificateException | NoSuchAlgorithmException invalid) {
      throw new IllegalStateException("Unable to compute certificate fingerprint", invalid);
    }
  }

  private record TrustedMaterial(Set<String> fingerprints, Set<TrustAnchor> anchors) {
  }
}
