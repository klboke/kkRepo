package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowseDownloadUrlsTest {
  @Test
  void composerGroupUsesSameNexusCompatibleDistPathAsMember() {
    RepositoryRecord group = repository(1L, "composer-group", RepositoryType.GROUP);
    String path = "company/example/1.0.0/company-example-1.0.0.zip";

    String url = BrowseDownloadUrls.asset(group, path);

    assertEquals("/repository/composer-group/" + path, url);
  }

  private static RepositoryRecord repository(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.COMPOSER, type, "composer-" + type.name().toLowerCase(),
        true, 1L, null, null, null, null, null, true, Map.of());
  }
}
