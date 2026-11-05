package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;

class PypiStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final PypiHostedService pypiHosted = mock(PypiHostedService.class);
  private final PypiStagingContentAdapter adapter =
      new PypiStagingContentAdapter(hosted, pypiHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, "pypi-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String filename = "demo-1.0.0-py3-none-any.whl";
  private final String packagePath = "packages/demo/1.0.0/" + filename;

  @Test
  void derivesTheCanonicalPackagePathFromTheWheelMetadata() throws Exception {
    PypiHostedService service = new PypiHostedService(
        mock(AssetDao.class), mock(ComponentDao.class), mock(RepositoryIndexRebuildDao.class),
        mock(BlobStorageRegistry.class), mock(PypiAssetWriter.class), mock(PypiAssetReader.class),
        mock(AssetMetadataCache.class), 0);
    Path wheel = Files.createTempFile("pypi-staging-test-", ".whl");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(wheel))) {
      zip.putNextEntry(new ZipEntry("demo-1.0.0.dist-info/METADATA"));
      zip.write("Metadata-Version: 2.1\nName: demo\nVersion: 1.0.0\n"
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    try {
      PypiHostedService.UploadPlan plan = service.planUpload(wheel, filename, "application/zip");
      assertEquals(packagePath, plan.packagePath());
      assertEquals("demo", plan.name());
      assertEquals("1.0.0", plan.version());
    } finally {
      Files.deleteIfExists(wheel);
    }
  }

  @Test
  void stagesAMultipartUploadAndRejectsDetachedSignatures() throws Exception {
    when(pypiHosted.planUpload(any(), any(), any()))
        .thenReturn(new PypiHostedService.UploadPlan(
            packagePath, "demo", "1.0.0", "application/zip", filename));
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(packagePath), any(), eq("application/zip"), any(), any(), any()))
        .thenReturn(blob());
    MockHttpServletRequest request = multipartRequest();
    assertTrue(adapter.stageRequest(runtime, "", request, "alice", "127.0.0.1").isPresent());

    MockHttpServletRequest signed = multipartRequest();
    signed.addPart(new MockPart("gpg_signature", "demo.asc", new byte[] {1}));
    org.junit.jupiter.api.Assertions.assertThrows(
        PypiExceptions.BadRequestException.class,
        () -> adapter.stageRequest(runtime, "", signed, "alice", "127.0.0.1"));
  }

  @Test
  void preflightRejectsDeletesAndExistingPackages() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, "pypi-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    assertTrue(adapter.validateItem(repository, item("DELETE"), null).contains("deletions"));
    assertTrue(adapter.validateItem(repository, item("ADD"), mock(AssetRecord.class))
        .contains("already exists"));
  }

  @Test
  void acceptsPublicationOnlyAtTheRepositoryRoot() {
    assertTrue(adapter.supportsPublication(""));
    assertTrue(adapter.supportsPublication("/"));
    assertFalse(adapter.supportsPublication("packages/demo/1.0.0/" + filename));
  }

  private MockHttpServletRequest multipartRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("multipart/form-data; boundary=----test");
    MockPart content = new MockPart(
        "content", filename, new byte[] {1, 2, 3}, org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
    request.addPart(content);
    return request;
  }

  private StagingItemRecord item(String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.PYPI, packagePath, new byte[32], action, "STAGED",
        1L, 1L, "application/zip", 3L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        null, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 3L, "application/zip", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
