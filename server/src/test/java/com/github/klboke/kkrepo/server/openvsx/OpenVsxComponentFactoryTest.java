package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenVsxComponentFactoryTest {
  private final OpenVsxComponentFactory factory = new OpenVsxComponentFactory();
  private final RepositoryRuntime runtime = runtime();

  @Test
  void platformNeutralComponentDoesNotStoreNullAttributes() {
    OpenVsxVsixInspector.Inspected inspected = inspected(
        null, "acme.demo-1.0.0.vsix");

    ComponentRecord component = factory.component(
        runtime, inspected, "acme/demo/1.0.0/acme.demo-1.0.0.vsix",
        "sha1", "sha256", 10, Instant.EPOCH);

    assertFalse(component.attributes().containsKey("openvsxTargetPlatform"));
    assertEquals("1.0.0", component.version());
  }

  @Test
  void platformVariantsUseDistinctStoragePaths() {
    OpenVsxVsixInspector.Inspected linux = inspected(
        "linux-x64", "acme.demo-1.0.0-linux-x64.vsix");
    OpenVsxVsixInspector.Inspected darwin = inspected(
        "darwin-x64", "acme.demo-1.0.0-darwin-x64.vsix");

    assertNotEquals(OpenVsxComponentFactory.storagePath(linux),
        OpenVsxComponentFactory.storagePath(darwin));
    assertTrue(OpenVsxComponentFactory.storagePath(linux)
        .endsWith("/acme.demo-1.0.0-linux-x64.vsix"));
  }

  private static OpenVsxVsixInspector.Inspected inspected(String platform, String fileName) {
    return new OpenVsxVsixInspector.Inspected(
        "acme", "demo", "1.0.0", platform, false, fileName, "Demo", "desc", Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "openvsx-hosted", RepositoryFormat.OPENVSX, RepositoryType.HOSTED,
        "openvsx-hosted", true, 1L, "ALLOW", null, null, true, null, null, null, null, null,
        List.of());
  }
}
