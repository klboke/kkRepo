package com.github.klboke.kkrepo.core.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class OutboundTlsTrustTest {
  @TempDir Path directory;

  @Test
  void emptyConfigurationLeavesClientDefaultsIntact() {
    assertFalse(OutboundTlsTrust.fromPemBundle(null).configured());
    assertFalse(OutboundTlsTrust.fromPemBundle(" \n").configured());
    assertNull(OutboundTlsTrust.defaults().sslContext());
    assertNull(OutboundTlsTrust.defaults().trustManagers());
  }

  @Test
  void mergesMultipleCertificatesAndDuplicatesWhileRetainingDefaultRoots() throws Exception {
    var originalContext = SSLContext.getDefault();
    var originalProperty = System.getProperty("javax.net.ssl.trustStore");
    var trust = bundle(pem("first") + pem("second") + pem("first"));
    assertTrue(trust.configured());
    assertNotNull(trust.sslContext());
    var accepted = new HashSet<>(Arrays.asList(trust.trustManagers()[0].getAcceptedIssuers()));
    assertTrue(accepted.contains(certificate("first")));
    assertTrue(accepted.contains(certificate("second")));
    var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    defaults.init((KeyStore) null);
    for (var manager : defaults.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        assertTrue(accepted.containsAll(Arrays.asList(x509.getAcceptedIssuers())));
      }
    }
    assertSame(originalContext, SSLContext.getDefault());
    assertEquals(originalProperty, System.getProperty("javax.net.ssl.trustStore"));
    var managers = trust.trustManagers();
    managers[0] = null;
    assertNotNull(trust.trustManagers()[0]);
  }

  @Test
  void rejectsMissingEmptyMalformedAndMixedFiles() throws Exception {
    for (String content : new String[] {"", " \n", "not a certificate", pem("first") + "garbage",
        "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----",
        pem("first") + "-----BEGIN CERTIFICATE-----\ntruncated",
        "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----"}) {
      var error = assertThrows(IllegalArgumentException.class, () -> bundle(content));
      assertTrue(error.getMessage().contains("kkrepo.tls.ca-certificates"));
    }
    assertThrows(IllegalArgumentException.class,
        () -> OutboundTlsTrust.fromPemBundle(directory.resolve("missing.pem").toString()));
    assertThrows(IllegalArgumentException.class, () -> OutboundTlsTrust.fromPemBundle(directory.toString()));
  }

  @Test
  void augmentsExplicitJksAndPkcs12Truststores() throws Exception {
    for (String type : new String[] {"JKS", "PKCS12"}) {
      KeyStore store = KeyStore.getInstance(type);
      store.load(null, null);
      store.setCertificateEntry("existing", certificate("first"));
      Path path = directory.resolve("existing." + type);
      try (var output = Files.newOutputStream(path)) {
        store.store(output, "test".toCharArray());
      }
      Map<String, String> overrides = Map.of("javax.net.ssl.trustStore", path.toString(),
          "javax.net.ssl.trustStoreType", type, "javax.net.ssl.trustStorePassword", "test");
      Map<String, String> original = new LinkedHashMap<>();
      try {
        overrides.forEach((name, value) -> original.put(name, System.setProperty(name, value)));
        var accepted = Arrays.asList(bundle(pem("second")).trustManagers()[0].getAcceptedIssuers());
        assertTrue(accepted.contains(certificate("first")));
        assertTrue(accepted.contains(certificate("second")));
      } finally {
        original.forEach((name, value) -> {
          if (value == null) {
            System.clearProperty(name);
          } else {
            System.setProperty(name, value);
          }
        });
      }
    }
  }

  private OutboundTlsTrust bundle(String pem) throws Exception {
    return OutboundTlsTrust.fromPemBundle(Files.writeString(directory.resolve("bundle.pem"), pem).toString());
  }

  private static String pem(String name) throws Exception {
    try (var input = OutboundTlsTrustTest.class.getResourceAsStream("/tls/" + name + "-ca.pem")) {
      return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static X509Certificate certificate(String name) throws Exception {
    return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
        new ByteArrayInputStream(pem(name).getBytes(StandardCharsets.US_ASCII)));
  }
}
