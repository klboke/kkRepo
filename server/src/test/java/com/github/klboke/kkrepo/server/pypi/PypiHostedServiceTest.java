package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PypiHostedServiceTest {
  @Test
  void uploadEnqueuesProjectAndRootIndexRebuildsWithoutSynchronousIndexWrite() throws Exception {
    RecordingIndexRebuildDao indexRebuildDao = new RecordingIndexRebuildDao();
    RecordingWriter writer = new RecordingWriter();
    EmptyAssetDao assetDao = new EmptyAssetDao();
    AssetMetadataCache cache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    PypiHostedService service = new PypiHostedService(
        assetDao,
        new EmptyComponentDao(),
        indexRebuildDao,
        new FixedBlobStorageRegistry(),
        writer,
        new PypiAssetReader(assetDao, new FixedBlobStorageRegistry()),
        cache,
        0);

    service.upload(
        runtime(),
        Map.of(
            ":action", "file_upload",
            "name", "Demo_Pkg",
            "version", "1.0.0"),
        new MockMultipartFile(
            "content",
            "demo_pkg-1.0.0.tar.gz",
            "application/gzip",
            "payload".getBytes()),
        null,
        "admin",
        "127.0.0.1");

    assertEquals(1, writer.packageWrites);
    assertEquals(0, writer.indexWrites);
    assertEquals(List.of(
        "10:" + RepositoryIndexRebuildDao.PYPI_PROJECT + ":demo-pkg",
        "10:" + RepositoryIndexRebuildDao.PYPI_ROOT + ":"),
        indexRebuildDao.enqueues);
  }

  @Test
  void validatesUploadActionFieldsDigestAndWritePolicy() throws Exception {
    RecordingWriter writer = new RecordingWriter();
    PypiHostedService service = service(new EmptyAssetDao(), new RecordingIndexRebuildDao(), writer);
    MockMultipartFile content = new MockMultipartFile(
        "content", "demo-1.0.0.tar.gz", "application/gzip", "payload".getBytes());

    assertThrows(PypiExceptions.MethodNotAllowed.class, () -> service.upload(
        runtime(RepositoryType.PROXY, "ALLOW", 1L), Map.of(":action", "file_upload"),
        content, null, "admin", null));
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> service.upload(runtime(), Map.of(), content, null, "admin", null));
    assertThrows(PypiExceptions.BadRequestException.class, () -> service.upload(
        runtime(), Map.of(":action", "file_upload", "name", "demo"),
        content, null, "admin", null));
    assertThrows(PypiExceptions.BadRequestException.class, () -> service.upload(
        runtime(), Map.of(
            ":action", "file_upload", "name", "demo", "version", "1.0.0",
            "md5_digest", "deadbeef"),
        content, null, "admin", null));
    assertThrows(PypiExceptions.WritePolicyDenied.class, () -> service.upload(
        runtime(RepositoryType.HOSTED, "DENY", 1L),
        Map.of(":action", "file_upload", "name", "demo", "version", "1.0.0"),
        content, null, "admin", null));
    assertEquals(0, writer.packageWrites);
  }

  @Test
  void uploadWritesPackageAndSignatureWhenMd5Matches() throws Exception {
    RecordingWriter writer = new RecordingWriter();
    RecordingIndexRebuildDao rebuilds = new RecordingIndexRebuildDao();
    PypiHostedService service = service(new EmptyAssetDao(), rebuilds, writer);
    byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile content = new MockMultipartFile(
        "content", "../demo_pkg-1.0.0.tar.gz", "application/gzip", payload);
    MockMultipartFile signature = new MockMultipartFile(
        "gpg_signature", "demo_pkg-1.0.0.tar.gz.asc",
        "application/pgp-signature", "signature".getBytes(StandardCharsets.UTF_8));

    PypiResponse response = service.upload(
        runtime(),
        Map.of(
            ":action", "file_upload",
            "name", "Demo_Pkg",
            "version", "1.0.0",
            "md5_digest", HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(payload))),
        content,
        signature,
        "admin",
        "127.0.0.1");

    assertEquals(200, response.status());
    assertEquals(2, writer.packageWrites);
    assertEquals(List.of("package", "package-signature"), writer.kinds);
    assertEquals(List.of(
        "packages/demo-pkg/1.0.0/demo_pkg-1.0.0.tar.gz",
        "packages/demo-pkg/1.0.0/demo_pkg-1.0.0.tar.gz.asc"), writer.paths);
    assertEquals(2, rebuilds.enqueues.size());
  }

  @Test
  void uploadAssetReadsWheelMetadataAndFallsBackToFilename() throws Exception {
    RecordingWriter writer = new RecordingWriter();
    PypiHostedService service = service(new EmptyAssetDao(), new RecordingIndexRebuildDao(), writer);
    byte[] wheel = wheel("""
        Metadata-Version: 2.3
        Name: Demo_Pkg
        Version: 2.0.0
        Summary: wheel metadata
        Requires-Python: >=3.11
        """);

    service.uploadAsset(
        runtime(),
        new MockMultipartFile(
            "asset", "ignored-0.0.1-py3-none-any.whl", "application/zip", wheel),
        "admin",
        null);
    service.uploadAsset(
        runtime(),
        new MockMultipartFile(
            "asset", "fallback_pkg-3.1.4.zip", "application/zip", emptyZip()),
        "admin",
        null);

    assertEquals(List.of(
        "packages/demo-pkg/2.0.0/ignored-0.0.1-py3-none-any.whl",
        "packages/fallback-pkg/3.1.4/fallback_pkg-3.1.4.zip"), writer.paths);
    assertEquals("wheel metadata", writer.attributes.getFirst().get("summary"));
    assertEquals(">=3.11", writer.attributes.getFirst().get("requires_python"));
  }

  @Test
  void uploadAssetRejectsUnknownCoordinatesAndExistingAsset() {
    RecordingWriter writer = new RecordingWriter();
    EmptyAssetDao empty = new EmptyAssetDao();
    PypiHostedService service = service(empty, new RecordingIndexRebuildDao(), writer);
    assertThrows(PypiExceptions.BadRequestException.class, () -> service.uploadAsset(
        runtime(),
        new MockMultipartFile("asset", "unknown.whl", "application/zip", emptyZip()),
        "admin", null));

    EmptyAssetDao existing = new EmptyAssetDao() {
      @Override
      public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
        return path.startsWith("packages/")
            ? Optional.of(new AssetRecord(
                1L, repositoryId, null, null, RepositoryFormat.PYPI, path, null,
                "demo-1.0.0.whl", "package", "application/zip", 1L,
                null, null, Map.of()))
            : Optional.empty();
      }
    };
    assertThrows(PypiExceptions.WritePolicyDenied.class, () -> service(
        existing, new RecordingIndexRebuildDao(), writer).uploadAsset(
            runtime(),
            new MockMultipartFile(
                "asset", "demo-1.0.0.whl", "application/zip", emptyZip()),
            "admin", null));
  }

  private static PypiHostedService service(
      AssetDao assetDao, RecordingIndexRebuildDao rebuildDao, RecordingWriter writer) {
    AssetMetadataCache cache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    FixedBlobStorageRegistry registry = new FixedBlobStorageRegistry();
    return new PypiHostedService(
        assetDao,
        new EmptyComponentDao(),
        rebuildDao,
        registry,
        writer,
        new PypiAssetReader(assetDao, registry),
        cache,
        0);
  }

  private static byte[] wheel(String metadata) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("demo_pkg-2.0.0.dist-info/METADATA"));
      zip.write(metadata.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static byte[] emptyZip() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream ignored = new ZipOutputStream(bytes)) {
    }
    return bytes.toByteArray();
  }

  private static RepositoryRuntime runtime() {
    return runtime(RepositoryType.HOSTED, "ALLOW", 1L);
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, String writePolicy, Long blobStoreId) {
    return new RepositoryRuntime(
        10,
        type == RepositoryType.HOSTED ? "pypi-hosted" : "pypi-proxy",
        RepositoryFormat.PYPI,
        type,
        type == RepositoryType.HOSTED ? "pypi-hosted" : "pypi-proxy",
        true,
        blobStoreId,
        writePolicy,
        null,
        null,
        true,
        null,
        null,
        null,
        null, null, List.of());
  }

  private static class EmptyAssetDao extends AssetDao {
    EmptyAssetDao() {
      super(null, null);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.empty();
    }

    @Override
    public List<PypiProjectIndexRow> listPypiProjectIndexRows(long repositoryId, String normalizedName) {
      fail("PyPI upload must not synchronously rebuild project simple index");
      return List.of();
    }
  }

  private static class EmptyComponentDao extends ComponentDao {
    EmptyComponentDao() {
      super(null, null);
    }
  }

  private static class RecordingIndexRebuildDao extends RepositoryIndexRebuildDao {
    final List<String> enqueues = new ArrayList<>();

    RecordingIndexRebuildDao() {
      super(null);
    }

    @Override
    public void enqueue(long repositoryId, String indexKind) {
      enqueues.add(repositoryId + ":" + indexKind + ":");
    }

    @Override
    public void enqueue(long repositoryId, String indexKind, String scopeKey) {
      enqueues.add(repositoryId + ":" + indexKind + ":" + scopeKey);
    }
  }

  private static class RecordingWriter extends PypiAssetWriter {
    int packageWrites;
    int indexWrites;
    final List<String> paths = new ArrayList<>();
    final List<String> kinds = new ArrayList<>();
    final List<Map<String, Object>> attributes = new ArrayList<>();

    RecordingWriter() {
      super(null, null, null, new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
          null, null);
    }

    @Override
    Stored write(
        RepositoryRuntime runtime,
        BlobStorage storage,
        long blobStoreId,
        String path,
        InputStream body,
        String contentType,
        String kind,
        PackageCoordinate coordinate,
        Map<String, Object> assetAttributes,
        Map<String, String> extraBlobAttributes,
        String createdBy,
        String createdByIp) {
      packageWrites++;
      paths.add(path);
      kinds.add(kind);
      attributes.add(assetAttributes);
      return null;
    }

    @Override
    Stored writeBytes(
        RepositoryRuntime runtime,
        BlobStorage storage,
        long blobStoreId,
        String path,
        byte[] body,
        String contentType,
        String kind,
        PackageCoordinate coordinate,
        Map<String, Object> assetAttributes,
        String createdBy,
        String createdByIp) {
      indexWrites++;
      fail("PyPI upload must enqueue index rebuilds instead of writing index blobs synchronously");
      return null;
    }
  }

  private static class FixedBlobStorageRegistry extends BlobStorageRegistry {
    FixedBlobStorageRegistry() {
      super(null, null, null, null, 0L);
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return new NoopBlobStorage();
    }
  }

  private static class NoopBlobStorage implements BlobStorage {
    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference("test", "unused", sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }
}
