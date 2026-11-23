package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao.DockerConnectorRepository;
import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class DockerConnectorRuntimeTest {
  @TempDir static Path certificates;
  @TempDir Path tomcat;
  static KeyStore keys;
  static KeyStore trust;

  @BeforeAll
  static void certificate() throws Exception {
    Path keyStore = certificates.resolve("server.p12");
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
        "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "test-password")
        .redirectErrorStream(true).redirectOutput(certificates.resolve("keytool.log").toFile()).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keyStore)) { keys.load(input, "test-password".toCharArray()); }
    trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("server", keys.getCertificate("server"));
  }

  @Test
  void servesHttpAndTrustedHttpsAndRejectsUntrustedCertificate() throws Exception {
    int http = port(), https = port();
    var fixture = fixture(Map.of("connectorPort", http, "httpsConnectorPort", https));
    try (fixture; var client = trustedClient(); var untrusted = HttpClient.newHttpClient()) {
      fixture.runtime.validateTlsBundle("registry");
      assertEquals("http:registry", get(client, "http", http));
      assertEquals("https:registry", get(client, "https", https));
      assertThrows(SSLHandshakeException.class, () -> get(untrusted, "https", https));
      assertEquals(2, fixture.runtime.snapshot().connectors().stream().filter(s -> s.active()).count());
      var status = fixture.runtime.snapshot().connectors().stream().filter(s -> s.scheme().equals("https")).findFirst().orElseThrow();
      assertEquals("registry", status.tlsBundle());
      assertEquals(64, status.certificateFingerprint().length());
    }
  }

  @Test
  void reconcilesAnotherReplicasChangesIncludingSamePortProtocolSwitchAndDeletion() throws Exception {
    int port = port();
    var fixture = fixture(Map.of("connectorPort", port));
    try (fixture; var client = trustedClient()) {
      assertEquals("http:registry", get(client, "http", port));
      // Only shared persistence changes: no call to the mutation node's after-commit hook.
      fixture.records.set(List.of(repository(Map.of("httpsConnectorPort", port))));
      fixture.runtime.reconcile();
      assertEquals("https:registry", get(client, "https", port));
      fixture.records.set(List.of());
      fixture.runtime.reconcile();
      assertTrue(fixture.runtime.snapshot().repositoriesByPort().isEmpty());
      assertNull(fixture.manager.repositoryForPort(port));
      assertThrows(IOException.class, () -> get(client, "https", port));
    }
  }

  @Test
  void missingIdentityFailsClosedAndOtherListenersStillStart() throws Exception {
    int http = port(), https = port();
    var fixture = fixture(Map.of("connectorPort", http, "httpsConnectorPort", https, "tlsBundle", "missing"));
    try (fixture; var client = trustedClient()) {
      assertEquals("http:registry", get(client, "http", http));
      assertFalse(fixture.runtime.snapshot().connectors().stream().filter(s -> s.scheme().equals("https")).findFirst().orElseThrow().active());
      assertNull(fixture.manager.repositoryForPort(https));
      assertThrows(IOException.class, () -> get(client, "http", https));
      assertThrows(IOException.class, () -> get(client, "https", https));
      assertTrue(fixture.runtime.lastError().contains("missing"));
      fixture.records.set(List.of(repository(Map.of("connectorPort", http, "httpsConnectorPort", https))));
      fixture.runtime.reconcile();
      assertEquals("https:registry", get(client, "https", https));
      assertNull(fixture.runtime.lastError());
    }
  }

  @Test
  void occupiedPortIsNotActiveAndRetriesAfterPortIsReleased() throws Exception {
    ServerSocket occupied = new ServerSocket(0);
    int port = occupied.getLocalPort();
    var fixture = fixture(Map.of("httpsConnectorPort", port));
    try (fixture; var client = trustedClient()) {
      assertEquals("error", fixture.runtime.snapshot().connectors().get(0).state());
      occupied.close();
      fixture.runtime.reconcile();
      assertEquals("https:registry", get(client, "https", port));
    } finally { occupied.close(); }
  }

  @Test
  void validatesDefaultAndExplicitIdentityAndDoesNotExposePrivateKeyMaterial() {
    var bundles = bundles();
    assertEquals("registry", bundles.defaultBundle());
    assertEquals("registry", bundles.resolve(null).status().name());
    assertThrows(RepositoryValidationException.class, () -> bundles.resolve("missing"));
    assertEquals(List.of("localhost", "127.0.0.1"), bundles.statuses().get(0).domains());
    assertFalse(bundles.statuses().toString().contains("test-password"));
    DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry("trust-only", SslBundle.of(SslStoreBundle.of(null, null, trust)));
    var provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    var invalid = new DockerTlsBundles(provider, "");
    assertThrows(RepositoryValidationException.class, () -> invalid.resolve(null));
    assertFalse(invalid.statuses().get(0).usable());
  }

  @Test
  void rejectsMismatchedCertificateAndPrivateKey() throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyStore mismatch = KeyStore.getInstance("PKCS12");
    mismatch.load(null, null);
    mismatch.setKeyEntry("server", generator.generateKeyPair().getPrivate(), "test-password".toCharArray(),
        new java.security.cert.Certificate[] {keys.getCertificate("server")});
    var registry = new DefaultSslBundleRegistry("mismatch",
        SslBundle.of(SslStoreBundle.of(mismatch, "test-password", null), SslBundleKey.of("test-password", "server")));
    @SuppressWarnings("unchecked") ObjectProvider<SslBundles> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    assertThrows(RepositoryValidationException.class, () -> new DockerTlsBundles(provider, "mismatch").resolve(null));
  }

  @Test
  void passwordlessPemStyleKeyStoreCompletesHandshake() throws Exception {
    KeyStore pem = KeyStore.getInstance("PKCS12");
    pem.load(null, null);
    pem.setKeyEntry("server", keys.getKey("server", "test-password".toCharArray()), new char[0],
        keys.getCertificateChain("server"));
    var registry = new DefaultSslBundleRegistry("pem", SslBundle.of(SslStoreBundle.of(pem, null, null)));
    @SuppressWarnings("unchecked") ObjectProvider<SslBundles> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    int port = port();
    try (var fixture = fixture(Map.of("httpsConnectorPort", port), new DockerTlsBundles(provider, "pem"));
         var client = trustedClient()) {
      assertEquals("https:registry", get(client, "https", port));
    }
  }

  @Test
  void databaseFailurePreservesLastAppliedListenersAndReportsStaleConfiguration() throws Exception {
    var fixture = fixture(Map.of("httpsConnectorPort", port()));
    try (fixture) {
      var applied = fixture.runtime.snapshot();
      RepositoryDao dao = (RepositoryDao) ReflectionTestUtils.getField(fixture.manager, "repositoryDao");
      when(dao.listDockerConnectorRepositories()).thenThrow(new IllegalStateException("database unavailable"));
      assertSame(applied, fixture.runtime.sync());
      assertTrue(fixture.runtime.lastError().contains("Cannot read"));
      assertNotNull(fixture.runtime.nodeId());
    }
  }

  @Test
  void firstConfigurationReadFailureDoesNotRetryInTheErrorPath() {
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.listDockerConnectorRepositories()).thenThrow(new IllegalStateException("database unavailable"));
    var runtime = new DockerConnectorRuntime(new DockerConnectorManager(dao, true, 50, 10, 2000, 0, 0));

    assertTrue(runtime.sync().connectors().isEmpty());
    assertTrue(runtime.snapshot().repositoriesByPort().isEmpty());
    assertTrue(runtime.lastError().contains("Cannot read"));
    verify(dao).listDockerConnectorRepositories();

    doReturn(List.of(repository(Map.of("connectorPort", 5100)))).when(dao).listDockerConnectorRepositories();
    assertSame(runtime.sync(), runtime.snapshot());
    assertEquals(1, runtime.snapshot().connectors().size());
    assertNull(runtime.lastError());
  }

  @Test
  void cannotValidateHttpsWithoutConfiguredIdentityAndDoesNotStartBeforeReady() {
    var manager = new DockerConnectorManager(mock(RepositoryDao.class), true, 50, 10, 2000, 0, 0);
    var runtime = new DockerConnectorRuntime(manager);
    assertThrows(RepositoryValidationException.class, () -> runtime.validateTlsBundle(null));
    assertFalse(runtime.sync().connectors().stream().anyMatch(s -> s.active()));
    runtime.reconcile();
    assertNotNull(runtime.snapshot());
  }

  @Test
  void refusesToAdoptMainServerPortAndHonorsDisabledConnectors() throws Exception {
    try (var fixture = fixture(Map.of("connectorPort", port(), "connectorEnabled", false))) {
      assertEquals("repository-disabled", fixture.runtime.snapshot().connectors().get(0).state());
      fixture.records.set(List.of(repository(Map.of("httpsConnectorPort", fixture.server.getPort()))));
      fixture.runtime.sync();
      assertEquals("error", fixture.runtime.snapshot().connectors().get(0).state());
      assertNull(fixture.manager.repositoryForPort(fixture.server.getPort()));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"EC", "DSA", "Ed25519", "RSASSA-PSS"})
  void validatesSupportedServerKeyAlgorithms(String algorithm) throws Exception {
    Path file = tomcat.resolve("identity.p12");
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "server", "-keyalg", algorithm, "-dname", "CN=localhost", "-validity", "2",
        "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", "test-password")
        .redirectErrorStream(true).redirectOutput(tomcat.resolve("keytool.log").toFile()).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), () -> {
      try { return Files.readString(tomcat.resolve("keytool.log")); } catch (IOException e) { return e.toString(); }
    });
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(file)) { store.load(input, "test-password".toCharArray()); }
    var registry = new DefaultSslBundleRegistry("identity", SslBundle.of(SslStoreBundle.of(store, "test-password", null)));
    @SuppressWarnings("unchecked") ObjectProvider<SslBundles> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    assertTrue(new DockerTlsBundles(provider, "identity").resolve(null).status().usable());
    registry.registerBundle("missing-alias", SslBundle.of(SslStoreBundle.of(store, "test-password", null),
        SslBundleKey.of("test-password", "does-not-exist")));
    assertThrows(RepositoryValidationException.class, () -> new DockerTlsBundles(provider, "missing-alias").resolve(null));
  }

  private Fixture fixture(Map<String, Object> settings) {
    return fixture(settings, bundles());
  }

  private Fixture fixture(Map<String, Object> settings, DockerTlsBundles identities) {
    var records = new AtomicReference<>(List.of(repository(settings)));
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.listDockerConnectorRepositories()).thenAnswer(invocation -> records.get());
    var manager = new DockerConnectorManager(dao, true, 50, 10, 2000, 0, 0);
    var runtime = new DockerConnectorRuntime(manager, identities);
    var factory = new TomcatServletWebServerFactory(0);
    factory.setBaseDirectory(tomcat.toFile());
    TomcatWebServer server = (TomcatWebServer) factory.getWebServer(context -> {
      var servlet = context.addServlet("probe", new HttpServlet() {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
          response.getWriter().write(request.getScheme() + ":" + manager.repositoryForPort(request.getLocalPort()));
        }
      });
      servlet.addMapping("/*");
    });
    server.start();
    var context = mock(org.springframework.context.ConfigurableApplicationContext.class,
        withSettings().extraInterfaces(org.springframework.boot.web.server.context.WebServerApplicationContext.class));
    when(((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer()).thenReturn(server);
    var event = mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
    when(event.getApplicationContext()).thenReturn(context);
    runtime.onApplicationEvent(event);
    return new Fixture(records, manager, runtime, server);
  }

  private static DockerTlsBundles bundles() {
    var registry = new DefaultSslBundleRegistry("registry", SslBundle.of(SslStoreBundle.of(keys, "test-password", null), SslBundleKey.of("test-password", "server")));
    @SuppressWarnings("unchecked") ObjectProvider<SslBundles> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    return new DockerTlsBundles(provider, "registry");
  }

  private static HttpClient trustedClient() {
    return HttpClient.newBuilder().sslContext(SslBundle.of(SslStoreBundle.of(null, null, trust)).createSslContext())
        .connectTimeout(Duration.ofSeconds(3)).build();
  }

  private static String get(HttpClient client, String scheme, int port) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + "/v2/"))
        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  private static int port() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  private static DockerConnectorRepository repository(Map<String, Object> settings) {
    return new DockerConnectorRepository(1L, "registry", RepositoryType.HOSTED, settings);
  }

  private record Fixture(AtomicReference<List<DockerConnectorRepository>> records, DockerConnectorManager manager,
      DockerConnectorRuntime runtime, TomcatWebServer server) implements AutoCloseable {
    @Override public void close() { server.stop(); server.destroy(); }
  }
}
