package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Item;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.ItemState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Job;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Source;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmImportServiceTest {
  private final OhpmImportDao imports = mock(OhpmImportDao.class);
  private final OhpmImportSource sources = mock(OhpmImportSource.class);
  private final OhpmHostedService hosted = mock(OhpmHostedService.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RepositoryDao records = mock(RepositoryDao.class);
  private final TransientTransactionRetry transactions = mock(TransientTransactionRetry.class);
  private final RepositoryRuntime target = mock(RepositoryRuntime.class);
  private final Runnable authorize = mock(Runnable.class);
  private final OhpmImportService service = new OhpmImportService(imports, sources, hosted, runtimes,
      transactions, new ObjectMapper(), records);
  private final Job directory = job(Source.DIRECTORY, JobState.PLANNING);

  @BeforeEach
  void setup() {
    when(transactions.execute(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    when(imports.findJob("job")).thenReturn(Optional.of(directory));
    when(records.findByName("target")).thenReturn(Optional.of(new RepositoryRecord(1L, "target",
        RepositoryFormat.OHPM, RepositoryType.HOSTED, "ohpm-hosted", true, 1L,
        null, null, null, null, "ALLOW_ONCE", false, Map.of())));
    when(runtimes.resolveFreshById(1L)).thenReturn(Optional.of(target));
    when(target.format()).thenReturn(RepositoryFormat.OHPM);
    when(target.isHosted()).thenReturn(true);
    when(target.online()).thenReturn(true);
  }

  @Test
  void listsOnlyTheRequestedRepositoryAndRejectsAnUnknownTarget() {
    when(imports.listJobs(1L, 100)).thenReturn(List.of(directory));
    assertEquals(List.of(directory), service.jobs("target"));
    assertEquals(404, assertThrows(OhpmException.class, () -> service.jobs("missing")).status());
    verify(imports).listJobs(1L, 100);
  }

  @Test
  void rejectsUnsupportedSourcesAndNonWritableTargetsBeforeCreatingAJob() {
    assertEquals(400, assertThrows(OhpmException.class,
        () -> service.create("target", null, null, "alice")).status());
    assertEquals(400, assertThrows(OhpmException.class,
        () -> service.create("target", Source.DIRECTORY, "proxy", "alice")).status());
    when(target.isHosted()).thenReturn(false);
    assertEquals(400, assertThrows(OhpmException.class,
        () -> service.create("target", Source.DIRECTORY, null, "alice")).status());
    when(target.isHosted()).thenReturn(true);
    when(target.online()).thenReturn(false);
    assertEquals(409, assertThrows(OhpmException.class,
        () -> service.create("target", Source.DIRECTORY, null, "alice")).status());
    when(target.online()).thenReturn(true);
    when(target.writePolicy()).thenReturn("DENY");
    assertEquals(409, assertThrows(OhpmException.class,
        () -> service.create("target", Source.DIRECTORY, null, "alice")).status());
    verifyNoInteractions(imports, sources, hosted);
  }

  @Test
  void validatesLabelsAndCoordinatesBeforeReadingSourceBytes() {
    for (String label : new String[] {null, "", " ", "a".repeat(1025)}) {
      assertEquals(400, assertThrows(OhpmException.class,
          () -> service.planDirectory("job", label, null, null, false, null, authorize)).status());
    }
    when(imports.findJob("job")).thenReturn(Optional.of(job(Source.REGISTRY, JobState.PLANNING)));
    for (String name : new String[] {null, "a".repeat(1025)}) {
      assertEquals(400, assertThrows(OhpmException.class,
          () -> service.planRegistry("job", name, "1.0.0", authorize)).status());
    }
    assertEquals(400, assertThrows(OhpmException.class,
        () -> service.planRegistry("job", "pkg", null, authorize)).status());
    verifyNoInteractions(sources, hosted, transactions, authorize);
  }

  @Test
  void malformedDirectoryInputCreatesAManualResultWithoutExposingTheException() throws Exception {
    when(sources.directory(null, null, false, null)).thenThrow(new IllegalArgumentException("private-path"));
    service.planDirectory("job", "source.har", null, null, false, null, authorize);
    verify(authorize).run();
    verify(imports).addPlan(eq("job"), matches("[a-f0-9]{64}"), isNull(), isNull(),
        eq(Map.of("sourceKey", "source.har")), eq(ItemState.MANUAL),
        eq("Source package metadata is invalid; create a corrected plan; create a new plan after correcting this input"));
    verifyNoInteractions(hosted);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void authorizationFailuresNeverBecomeImportResults(int status) throws Exception {
    var denial = new OhpmException(status, "Access revoked");
    when(sources.directory(null, null, false, null)).thenThrow(denial);
    assertSame(denial, assertThrows(OhpmException.class,
        () -> service.planDirectory("job", "source.har", null, null, false, null, authorize)));
    Job applying = job(Source.DIRECTORY, JobState.APPLYING);
    var item = new Item("job", "item", "pkg", "1.0.0", Map.of(), ItemState.PLANNED, "", Instant.EPOCH);
    when(imports.findJob("job")).thenReturn(Optional.of(applying));
    when(imports.findItem("job", "item")).thenReturn(Optional.of(item));
    assertSame(denial, assertThrows(OhpmException.class, () -> service.applyDirectory(
        "job", "item", null, null, false, null, "alice", "127.0.0.1", authorize)));
    verifyNoInteractions(transactions, hosted, authorize);
    verify(imports, never()).result(anyString(), anyString(), any(), anyString());
  }

  @Test
  void transientReadFailureIsRetryableButAConcurrentSuccessfulCheckpointWins() throws Exception {
    var applying = job(Source.DIRECTORY, JobState.APPLYING);
    var planned = new Item("job", "item", "pkg", "1.0.0", Map.of(), ItemState.PLANNED, "", Instant.EPOCH);
    var completed = new Item("job", "item", "pkg", "1.0.0", Map.of(), ItemState.IMPORTED, "", Instant.EPOCH);
    when(imports.findJob("job")).thenReturn(Optional.of(applying));
    when(imports.findItem("job", "item")).thenReturn(Optional.of(planned));
    when(imports.lockJob("job")).thenReturn(Optional.of(applying));
    when(imports.lockItem("job", "item")).thenReturn(Optional.of(completed));
    when(sources.directory(null, null, false, null)).thenThrow(new IOException("private-storage-endpoint"));
    assertEquals(completed, service.applyDirectory("job", "item", null, null, false, null,
        "alice", "127.0.0.1", authorize));
    verify(imports, never()).result(anyString(), anyString(), any(), anyString());
    when(imports.lockItem("job", "item")).thenReturn(Optional.of(planned));
    service.applyDirectory("job", "item", null, null, false, null, "alice", "127.0.0.1", authorize);
    verify(imports).result("job", "item", ItemState.FAILED,
        "Source or target content could not be read; retry with the same source");
  }

  private static Job job(Source source, JobState state) {
    return new Job("job", 1L, source, source == Source.REGISTRY ? 2L : null, "alice", state,
        Instant.EPOCH, Instant.EPOCH);
  }
}
