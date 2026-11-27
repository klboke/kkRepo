package com.github.klboke.kkrepo.server.winget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WingetHostedServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WingetManifestParser parser = new WingetManifestParser();
  private final FakeWingetDao dao = new FakeWingetDao();
  private final WingetHostedService service = new WingetHostedService(dao, mapper);
  private final RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);

  @Test
  void publishesAndRejectsDuplicateVersion() {
    WingetManifest manifest = manifest("Contoso.Tool", "1.2.3");
    service.publish(runtime, manifest);
    assertEquals(1, dao.rows.size());

    WingetException conflict = assertThrows(WingetException.class,
        () -> service.publish(runtime, manifest));
    assertEquals(409, conflict.status());
  }

  @Test
  void replaceDropsVersionsMissingFromTheNewManifest() {
    service.publish(runtime, manifest("Contoso.Tool", "1.0.0", "1.1.0"));
    service.replace(runtime, manifest("Contoso.Tool", "2.0.0"));

    WingetManifest stored = service.manifest(runtime, "Contoso.Tool", null, null);
    assertEquals(1, stored.versions().size());
    assertEquals("2.0.0", stored.versions().getFirst().packageVersion());
  }

  @Test
  void filtersManifestByVersionAndChannel() {
    service.publish(runtime, manifest("Contoso.Tool", "1.0.0", "1.1.0"));
    WingetManifest filtered = service.manifest(runtime, "Contoso.Tool", "1.1.0", null);
    assertEquals(1, filtered.versions().size());
    assertEquals("1.1.0", filtered.versions().getFirst().packageVersion());
    assertThrows(WingetException.class,
        () -> service.manifest(runtime, "Contoso.Tool", "9.9.9", null));
  }

  @Test
  void searchesIndexedFieldsAndHonorsMaximumResults() throws Exception {
    service.publish(runtime, manifest("Contoso.Tool", "1.0.0"));
    service.publish(runtime, manifest("Fabrikam.Other", "2.0.0"));
    WingetSearchRequest request = WingetSearchRequest.parse(mapper.readTree("""
        {"Query":{"KeyWord":"contoso","MatchType":"SubstringIgnoreCase"},"MaximumResults":10}
        """));

    WingetSearchPage page = service.search(runtime, request, 0, 10);
    assertEquals(1, page.packages().size());
    assertEquals("Contoso.Tool", page.packages().getFirst().packageIdentifier());
  }

  @Test
  void deleteRemovesOneVersionOrTheWholePackage() {
    service.publish(runtime, manifest("Contoso.Tool", "1.0.0", "1.1.0"));
    service.delete(runtime, "Contoso.Tool", "1.0.0", null);
    assertEquals(1, service.manifest(runtime, "Contoso.Tool", null, null).versions().size());
    service.delete(runtime, "Contoso.Tool", null, null);
    assertThrows(WingetException.class,
        () -> service.manifest(runtime, "Contoso.Tool", null, null));
  }

  @Test
  void enforcesWritePolicyBeforeMutatingManifests() {
    RepositoryRuntime deny = runtime(RepositoryType.HOSTED, "DENY");
    WingetManifest manifest = manifest("Contoso.Tool", "1.0.0");
    assertEquals(403, assertThrows(WingetException.class,
        () -> service.publish(deny, manifest)).status());
    assertEquals(403, assertThrows(WingetException.class,
        () -> service.replace(deny, manifest)).status());
    assertEquals(403, assertThrows(WingetException.class,
        () -> service.delete(deny, "Contoso.Tool", null, null)).status());

    RepositoryRuntime once = runtime(RepositoryType.HOSTED, "ALLOW_ONCE");
    service.publish(once, manifest);
    assertEquals(403, assertThrows(WingetException.class,
        () -> service.replace(once, manifest)).status());
    assertEquals(1, service.delete(once, "Contoso.Tool", null, null));
  }

  @Test
  void searchCursorAdvancesOnlyPastConsumedIdentifiers() throws Exception {
    for (int i = 0; i < 25; i++) {
      service.publish(runtime, manifest("Contoso.Tool" + i, "1.0.0"));
    }
    WingetSearchRequest request = WingetSearchRequest.parse(
        mapper.readTree("{\"MaximumResults\":10}"));

    WingetSearchPage first = service.search(runtime, request, 0, 10);
    assertEquals(10, first.packages().size());
    assertEquals(10, first.nextOffset());

    WingetSearchPage second = service.search(runtime, request, first.nextOffset(), 10);
    assertEquals(10, second.packages().size());
    assertEquals(20, second.nextOffset());

    WingetSearchPage third = service.search(runtime, request, second.nextOffset(), 10);
    assertEquals(5, third.packages().size());
    assertNull(third.nextOffset());
  }

  @Test
  void rejectsPublishOnNonHostedRepositories() {
    WingetService facade = new WingetService(service,
        new WingetProxyService(dao, service, null, mapper));
    WingetException error = assertThrows(WingetException.class,
        () -> facade.publish(runtime(RepositoryType.PROXY), manifest("Contoso.Tool", "1.0.0")));
    assertEquals(405, error.status());
  }

  private WingetManifest manifest(String identifier, String... versions) {
    String publisher = identifier.substring(0, identifier.indexOf('.'));
    StringBuilder body = new StringBuilder("{\"PackageIdentifier\":\"" + identifier + "\",\"Versions\":[");
    for (int i = 0; i < versions.length; i++) {
      if (i > 0) body.append(',');
      body.append("""
          {"PackageIdentifier":"%s","PackageVersion":"%s",
           "DefaultLocale":{"PackageLocale":"en-US","Publisher":"%s","PackageName":"%s Tool",
             "Moniker":"%s","Tags":["cli"],"Commands":["%s.exe"]},
           "Installers":[{"InstallerType":"msi","PackageFamilyName":"%s.Tool_8wekyb3d8bbwe",
             "ProductCode":"{PRODUCT-CODE}"}]}
          """.formatted(identifier, versions[i], publisher, publisher,
              publisher.toLowerCase(), publisher.toLowerCase(), publisher));
    }
    body.append("]}");
    try {
      return parser.parse(mapper.readTree(body.toString()));
    } catch (Exception invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return runtime(type, type == RepositoryType.HOSTED ? "ALLOW" : null);
  }

  private static RepositoryRuntime runtime(RepositoryType type, String writePolicy) {
    return new RepositoryRuntime(
        1L, "winget", RepositoryFormat.WINGET, type, "winget-" + type.name().toLowerCase(),
        true, 1L, writePolicy,
        null, null, true, null, 60, 30, true, null, List.of());
  }
}
