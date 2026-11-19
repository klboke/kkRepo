package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.WingetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the WinGet metadata contract against both real backends. */
public final class WingetDaoContract {
  private WingetDaoContract() {}

  public static void verify(JdbcTemplate jdbc, PersistenceStores stores) {
    long store = stores.blobStores().insert(new BlobStoreRecord(
        null, "winget-contract", "s3", null, null, "bucket", "", Map.of()));
    long repository = stores.repositories().insert(new RepositoryRecord(
        null, "winget-contract", RepositoryFormat.WINGET, RepositoryType.HOSTED,
        "winget-hosted", true, store, null, null, null, null, "ALLOW", false, Map.of()));
    WingetDao dao = stores.winget();

    dao.insert(row(repository, "Contoso.Tool", "1.2.3", "Contoso Tool"));
    dao.insert(row(repository, "Contoso.Tool", "2.0.0", "Contoso Tool"));
    dao.insert(row(repository, "Fabrikam.Other", "1.0.0", "Other Tool"));

    assertEquals(1, dao.listByPackage(repository, "Contoso.Tool").stream()
        .filter(row -> row.packageVersion().equals("2.0.0")).count());
    assertTrue(dao.find(repository, "Contoso.Tool", "1.2.3", "").isPresent());
    assertTrue(dao.find(repository, "Contoso.Tool", "1.2.3", "beta").isEmpty());
    assertEquals(2, dao.listSummaries(repository, List.of("Contoso.Tool")).size());
    assertEquals(List.of("Contoso.Tool"),
        dao.searchPackageIdentifiers(repository, List.of("contoso"), 10, 0));
    assertEquals(List.of("Fabrikam.Other"),
        dao.searchPackageIdentifiers(repository, List.of("fabrikam"), 10, 0));
    assertEquals(3, dao.countVersions(repository));

    assertThrows(DataIntegrityViolationException.class,
        () -> dao.insert(row(repository, "Contoso.Tool", "1.2.3", "Contoso Tool")));
    assertEquals(1, dao.deleteVersion(repository, "Contoso.Tool", "1.2.3", ""));
    assertEquals(1, dao.deletePackage(repository, "Contoso.Tool"));
    assertEquals(1, dao.countVersions(repository));
    assertEquals(1, dao.deleteByRepository(repository));
    assertEquals(0, dao.countVersions(repository));
  }

  private static WingetDao.ManifestRecord row(
      long repository, String identifier, String version, String name) {
    String publisher = identifier.substring(0, identifier.indexOf('.'));
    return new WingetDao.ManifestRecord(
        0L, repository, identifier, version, "", "en-US", name, publisher, "",
        (identifier + " " + name + " " + publisher).toLowerCase(), "{}",
        "{\"PackageVersion\":\"" + version + "\"}", "a".repeat(64), Instant.now());
  }
}
