package com.github.klboke.kkrepo.protocol.maven.path;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MavenSnapshotBuildTest {
  @Test void groupsArtifactsSignaturesAndChecksumsByTimestampAndNumericBuild() {
    String root = "com/example/demo/1.0-SNAPSHOT/demo-1.0-20260101.120000-10";
    for (String suffix : new String[] {".jar", ".pom", "-sources.jar", ".jar.asc", ".jar.asc.sha256", "-sources.jar.sha1"}) {
      var parsed = MavenSnapshotBuild.parse(root + suffix).orElseThrow();
      assertEquals("com.example", parsed.groupId());
      assertEquals("demo", parsed.artifactId());
      assertEquals("1.0-SNAPSHOT", parsed.baseVersion());
      assertEquals("1.0-20260101.120000-10", parsed.version());
      assertEquals(10, parsed.buildNumber());
      assertEquals("com/example/demo/1.0-SNAPSHOT", parsed.familyPath());
      assertEquals(!suffix.contains("asc") && !suffix.contains("sha"), parsed.primary());
    }
    assertEquals("7:sources:jar", MavenSnapshotBuild.parse(root + "-sources.jar.asc.sha256").orElseThrow().variant());
    assertEquals("0::pom", MavenSnapshotBuild.parse(root + ".pom").orElseThrow().variant());
  }

  @Test void excludesReleasesNonUniqueSnapshotsMetadataAndUnrecognizableFiles() {
    for (String path : new String[] {"com/example/demo/1.0/demo-1.0.jar",
        "com/example/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar",
        "com/example/demo/1.0-SNAPSHOT/maven-metadata.xml",
        "com/example/demo/1.0-SNAPSHOT/maven-metadata.xml.sha1",
        "com/example/demo/1.0-SNAPSHOT/arbitrary.txt",
        "com/example/demo/1.0-SNAPSHOT/demo-1.0-20260101.120000-999999999999999.jar", ""}) {
      assertTrue(MavenSnapshotBuild.parse(path).isEmpty(), path);
    }
  }
}
