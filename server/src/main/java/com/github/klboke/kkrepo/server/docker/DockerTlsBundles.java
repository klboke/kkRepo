package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

/** Deployment-owned inbound identities. No private key material is exposed in repository APIs. */
@Component
public class DockerTlsBundles {
  private final ObjectProvider<SslBundles> bundles;
  private final String defaultBundle;

  public DockerTlsBundles(ObjectProvider<SslBundles> bundles,
      @Value("${kkrepo.tls.default-bundle:${server.ssl.bundle:}}") String defaultBundle) {
    this.bundles = bundles;
    this.defaultBundle = defaultBundle;
  }

  public String defaultBundle() {
    return defaultBundle;
  }

  public List<BundleStatus> statuses() {
    SslBundles registry = bundles.getIfAvailable();
    if (registry == null) return List.of();
    return registry.getBundleNames().stream().sorted().map(name -> {
      try {
        return resolve(name).status();
      } catch (RepositoryValidationException e) {
        return new BundleStatus(name, false, null, null, null, null, e.getMessage());
      }
    }).toList();
  }

  Resolved resolve(String requested) {
    String name = requested == null || requested.isBlank() ? defaultBundle : requested.trim();
    if (name == null || name.isBlank()) {
      throw new RepositoryValidationException("Configure a default inbound TLS bundle before enabling HTTPS");
    }
    try {
      SslBundles registry = bundles.getIfAvailable();
      if (registry == null) throw new IllegalStateException();
      SslBundle bundle = registry.getBundle(name);
      KeyStore store = bundle.getStores().getKeyStore();
      if (store == null) throw new IllegalStateException();
      String alias = bundle.getKey().getAlias();
      if (alias == null) {
        List<String> aliases = Collections.list(store.aliases()).stream().filter(key -> {
          try { return store.isKeyEntry(key); }
          catch (Exception e) { return false; }
        }).toList();
        if (aliases.size() != 1) throw new IllegalStateException();
        alias = aliases.get(0);
      }
      String password = bundle.getKey().getPassword();
      if (password == null) password = bundle.getStores().getKeyStorePassword();
      if (!(store.getKey(alias, password == null ? null : password.toCharArray()) instanceof PrivateKey privateKey)) {
        throw new IllegalStateException();
      }
      X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
      certificate.checkValidity();
      verifyIdentity(privateKey, certificate);
      String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      List<String> domains = certificate.getSubjectAlternativeNames() == null ? List.of()
          : certificate.getSubjectAlternativeNames().stream()
              .filter(entry -> Integer.valueOf(2).equals(entry.get(0)) || Integer.valueOf(7).equals(entry.get(0)))
              .map(entry -> entry.get(1).toString()).toList();
      return new Resolved(bundle, alias, password, new BundleStatus(name, true,
          certificate.getSubjectX500Principal().getName(), domains,
          certificate.getNotAfter().toInstant().toString(), fingerprint, null));
    } catch (Exception e) {
      // Do not propagate provider exceptions: these can contain secret paths/configuration values.
      throw new RepositoryValidationException("TLS bundle '" + name
          + "' must contain a currently valid server certificate and its private key (with a key alias if ambiguous)");
    }
  }

  private static void verifyIdentity(PrivateKey key, X509Certificate certificate) throws Exception {
    String algorithm = switch (key.getAlgorithm()) {
      case "RSA" -> "SHA256withRSA";
      case "EC" -> "SHA256withECDSA";
      case "DSA" -> "SHA256withDSA";
      case "EdDSA" -> ((EdECKey) key).getParams().getName();
      default -> key.getAlgorithm();
    };
    Signature signature = Signature.getInstance(algorithm);
    if ("RSASSA-PSS".equals(algorithm)) {
      signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
    }
    byte[] challenge = "kkrepo-inbound-identity-validation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    signature.initSign(key);
    signature.update(challenge);
    byte[] proof = signature.sign();
    signature.initVerify(certificate.getPublicKey());
    signature.update(challenge);
    if (!signature.verify(proof)) throw new IllegalStateException("Certificate and private key do not match");
  }

  void configure(Connector connector, Resolved resolved) {
    AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
    connector.setScheme("https");
    connector.setSecure(true);
    protocol.setSSLEnabled(true);
    // Null selects Tomcat's directly constructed JSSE implementation, including in Native.
    protocol.setSslImplementationName(null);
    SslBundle bundle = resolved.bundle();
    SSLHostConfig host = new SSLHostConfig();
    host.setSslProtocol(bundle.getProtocol());
    String[] protocols = bundle.getOptions().getEnabledProtocols();
    host.setProtocols(protocols == null ? "TLSv1.2,TLSv1.3" : String.join(",", protocols));
    String[] ciphers = bundle.getOptions().getCiphers();
    if (ciphers != null) host.setCiphers(String.join(",", ciphers));
    SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
    certificate.setCertificateKeystore(bundle.getStores().getKeyStore());
    certificate.setCertificateKeyAlias(resolved.alias());
    // Tomcat otherwise falls back to its default keystore password for unencrypted PEM bundles.
    certificate.setCertificateKeystorePassword(bundle.getStores().getKeyStorePassword() == null
        ? "" : bundle.getStores().getKeyStorePassword());
    certificate.setCertificateKeyPassword(resolved.password() == null ? "" : resolved.password());
    host.addCertificate(certificate);
    protocol.addSslHostConfig(host);
  }

  record Resolved(SslBundle bundle, String alias, String password, BundleStatus status) { }

  public record BundleStatus(String name, boolean usable, String subject, List<String> domains,
      String expiresAt, String fingerprint, String error) { }
}
