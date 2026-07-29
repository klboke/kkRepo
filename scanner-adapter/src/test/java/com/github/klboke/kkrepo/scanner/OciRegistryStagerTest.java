package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdOutputStream;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OciRegistryStagerTest {
  private static final String REPOSITORY = "repo/image";
  private static final String MANIFEST_TYPE =
      "application/vnd.oci.image.manifest.v1+json";
  private static final String INDEX_TYPE =
      "application/vnd.oci.image.index.v1+json";
  private static final String CONFIG_TYPE =
      "application/vnd.oci.image.config.v1+json";
  private static final String LAYER_TYPE =
      "application/vnd.oci.image.layer.v1.tar+gzip";
  private static final String ZSTD_LAYER_TYPE =
      "application/vnd.oci.image.layer.v1.tar+zstd";

  @TempDir Path temporary;

  private Registry registry;

  @AfterEach
  void stopRegistry() {
    if (registry != null) registry.close();
  }

  @Test
  void stagesAndInspectsARegistryImageBeforeSyftRuns() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = gzipTar("usr/lib/package.txt", "hello".getBytes(StandardCharsets.UTF_8));
    byte[] manifest = manifest(config, layer);
    registry = new Registry(Map.of(
        manifestPath(digest(manifest)), manifest,
        blobPath(digest(config)), config,
        blobPath(digest(layer)), layer));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    OciRegistryStager.StagedImage staged = stager.stage(
        request(registry.url(), digest(manifest), List.of("linux/amd64")),
        limits(1024 * 1024, 1024 * 1024, 1024 * 1024),
        temporary,
        new ScanDeadline(10));

    assertEquals(List.of("linux/amd64"), staged.availablePlatforms());
    assertTrue(staged.missingPlatforms().isEmpty());
    assertEquals(manifest.length + config.length + layer.length, staged.transferredBytes());
    assertEquals(1, staged.archiveEntries());
    assertEquals(5, staged.expandedBytes());
    assertTrue(Files.exists(staged.layout().resolve("oci-layout")));
    assertTrue(Files.exists(staged.layout().resolve("index.json")));
    assertTrue(Files.exists(
        staged.layout().resolve("blobs/sha256/" + digest(layer).substring(7))));
    assertTrue(registry.authorizations().stream().allMatch("Bearer scoped-token"::equals));
  }

  @Test
  void inspectsZstdCompressedOciLayersWithinTheSameResourceBudget() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = zstdTar("usr/lib/package.txt", "zstd".getBytes(StandardCharsets.UTF_8));
    byte[] manifest = manifest(config, layer, ZSTD_LAYER_TYPE);
    registry = new Registry(Map.of(
        manifestPath(digest(manifest)), manifest,
        blobPath(digest(config)), config,
        blobPath(digest(layer)), layer));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    OciRegistryStager.StagedImage staged = stager.stage(
        request(registry.url(), digest(manifest), List.of("linux/amd64")),
        limits(1024 * 1024, 1024 * 1024, 1024 * 1024),
        temporary,
        new ScanDeadline(10));

    assertEquals(1, staged.archiveEntries());
    assertEquals(4, staged.expandedBytes());
  }

  @Test
  void rejectsAggregateDescriptorBytesBeforeDownloadingAnyLayer() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    String hugeLayerDigest = "sha256:" + "a".repeat(64);
    byte[] manifest = ("""
        {"schemaVersion":2,"mediaType":"%s",
         "config":{"mediaType":"%s","digest":"%s","size":%d},
         "layers":[{"mediaType":"%s","digest":"%s","size":4096}]}
        """).formatted(
            MANIFEST_TYPE,
            CONFIG_TYPE,
            digest(config),
            config.length,
            LAYER_TYPE,
            hugeLayerDigest)
        .getBytes(StandardCharsets.UTF_8);
    registry = new Registry(Map.of(manifestPath(digest(manifest)), manifest));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> stager.stage(
            request(registry.url(), digest(manifest), List.of("linux/amd64")),
            limits(2048, 8192, 8192),
            temporary,
            new ScanDeadline(10)));

    assertEquals("OCI_INPUT_TOO_LARGE", failure.code());
    assertEquals(List.of(manifestPath(digest(manifest))), registry.paths());
  }

  @Test
  void appliesOneExpandedByteBudgetAcrossOciLayers() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = gzipTar("large.bin", new byte[4096]);
    byte[] manifest = manifest(config, layer);
    registry = new Registry(Map.of(
        manifestPath(digest(manifest)), manifest,
        blobPath(digest(config)), config,
        blobPath(digest(layer)), layer));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> stager.stage(
            request(registry.url(), digest(manifest), List.of("linux/amd64")),
            limits(1024 * 1024, 100, 8192),
            temporary,
            new ScanDeadline(10)));

    assertEquals("ARCHIVE_EXPANDED_LIMIT", failure.code());
  }

  @Test
  void stagesOnlyRequestedIndexPlatformsAndReportsMissingOnes() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = gzipTar("package.txt", "ok".getBytes(StandardCharsets.UTF_8));
    byte[] child = manifest(config, layer);
    byte[] index = ("""
        {"schemaVersion":2,"mediaType":"%s","manifests":[{
          "mediaType":"%s","digest":"%s","size":%d,
          "platform":{"os":"linux","architecture":"amd64"}}]}
        """).formatted(INDEX_TYPE, MANIFEST_TYPE, digest(child), child.length)
        .getBytes(StandardCharsets.UTF_8);
    registry = new Registry(Map.of(
        manifestPath(digest(index)), index,
        manifestPath(digest(child)), child,
        blobPath(digest(config)), config,
        blobPath(digest(layer)), layer));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    OciRegistryStager.StagedImage staged = stager.stage(
        request(
            registry.url(),
            digest(index),
            List.of("linux/amd64", "linux/arm64")),
        limits(1024 * 1024, 1024 * 1024, 1024 * 1024),
        temporary,
        new ScanDeadline(10));

    assertEquals(List.of("linux/amd64"), staged.availablePlatforms());
    assertEquals(List.of("linux/arm64"), staged.missingPlatforms());
    assertTrue(registry.paths().contains(manifestPath(digest(child))));
  }

  @Test
  void rejectsRegistryBytesThatDoNotMatchTheRequestedDigest() throws Exception {
    byte[] body = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
    String requested = "sha256:" + "b".repeat(64);
    registry = new Registry(Map.of(manifestPath(requested), body));
    OciRegistryStager stager =
        new OciRegistryStager(new ObjectMapper(), new ArchiveGuard());

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> stager.stage(
            request(registry.url(), requested, List.of("linux/amd64")),
            limits(1024 * 1024, 1024 * 1024, 1024 * 1024),
            temporary,
            new ScanDeadline(10)));

    assertEquals("OCI_BLOB_DIGEST_MISMATCH", failure.code());
  }

  private static ResourceLimits limits(
      long maxInput, long maxExpanded, long maxSingleFile) {
    return new ResourceLimits(maxInput, 100, maxExpanded, maxSingleFile, 2, 30);
  }

  private static OciScanRequest request(
      String registryUrl, String digest, List<String> platforms) {
    return new OciScanRequest(
        "v1",
        "run",
        "key",
        registryUrl,
        REPOSITORY,
        digest,
        platforms,
        "scoped-token",
        "profile",
        limits(1024 * 1024, 1024 * 1024, 1024 * 1024));
  }

  private static byte[] manifest(byte[] config, byte[] layer) {
    return manifest(config, layer, LAYER_TYPE);
  }

  private static byte[] manifest(byte[] config, byte[] layer, String layerType) {
    return ("""
        {"schemaVersion":2,"mediaType":"%s",
         "config":{"mediaType":"%s","digest":"%s","size":%d},
         "layers":[{"mediaType":"%s","digest":"%s","size":%d}]}
        """).formatted(
            MANIFEST_TYPE,
            CONFIG_TYPE,
            digest(config),
            config.length,
            layerType,
            digest(layer),
            layer.length)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] gzipTar(String path, byte[] value) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry(path);
      entry.setSize(value.length);
      tar.putArchiveEntry(entry);
      tar.write(value);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static byte[] zstdTar(String path, byte[] value) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZstdOutputStream zstd = new ZstdOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
      TarArchiveEntry entry = new TarArchiveEntry(path);
      entry.setSize(value.length);
      tar.putArchiveEntry(entry);
      tar.write(value);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static String digest(byte[] value) {
    return "sha256:" + ScannerDocumentMapper.sha256(value);
  }

  private static String manifestPath(String digest) {
    return "/v2/" + REPOSITORY + "/manifests/" + digest;
  }

  private static String blobPath(String digest) {
    return "/v2/" + REPOSITORY + "/blobs/" + digest;
  }

  private static final class Registry implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, byte[]> content;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    private Registry(Map<String, byte[]> content) throws IOException {
      this.content = new LinkedHashMap<>(content);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::serve);
      server.start();
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private List<String> paths() {
      return List.copyOf(paths);
    }

    private List<String> authorizations() {
      return List.copyOf(authorizations);
    }

    private void serve(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getRawPath();
      paths.add(path);
      authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = content.get(path);
      if (body == null) {
        exchange.sendResponseHeaders(404, -1);
      } else {
        exchange.getResponseHeaders().set("Content-Length", Long.toString(body.length));
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
