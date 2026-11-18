package com.github.klboke.kkrepo.protocol.maven.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenMetadataVersionFilterTest {
  private static final String METADATA = """
      <?xml version="1.0" encoding="UTF-8"?>
      <metadata>
        <groupId>com.acme</groupId>
        <artifactId>demo</artifactId>
        <versioning>
          <latest>1.2</latest>
          <release>1.2</release>
          <versions>
            <version>1.0</version>
            <version>1.1</version>
            <version>1.2</version>
            <version>2.0-SNAPSHOT</version>
          </versions>
          <lastUpdated>20260110120000</lastUpdated>
        </versioning>
      </metadata>
      """;

  @Test
  void removesVersionsAndRecomputesLatestAndRelease() throws Exception {
    var result = MavenMetadataVersionFilter.retain(
        bytes(METADATA), version -> !version.equals("1.2")).orElseThrow();

    assertEquals(List.of("1.0", "1.1", "2.0-SNAPSHOT"), result.kept());
    assertEquals(List.of("1.2"), result.removed());
    String xml = new String(result.xml(), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<latest>2.0-SNAPSHOT</latest>"), xml);
    assertTrue(xml.contains("<release>1.1</release>"), xml);
    assertTrue(!xml.contains("<version>1.2</version>"), xml);
    assertTrue(xml.contains("<lastUpdated>20260110120000</lastUpdated>"), xml);
  }

  @Test
  void keepsUpstreamLatestAndReleaseWhenTheyAreStillAllowed() throws Exception {
    var result = MavenMetadataVersionFilter.retain(
        bytes(METADATA), version -> !version.equals("1.0")).orElseThrow();

    String xml = new String(result.xml(), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<latest>1.2</latest>"), xml);
    assertTrue(xml.contains("<release>1.2</release>"), xml);
  }

  @Test
  void dropsEverythingWhenNoVersionIsAllowed() throws Exception {
    var result = MavenMetadataVersionFilter.retain(bytes(METADATA), version -> false).orElseThrow();

    assertTrue(result.kept().isEmpty());
    assertEquals(4, result.removed().size());
  }

  @Test
  void omittedLastUpdatedStaysOmittedSoRepeatedFiltersProduceIdenticalBytes() throws Exception {
    String withoutLastUpdated = METADATA.replace("<lastUpdated>20260110120000</lastUpdated>", "");

    var first = MavenMetadataVersionFilter.retain(
        bytes(withoutLastUpdated), version -> !version.equals("1.2")).orElseThrow();
    Thread.sleep(1100);
    var second = MavenMetadataVersionFilter.retain(
        bytes(withoutLastUpdated), version -> !version.equals("1.2")).orElseThrow();

    // A checksum served a moment after the metadata must hash the same bytes.
    assertTrue(java.util.Arrays.equals(first.xml(), second.xml()));
    assertTrue(!new String(first.xml(), StandardCharsets.UTF_8).contains("lastUpdated"));
  }

  @Test
  void presentLastUpdatedIsPreserved() throws Exception {
    var result = MavenMetadataVersionFilter.retain(
        bytes(METADATA), version -> !version.equals("1.2")).orElseThrow();

    assertTrue(new String(result.xml(), StandardCharsets.UTF_8)
        .contains("<lastUpdated>20260110120000</lastUpdated>"));
  }

  @Test
  void documentsWithoutVersionsAreNotApplicable() throws Exception {
    String baseVersion = """
        <metadata><groupId>g</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version>
        <versioning><lastUpdated>20260110120000</lastUpdated></versioning></metadata>
        """;

    assertTrue(MavenMetadataVersionFilter.retain(bytes(baseVersion), version -> false).isEmpty());
  }

  private static byte[] bytes(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }
}
