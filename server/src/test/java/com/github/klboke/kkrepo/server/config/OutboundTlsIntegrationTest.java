package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.support.FakeHttpProxyServer;
import com.github.klboke.kkrepo.storage.s3.OssClientFactory;
import com.github.klboke.kkrepo.storage.s3.OssNativeBlobStorage;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3ClientFactory;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;

class OutboundTlsIntegrationTest {
  private static Identity firstCa;
  private static Identity secondCa;
  private static Identity server;
  private static final byte[] CONTENT = "custom-ca-artifact".getBytes(StandardCharsets.UTF_8);
  @TempDir Path directory;

  @BeforeAll
  static void certificates() throws Exception {
    firstCa = identity("First CA", null, false);
    secondCa = identity("Second CA", null, false);
    server = identity("localhost", firstCa, false);
  }

  @Test
  void emptyConfigurationKeepsExistingDefaults() {
    assertFalse(OutboundTlsTrust.fromPemBundle(null).configured());
    assertFalse(OutboundTlsTrust.fromPemBundle("  ").configured());
    try (var context = context("")) {
      context.refresh();
      assertFalse(context.getBean(OutboundTlsTrust.class).configured());
    }
  }

  @Test
  void invalidBundlesFailStartupRatherThanSilentlyUsingDefaultTrust() throws Exception {
    for (String content : new String[] {"", " \n", "not a certificate", firstCa.pem() + "garbage",
        "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----",
        firstCa.pem() + "-----BEGIN CERTIFICATE-----\ntruncated",
        "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----"}) {
      Path path = Files.writeString(directory.resolve("invalid.pem"), content);
      try (var context = context(path.toString())) {
        var failure = assertThrows(BeansException.class, context::refresh);
        assertTrue(failure.getMessage().contains("kkrepo.tls.ca-certificates"));
      }
    }
    assertThrows(IllegalArgumentException.class,
        () -> OutboundTlsTrust.fromPemBundle(directory.resolve("missing.pem").toString()));
    assertThrows(IllegalArgumentException.class, () -> OutboundTlsTrust.fromPemBundle(directory.toString()));
  }

  @Test
  void runtimeConfigurationInjectsTheTrustIntoStorageAndProxyClients() throws Exception {
    Path path = Files.writeString(directory.resolve("ca.pem"), firstCa.pem());
    try (var fixture = fixture(server); var context = context(path.toString())) {
      context.register(S3ClientFactory.class, OssClientFactory.class, ProxiedHttpClientFactory.class);
      context.refresh();
      try (var client = context.getBean(S3ClientFactory.class).client(config(fixture.url()))) {
        assertArrayEquals(CONTENT, client.getObjectAsBytes(r -> r.bucket("bucket").key("artifact")).asByteArray());
      }
      proxyGet(context.getBean(ProxiedHttpClientFactory.class), fixture.url(), null);
    }
  }

  @Test
  void s3OssAndUpstreamRequestsTrustEachCaInBundle() throws Exception {
    var trust = bundle(firstCa, secondCa);
    for (Identity identity : new Identity[] {server, identity("localhost", secondCa, false)}) {
      try (var fixture = fixture(identity)) {
        assertAllClients(trust, fixture, true);
      }
    }
  }

  @Test
  void defaultTrustAndUnrelatedCaRejectThePrivateServer() throws Exception {
    try (var fixture = fixture(server)) {
      assertAllClients(OutboundTlsTrust.defaults(), fixture, false);
      assertAllClients(bundle(secondCa), fixture, false);
    }
  }

  @Test
  void additionalTrustStillRejectsWrongHostAndExpiredLeafCertificates() throws Exception {
    for (Identity identity : new Identity[] {
        identity("wrong.example", firstCa, false), identity("localhost", firstCa, true)}) {
      try (var fixture = fixture(identity)) {
        assertAllClients(bundle(firstCa), fixture, false);
      }
    }
  }

  private void assertAllClients(OutboundTlsTrust trust, Fixture fixture, boolean succeeds) throws Exception {
    try (var client = new S3ClientFactory(trust).client(config(fixture.url()))) {
      verify(succeeds, () -> {
        client.putObject(r -> r.bucket("bucket").key("artifact"), RequestBody.fromBytes(CONTENT));
        assertArrayEquals(CONTENT, client.getObjectAsBytes(r -> r.bucket("bucket").key("artifact")).asByteArray());
      });
    }
    try (var client = new OssClientFactory(trust).client(config(fixture.url()));
         var storage = new OssNativeBlobStorage(client, config(fixture.url()))) {
      verify(succeeds, () -> {
        BlobReference reference = storage.put("raw", "artifact", new ByteArrayInputStream(CONTENT), CONTENT.length, "checksum");
        try (var input = storage.get(reference).orElseThrow()) {
          assertArrayEquals(CONTENT, input.readAllBytes());
        }
      });
    }
    try (var client = new ProxiedHttpClientFactory(60000, 1000, trust);
         var proxy = FakeHttpProxyServer.startTunneling(request -> null, request -> fixture.address());
         var socks = socksTunnel(fixture.address())) {
      verify(succeeds, () -> proxyGet(client, fixture.url(), null));
      verify(succeeds, () -> proxyGet(client, fixture.url(), new OutboundProxyConfig(
          OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), null, null)));
      verify(succeeds, () -> proxyGet(client, fixture.url(), new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", socks.getLocalPort(), null, null)));
    }
  }

  private static void proxyGet(ProxiedHttpClientFactory client, String url, OutboundProxyConfig proxy) throws Exception {
    var target = OutboundRequestPolicy.allowPrivateForTests().resolveHttpTarget(url + "/artifact", "TLS fixture");
    try (var response = client.execute("tls-test", proxy, "GET", target, Map.of(), 2000)) {
      assertEquals(200, response.status());
      assertArrayEquals(CONTENT, response.body().readAllBytes());
    }
  }

  private static void verify(boolean succeeds, org.junit.jupiter.api.function.Executable operation) throws Exception {
    if (succeeds) {
      assertDoesNotThrow(operation);
    } else {
      Throwable error = assertThrows(Exception.class, operation);
      while (!(error instanceof SSLException) && error.getCause() != null) {
        error = error.getCause();
      }
      assertInstanceOf(SSLException.class, error);
    }
  }

  private OutboundTlsTrust bundle(Identity... identities) throws Exception {
    StringBuilder pem = new StringBuilder();
    for (Identity identity : identities) {
      pem.append(identity.pem());
    }
    return OutboundTlsTrust.fromPemBundle(Files.writeString(directory.resolve("bundle.pem"), pem).toString());
  }

  private static AnnotationConfigApplicationContext context(String path) {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(
        new MapPropertySource("test", Map.of("kkrepo.tls.ca-certificates", path)));
    context.register(OutboundTlsConfiguration.class);
    return context;
  }

  private static S3BlobStoreConfig config(String endpoint) {
    return S3BlobStoreConfig.of(1L, "tls", endpoint, "us-east-1", "bucket", "",
        Map.of("accessKey", "test-access", "secretKey", "test-secret", "connectionTimeoutMs", 1000,
            "socketTimeoutMs", 1000));
  }

  private static Identity identity(String name, Identity issuer, boolean expired) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    X500Name subject = new X500Name("CN=" + name);
    Instant now = Instant.now();
    var builder = new JcaX509v3CertificateBuilder(
        issuer == null ? subject : new X500Name(issuer.certificate().getSubjectX500Principal().getName()),
        new BigInteger(120, new java.security.SecureRandom()),
        Date.from(now.minus(2, ChronoUnit.DAYS)),
        Date.from(expired ? now.minus(1, ChronoUnit.DAYS) : now.plus(2, ChronoUnit.DAYS)),
        subject, keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(issuer == null));
    if (issuer != null) {
      GeneralName[] names = name.equals("localhost")
          ? new GeneralName[] {new GeneralName(GeneralName.dNSName, name),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")}
          : new GeneralName[] {new GeneralName(GeneralName.dNSName, name)};
      builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
    }
    var signer = new JcaContentSignerBuilder("SHA256withRSA").build(
        issuer == null ? keys.getPrivate() : issuer.keys().getPrivate());
    return new Identity(keys, new JcaX509CertificateConverter().getCertificate(builder.build(signer)));
  }

  private static Fixture fixture(Identity identity) throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    keys.setKeyEntry("server", identity.keys().getPrivate(), "test".toCharArray(),
        new X509Certificate[] {identity.certificate()});
    KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "test".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), null, null);
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.createContext("/", exchange -> {
      try (exchange) {
        if (exchange.getRequestMethod().equals("PUT")) {
          assertArrayEquals(CONTENT, exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().set("ETag", "\"test\"");
          exchange.sendResponseHeaders(200, -1);
        } else {
          exchange.sendResponseHeaders(200, CONTENT.length);
          exchange.getResponseBody().write(CONTENT);
        }
      }
    });
    server.start();
    return new Fixture(server);
  }

  private static ServerSocket socksTunnel(InetSocketAddress target) throws IOException {
    ServerSocket listener = new ServerSocket();
    listener.bind(new InetSocketAddress("127.0.0.1", 0));
    Thread.ofVirtual().start(() -> {
      while (!listener.isClosed()) {
        try {
          Socket downstream = listener.accept();
          Thread.ofVirtual().start(() -> {
            try (downstream; Socket upstream = new Socket()) {
              downstream.setSoTimeout(5000);
              var input = downstream.getInputStream();
              var output = downstream.getOutputStream();
              assertEquals(5, input.read());
              input.readNBytes(input.read());
              output.write(new byte[] {5, 0});
              output.flush();
              byte[] request = input.readNBytes(4);
              int addressLength = request[3] == 1 ? 4 : request[3] == 4 ? 16 : input.read();
              input.readNBytes(addressLength + 2);
              upstream.connect(target, 1000);
              output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
              output.flush();
              Thread.ofVirtual().start(() -> {
                try {
                  input.transferTo(upstream.getOutputStream());
                } catch (IOException ignored) {
                  // TLS rejection closes the tunnel before any application request.
                }
              });
              upstream.getInputStream().transferTo(output);
            } catch (IOException ignored) {
              // The client closes successful requests and rejected handshakes alike.
            }
          });
        } catch (IOException ignored) {
          return;
        }
      }
    });
    return listener;
  }

  private record Identity(KeyPair keys, X509Certificate certificate) {
    String pem() throws Exception {
      return "-----BEGIN CERTIFICATE-----\n"
          + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
          + "\n-----END CERTIFICATE-----\n";
    }
  }

  private record Fixture(HttpsServer server) implements AutoCloseable {
    String url() { return "https://127.0.0.1:" + server.getAddress().getPort(); }
    InetSocketAddress address() { return server.getAddress(); }
    public void close() { server.stop(0); }
  }
}
