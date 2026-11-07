package com.github.klboke.kkrepo.server.kkquality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Analysis;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Branch;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Issue;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class KkQualityIngestionServiceTest {
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String SARIF = """
      {"version":"2.1.0","runs":[{
        "tool":{"driver":{"name":"Semgrep","rules":[
          {"id":"java.lang.security.audit.xss","name":"XSS","shortDescription":{"text":"XSS"},
           "properties":{"tags":["security","external/cwe/cwe-079"],
                         "security-severity":"8.5"}}]}},
        "results":[{"ruleId":"java.lang.security.audit.xss","level":"error",
          "message":{"text":"Reflected XSS"},
          "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/App.java"},
                                            "region":{"startLine":42,"endLine":45}}}]}]}]}
      """;

  private static final String SARIF_TWO_ISSUES = """
      {"version":"2.1.0","runs":[{
        "tool":{"driver":{"name":"eslint","rules":[{"id":"a"},{"id":"b"}]}},
        "results":[
          {"ruleId":"a","message":{"text":"first"},
           "locations":[{"physicalLocation":{"artifactLocation":{"uri":"a.ts"}}}]},
          {"ruleId":"b","message":{"text":"second"},
           "locations":[{"physicalLocation":{"artifactLocation":{"uri":"b.ts"}}}]}]}]}
      """;

  private KkQualityDao dao;
  private BlobStorage storage;
  private ObjectMapper objectMapper;
  private KkQualityProperties properties;
  private KkQualityIngestionService service;

  @BeforeEach
  void setUp() {
    dao = mock(KkQualityDao.class);
    BlobStoreDao blobStores = mock(BlobStoreDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    storage = mock(BlobStorage.class);
    objectMapper = new ObjectMapper();
    properties = new KkQualityProperties();

    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);

    when(blobStores.list()).thenReturn(List.of(
        new BlobStoreRecord(1L, "default", "S3", null, null, "bucket", null, Map.of())));
    when(storages.forBlobStoreId(1L)).thenReturn(storage);
    when(storage.put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString()))
        .thenAnswer(invocation -> new BlobReference(
            "bucket", invocation.getArgument(1), invocation.getArgument(4),
            invocation.getArgument(3)));

    service = new KkQualityIngestionService(
        dao, blobStores, storages, objectMapper, properties, transactions);
  }

  /** The controller resolves the tenant before calling the service; these tests bind none. */
  private KkQualityIngestionService.AnalysisStatusView ingest(
      KkQualityIngestionService.IngestRequest request) {
    return service.ingest(request, null);
  }

  private KkQualityIngestionService.AnalysisStatusView ingestWithRepository(
      KkQualityIngestionService.IngestRequest request, Long repositoryId) {
    return service.ingest(request, repositoryId);
  }

  @Test
  void ingestNormalizesSarifAndRecordsOneOccurrence() {
    stubProjectAndBranch();
    when(dao.lockBranch(9L)).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.empty());
    when(dao.insertAnalysis(any())).thenReturn(11L);
    when(dao.listBranchIssues(5L, 9L)).thenReturn(List.of());
    when(dao.insertIssue(
        anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), any(), anyString(),
        anyString(), anyString(), anyString(), anyString(), any())).thenReturn(21L);
    when(dao.countNewIssues(5L, 9L, 11L)).thenReturn(1L);
    when(dao.findAnalysis(11L)).thenReturn(Optional.of(analysis(11L, 1, 1)));

    KkQualityIngestionService.AnalysisStatusView view = ingest(request(SARIF));

    assertEquals("COMPLETE", view.status());
    assertEquals(1, view.issueCount());
    assertEquals(1, view.newIssueCount());
    assertEquals("main", view.branch());
    verify(dao).closeUnseenIssues(eq(5L), eq(9L), eq(11L), any());
    verify(dao).insertOccurrence(eq(21L), eq(11L), eq(42), eq(45), anyString(), any());
    verify(dao).upsertRule(
        eq("Semgrep"), eq("java.lang.security.audit.xss"), eq("XSS"), eq("CRITICAL"), any(),
        any(), any(), any());
    verify(dao).completeAnalysis(eq(11L), eq(1), eq(1), anyString(), any());
    verify(dao).replaceMeasures(eq(11L), any());
  }

  @Test
  void resubmittingTheSameDocumentReturnsTheExistingAnalysis() {
    stubProjectAndBranch();
    when(dao.lockBranch(9L)).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(analysis(11L, 1, 1)));

    KkQualityIngestionService.AnalysisStatusView view = ingest(request(SARIF));

    assertEquals("analysis-11", view.analysisId());
    verify(dao, never()).insertAnalysis(any());
    verify(storage, never()).put(
        anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  void secondAnalysisOfUnchangedTreeObservesInsteadOfDuplicating() {
    stubProjectAndBranch();
    when(dao.lockBranch(9L)).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.empty());
    when(dao.insertAnalysis(any())).thenReturn(12L);
    String fingerprint = KkQualityIngestionService.fingerprint(
        "Semgrep", "java.lang.security.audit.xss", "src/App.java", "Reflected XSS");
    when(dao.listBranchIssues(5L, 9L)).thenReturn(List.of(new Issue(
        30L, 5L, 9L, fingerprint, 11L, "src/App.java", 41, 44, "Semgrep",
        "java.lang.security.audit.xss", "CRITICAL", "VULNERABILITY", "Reflected XSS", "OPEN",
        null, null, CREATED, CREATED, null)));
    when(dao.countNewIssues(5L, 9L, 12L)).thenReturn(0L);
    when(dao.findAnalysis(12L)).thenReturn(Optional.of(analysis(12L, 1, 0)));

    KkQualityIngestionService.AnalysisStatusView view = ingest(request(SARIF));

    assertEquals(0, view.newIssueCount());
    verify(dao, never()).insertIssue(
        anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), any(), anyString(),
        anyString(), anyString(), anyString(), anyString(), any());
    verify(dao).observeIssue(
        eq(30L), eq(12L), eq("src/App.java"), eq(42), eq(45), eq("CRITICAL"), eq("VULNERABILITY"),
        eq("Reflected XSS"), any());
    verify(dao).insertOccurrence(eq(30L), eq(12L), eq(42), eq(45), anyString(), any());
  }

  @Test
  void fingerprintIsStableForWhitespaceButSensitiveToLocationAndRule() {
    String base = KkQualityIngestionService.fingerprint(
        "eslint", "no-unused-vars", "src/a.ts", "unused variable");
    String padded = KkQualityIngestionService.fingerprint(
        "eslint", "no-unused-vars", "src/a.ts", "  unused variable  ");
    String otherFile = KkQualityIngestionService.fingerprint(
        "eslint", "no-unused-vars", "src/b.ts", "unused variable");
    String otherRule = KkQualityIngestionService.fingerprint(
        "eslint", "no-undef", "src/a.ts", "unused variable");

    assertEquals(base, padded);
    assertNotEquals(base, otherFile);
    assertNotEquals(base, otherRule);
    assertEquals(64, base.length());
  }

  @Test
  void rejectsMissingProject() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> ingest(new KkQualityIngestionService.IngestRequest(
            " ", null, "main", null, null, objectMapper.readTree(SARIF))));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
  }

  @Test
  void refusesToWriteIntoAProjectBoundToAnotherRepository() {
    // The project already belongs to repository 3; a caller authorized for repository 4 must not be
    // able to rebind it or write into it.
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(
        new Project(5L, "ext-5", "orders", "Orders", null, "private", 3L, CREATED, CREATED)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.ingest(request(SARIF), 4L));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    verify(dao, never()).bindRepository(anyLong(), anyLong(), any());
    verify(dao, never()).insertAnalysis(any());
  }

  @Test
  void bindsAnUnboundProjectToTheAuthorizedRepository() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(
        new Project(5L, "ext-5", "orders", "Orders", null, "private", null, CREATED, CREATED)));
    when(dao.findProject(5L)).thenReturn(Optional.of(
        new Project(5L, "ext-5", "orders", "Orders", null, "private", 4L, CREATED, CREATED)));
    when(dao.findBranch(5L, "main"))
        .thenReturn(Optional.of(new Branch(9L, 5L, "main", true, CREATED)));
    when(dao.lockBranch(9L)).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(analysis(11L, 1, 1)));

    ingestWithRepository(request(SARIF), 4L);

    verify(dao).bindRepository(eq(5L), eq(4L), any());
  }

  @Test
  void rejectsDocumentsWithoutRuns() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> ingest(request("{\"version\":\"2.1.0\"}")));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
  }

  @Test
  void rejectsUnsupportedSarifVersions() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> ingest(request("{\"version\":\"1.0\",\"runs\":[]}")));
    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
  }

  @Test
  void rejectsOversizedDocuments() {
    properties.setMaxSarifBytes(1024);
    String padded = SARIF.replace("2.1.0", "2.1.0" + " ".repeat(2048));
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> ingest(request(padded)));
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
  }

  @Test
  void rejectsTooManyIssues() {
    properties.setMaxIssues(1);
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> ingest(request(SARIF_TWO_ISSUES)));
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
  }

  private KkQualityIngestionService.IngestRequest request(String sarif) {
    try {
      return new KkQualityIngestionService.IngestRequest(
          "orders", "Orders", "main", "rev-1", null, objectMapper.readTree(sarif));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void stubProjectAndBranch() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(
        new Project(5L, "ext-5", "orders", "Orders", null, "private", null, CREATED, CREATED)));
    when(dao.findBranch(5L, "main"))
        .thenReturn(Optional.of(new Branch(9L, 5L, "main", true, CREATED)));
  }

  private static Analysis analysis(long id, int issues, int newIssues) {
    return new Analysis(
        id, 5L, 9L, "analysis-" + id, "COMPLETE", "rev-1", sha(SARIF), issues, newIssues, CREATED,
        CREATED, CREATED, null);
  }

  /** Mirrors the service: the digest is over the serialized tree, not the raw request bytes. */
  private static String sha(String document) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode node = mapper.readTree(document);
      byte[] bytes = mapper.writeValueAsBytes(node);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
