package com.github.klboke.kkrepo.core.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Immutable, process-local TLS configuration rebuilt from the operator's PEM bundle at startup.
 * Every replica must receive the same bundle and restart after a change. No certificates are
 * persisted, and neither the JVM default SSLContext nor its truststore properties are modified.
 */
public final class OutboundTlsTrust {
  private static final Pattern CERTIFICATE = Pattern.compile(
      "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----");
  private final SSLContext sslContext;
  private final X509TrustManager[] trustManagers;

  private OutboundTlsTrust(SSLContext sslContext, X509TrustManager[] trustManagers) {
    this.sslContext = sslContext;
    this.trustManagers = trustManagers;
  }

  public static OutboundTlsTrust defaults() {
    return new OutboundTlsTrust(null, null);
  }

  /** Adds PEM certificates to the active JSSE truststore, including an explicit javax.net.ssl one. */
  public static OutboundTlsTrust fromPemBundle(String path) {
    if (path == null || path.isBlank()) {
      return defaults();
    }
    try {
      String pem = Files.readString(Path.of(path.trim()), StandardCharsets.US_ASCII);
      KeyStore combined = KeyStore.getInstance("PKCS12");
      combined.load(null, null);
      TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      defaults.init((KeyStore) null);
      int index = 0;
      for (TrustManager manager : defaults.getTrustManagers()) {
        if (manager instanceof X509TrustManager x509) {
          for (X509Certificate certificate : x509.getAcceptedIssuers()) {
            combined.setCertificateEntry("default-" + index++, certificate);
          }
        }
      }

      CertificateFactory certificates = CertificateFactory.getInstance("X.509");
      var matcher = CERTIFICATE.matcher(pem);
      int end = 0;
      int count = 0;
      while (matcher.find()) {
        requireWhitespace(pem.substring(end, matcher.start()));
        var certificate = certificates.generateCertificate(new ByteArrayInputStream(
            matcher.group().getBytes(StandardCharsets.US_ASCII)));
        combined.setCertificateEntry("additional-" + count++, certificate);
        end = matcher.end();
      }
      requireWhitespace(pem.substring(end));
      if (count == 0) {
        throw new IllegalArgumentException("PEM bundle must contain at least one CERTIFICATE block");
      }

      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(combined);
      X509TrustManager manager = Arrays.stream(factory.getTrustManagers())
          .filter(X509TrustManager.class::isInstance)
          .map(X509TrustManager.class::cast)
          .findFirst().orElseThrow(() -> new GeneralSecurityException("No X.509 trust manager available"));
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {manager}, null);
      return new OutboundTlsTrust(context, new X509TrustManager[] {manager});
    } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Cannot load kkrepo.tls.ca-certificates from '" + path + "': " + exception.getMessage(), exception);
    }
  }

  private static void requireWhitespace(String value) {
    if (!value.isBlank()) {
      throw new IllegalArgumentException("PEM bundle must contain only CERTIFICATE blocks and whitespace");
    }
  }

  public boolean configured() {
    return sslContext != null;
  }

  public SSLContext sslContext() {
    return sslContext;
  }

  public X509TrustManager[] trustManagers() {
    return trustManagers == null ? null : trustManagers.clone();
  }
}
