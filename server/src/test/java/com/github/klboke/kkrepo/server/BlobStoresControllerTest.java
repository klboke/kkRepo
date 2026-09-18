package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.argThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.FileBlobStorePathValidator;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin;
import com.github.klboke.kkrepo.storage.file.config.FileStorageProperties;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import com.github.klboke.kkrepo.storage.s3.admin.S3BlobStoreAdmin;
import org.springframework.test.web.servlet.ResultMatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class BlobStoresControllerTest {
  @TempDir
  Path tempDir;

  @Test
  void emptyDatabaseDoesNotExposeRuntimeConfiguredBlobStore() {
    BlobStoresController controller = new BlobStoresController(
        new EmptyBlobStoreDao(),
        null,
        null,
        null,
        null,
        new S3StorageProperties(),
        null,
        null);

    BlobStoresController.BlobStoresResponse response = controller.list();

    assertTrue(response.stores().isEmpty());
  }

  @Test
  void createsAndChecksFileBlobStore() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    FileStorageProperties fileProperties = new FileStorageProperties();
    fileProperties.setBaseDir(tempDir.toString());
    FileBlobStorageFactory fileFactory = new FileBlobStorageFactory(fileProperties);
    FileBlobStorePathValidator pathValidator = new FileBlobStorePathValidator();
    BlobStoresController controller = new BlobStoresController(
        dao,
        null,
        new FileBlobStoreAdmin(pathValidator, fileFactory),
        fileFactory,
        pathValidator,
        new S3StorageProperties(),
        null,
        new MockEnvironment());

    BlobStoresController.BlobStoreView created = controller.create(new BlobStoresController.BlobStoreRequest(
        "disk",
        "file",
        "file",
        null,
        null,
        null,
        null,
        "hosted",
        null,
        null,
        null));

    assertEquals("file", created.type());
    assertEquals("file", created.engine());
    assertEquals(0, created.objectCount());
    assertEquals(0, created.totalSize());
    assertTrue(Files.isDirectory(tempDir.resolve("hosted")));

    BlobStoresController.BlobStoreProbeResult result = controller.check(created.id());

    assertTrue(result.ok());
    assertTrue(result.summary().bucketExists());
    assertEquals(0, result.summary().objectCount());
    assertEquals(0, result.summary().totalSize());
  }

  @Test
  void listsBlobStoresWithoutAssetUsageAggregation() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.insert(fileRecord("disk-a", "hosted-a"));
    dao.insert(fileRecord("disk-b", "hosted-b"));
    FileStorageProperties fileProperties = new FileStorageProperties();
    fileProperties.setBaseDir(tempDir.toString());
    FileBlobStorageFactory fileFactory = new FileBlobStorageFactory(fileProperties);
    FileBlobStorePathValidator pathValidator = new FileBlobStorePathValidator();
    BlobStoresController controller = new BlobStoresController(
        dao,
        null,
        new FileBlobStoreAdmin(pathValidator, fileFactory),
        fileFactory,
        pathValidator,
        new S3StorageProperties(),
        null,
        new MockEnvironment());

    BlobStoresController.BlobStoresResponse response = controller.list();

    assertEquals(2, response.stores().size());
    assertEquals(0, response.stores().get(0).objectCount());
    assertEquals(0, response.stores().get(0).totalSize());
    assertEquals(0, response.stores().get(1).objectCount());
    assertEquals(0, response.stores().get(1).totalSize());
  }

  @Test
  void s3BlobStoreCreatePersistsMultipartTuningAttributes() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    BlobStoresController controller = new BlobStoresController(
        dao,
        null,
        null,
        null,
        null,
        new S3StorageProperties(),
        null,
        new MockEnvironment());

    BlobStoresController.BlobStoreView created = controller.create(new BlobStoresController.BlobStoreRequest(
        "s3",
        "s3",
        "oss-native",
        "http://oss.local",
        "cn-hangzhou",
        "bucket",
        "repo",
        null,
        "ak",
        "sk",
        true,
        32L * 1024 * 1024,
        8L * 1024 * 1024,
        6));

    BlobStoreRecord stored = dao.findById(created.id()).orElseThrow();
    assertEquals(32L * 1024 * 1024, stored.attributes().get("multipartThresholdBytes"));
    assertEquals(8L * 1024 * 1024, stored.attributes().get("multipartPartSizeBytes"));
    assertEquals(6, stored.attributes().get("multipartConcurrency"));
    assertEquals(6, created.multipartConcurrency());
  }

  @Test
  void awsCreateAcceptsAbsentEmptyAndBlankCredentialsAndRejectsPartialPairs() throws Exception {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    var mvc = MockMvcBuilders.standaloneSetup(s3Controller(dao, new S3StorageProperties(), null)).build();
    for (String keys : List.of("", ", \"accessKey\":\"\", \"secretKey\":\" \"")) {
      String name = "aws-" + dao.list().size();
      mvc.perform(post("/internal/blob-stores").contentType(MediaType.APPLICATION_JSON)
          .content(s3Request(name, "aws-s3", keys)))
          .andExpect(status().isOk()).andExpect(jsonField("credentialSource", "default"));
      BlobStoreRecord saved = dao.findByName(name).orElseThrow();
      assertEquals("", saved.attributes().get("accessKey"));
      assertEquals("", saved.attributes().get("secretKey"));
    }
    for (String keys : List.of(
        ", \"accessKey\":\"ak\"", ", \"secretKey\":\"sk\"",
        ", \"credentialSource\":\"static\"", ", \"credentialSource\":\"unknown\"")) {
      mvc.perform(post("/internal/blob-stores").contentType(MediaType.APPLICATION_JSON)
          .content(s3Request("invalid", "aws-s3", keys))).andExpect(status().isBadRequest());
    }
    mvc.perform(post("/internal/blob-stores").contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("oss", "oss-native", ""))).andExpect(status().isBadRequest());
    mvc.perform(post("/internal/blob-stores").contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("oss", "oss-native", ", \"credentialSource\":\"default\"")))
        .andExpect(status().isBadRequest());
    assertEquals(2, dao.list().size());
  }

  @Test
  void updateKeepsStaticSecretsUnlessDefaultChainIsExplicitlySelected() throws Exception {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    S3StorageProperties defaults = new S3StorageProperties();
    defaults.setAccessKey("global-ak");
    defaults.setSecretKey("global-sk");
    var admin = mock(S3BlobStoreAdmin.class);
    var controller = s3Controller(dao, defaults, admin);
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    mvc.perform(post("/internal/blob-stores").contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ", \"accessKey\":\"ak\", \"secretKey\":\"sk\"")))
        .andExpect(status().isOk()).andExpect(jsonField("credentialSource", "static"));
    long id = dao.findByName("aws").orElseThrow().id();
    mvc.perform(put("/internal/blob-stores/" + id).contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ", \"accessKey\":\"\", \"secretKey\":\"\"")))
        .andExpect(status().isOk()).andExpect(jsonField("secretConfigured", true));
    assertEquals("sk", dao.findById(id).orElseThrow().attributes().get("secretKey"));
    mvc.perform(put("/internal/blob-stores/" + id).contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ", \"credentialSource\":\"default\"")))
        .andExpect(status().isOk()).andExpect(jsonField("credentialSource", "default"))
        .andExpect(jsonField("accessKeyConfigured", false))
        .andExpect(jsonField("secretConfigured", false));
    assertEquals("", dao.findById(id).orElseThrow().attributes().get("accessKey"));
    assertEquals("", dao.findById(id).orElseThrow().attributes().get("secretKey"));
    // Both management summaries and subsequent edits must retain the empty state despite globals.
    mvc.perform(put("/internal/blob-stores/" + id).contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ""))).andExpect(status().isOk());
    verify(admin, atLeastOnce()).summary(argThat(config -> config.usesDefaultCredentials()));
    assertEquals("", dao.findById(id).orElseThrow().attributes().get("secretKey"));
    mvc.perform(put("/internal/blob-stores/" + id).contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ", \"credentialSource\":\"static\"")))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/internal/blob-stores/" + id).contentType(MediaType.APPLICATION_JSON)
        .content(s3Request("aws", "aws-s3", ", \"credentialSource\":\"static\", \"accessKey\":\"new-ak\", \"secretKey\":\"new-sk\"")))
        .andExpect(status().isOk()).andExpect(jsonField("credentialSource", "static"));
    assertEquals("new-sk", dao.findById(id).orElseThrow().attributes().get("secretKey"));
  }

  private static BlobStoresController s3Controller(InMemoryBlobStoreDao dao, S3StorageProperties defaults,
      S3BlobStoreAdmin admin) {
    return new BlobStoresController(dao, admin, null, null, null, defaults, null, new MockEnvironment());
  }

  private static ResultMatcher jsonField(String key, Object value) {
    String expected = "\"" + key + "\":" + (value instanceof String ? "\"" + value + "\"" : value);
    return result -> assertTrue(result.getResponse().getContentAsString().contains(expected),
        result.getResponse().getContentAsString());
  }

  private static String s3Request(String name, String engine, String keys) {
    return "{\"name\":\"" + name + "\",\"type\":\"s3\",\"engine\":\"" + engine
        + "\",\"endpoint\":\"http://localhost:9000\",\"region\":\"us-east-1\",\"bucket\":\"bucket\"" + keys + "}";
  }

  @Test
  void productionFileBlobStoresRequireExplicitStrongConsistencySharedFilesystem() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    FileStorageProperties fileProperties = new FileStorageProperties();
    fileProperties.setBaseDir(tempDir.toString());
    FileBlobStorageFactory fileFactory = new FileBlobStorageFactory(fileProperties);
    FileBlobStorePathValidator pathValidator = new FileBlobStorePathValidator();
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    BlobStoresController controller = new BlobStoresController(
        dao,
        null,
        new FileBlobStoreAdmin(pathValidator, fileFactory),
        fileFactory,
        pathValidator,
        new S3StorageProperties(),
        null,
        environment);

    ResponseStatusException productionDisabled = assertThrows(
        ResponseStatusException.class,
        () -> controller.create(fileRequest("disk", "hosted")));
    assertEquals(HttpStatus.BAD_REQUEST.value(), productionDisabled.getStatusCode().value());
    assertTrue(productionDisabled.getReason().contains("production-enabled"));

    fileProperties.setProductionEnabled(true);
    ResponseStatusException noSharedFilesystem = assertThrows(
        ResponseStatusException.class,
        () -> controller.create(fileRequest("disk", "hosted")));
    assertEquals(HttpStatus.BAD_REQUEST.value(), noSharedFilesystem.getStatusCode().value());
    assertTrue(noSharedFilesystem.getReason().contains("strong-consistency shared filesystem"));

    fileProperties.setSharedFilesystem(true);
    BlobStoresController.BlobStoreView created = controller.create(fileRequest("disk", "hosted"));

    assertEquals("file", created.type());
  }

  @Test
  void rendersBlobStoreValidationReasonForTheAdminUi() throws Exception {
    Path blockingFile = tempDir.resolve("blocking-file");
    Files.writeString(blockingFile, "not a directory");
    FileStorageProperties fileProperties = new FileStorageProperties();
    fileProperties.setBaseDir(blockingFile.toString());
    FileBlobStorageFactory fileFactory = new FileBlobStorageFactory(fileProperties);
    FileBlobStorePathValidator pathValidator = new FileBlobStorePathValidator();
    BlobStoresController controller = new BlobStoresController(
        new InMemoryBlobStoreDao(),
        null,
        null,
        fileFactory,
        pathValidator,
        new S3StorageProperties(),
        null,
        new MockEnvironment());

    var result = MockMvcBuilders.standaloneSetup(controller).build()
        .perform(post("/internal/blob-stores")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"disk","type":"file","engine":"file","path":"default"}
                """))
        .andExpect(status().isBadRequest())
        .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    assertTrue(responseBody.contains("\"message\":\"File blob store path cannot be created or written:"));
    assertTrue(responseBody.contains("default"));
  }

  @Test
  void fallsBackToStatusTextWhenResponseStatusReasonIsMissingOrBlank() {
    BlobStoresController controller = new BlobStoresController(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);

    var missingReason = controller.handleResponseStatus(
        new ResponseStatusException(HttpStatus.BAD_REQUEST));
    var blankReason = controller.handleResponseStatus(
        new ResponseStatusException(HttpStatus.BAD_REQUEST, " "));

    assertEquals(HttpStatus.BAD_REQUEST, missingReason.getStatusCode());
    assertEquals(Map.of("message", "400 BAD_REQUEST"), missingReason.getBody());
    assertEquals(HttpStatus.BAD_REQUEST, blankReason.getStatusCode());
    assertEquals(Map.of("message", "400 BAD_REQUEST"), blankReason.getBody());
  }

  private static BlobStoresController.BlobStoreRequest fileRequest(String name, String path) {
    return new BlobStoresController.BlobStoreRequest(
        name,
        "file",
        "file",
        null,
        null,
        null,
        null,
        path,
        null,
        null,
        null);
  }

  private static BlobStoreRecord fileRecord(String name, String path) {
    return new BlobStoreRecord(
        null,
        name,
        "file",
        null,
        null,
        null,
        "",
        Map.of("engine", "file", "path", path));
  }

  private static final class EmptyBlobStoreDao extends BlobStoreDaoAdapter {
    EmptyBlobStoreDao() {
      super(null, null);
    }

    @Override
    public List<BlobStoreRecord> list() {
      return List.of();
    }
  }

  private static final class InMemoryBlobStoreDao extends BlobStoreDaoAdapter {
    private final Map<Long, BlobStoreRecord> records = new LinkedHashMap<>();
    private long nextId = 1;

    InMemoryBlobStoreDao() {
      super(null, null);
    }

    @Override
    public long insert(BlobStoreRecord record) {
      long id = nextId++;
      records.put(id, new BlobStoreRecord(
          id,
          record.name(),
          record.type(),
          record.endpoint(),
          record.region(),
          record.bucket(),
          record.prefix(),
          record.attributes()));
      return id;
    }

    @Override
    public void updateById(BlobStoreRecord record) {
      records.put(record.id(), record);
    }

    @Override
    public Optional<BlobStoreRecord> findById(long id) {
      return Optional.ofNullable(records.get(id));
    }

    @Override
    public Optional<BlobStoreRecord> findByName(String name) {
      return records.values().stream()
          .filter(record -> record.name().equals(name))
          .findFirst();
    }

    @Override
    public List<BlobStoreRecord> list() {
      return records.values().stream()
          .sorted(Comparator.comparing(BlobStoreRecord::name))
          .toList();
    }
  }
}
