package com.github.klboke.kkrepo.protocol.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwiftIdentityAndPathTest {
  private final SwiftPathParser parser = new SwiftPathParser();

  @Test
  void validatesOfficialCaseInsensitiveIdentityRulesWithoutChangingDisplayCase() {
    SwiftPackageIdentity identity = new SwiftPackageIdentity("Mona-Org", "Linked_List-2");

    assertEquals("Mona-Org.Linked_List-2", identity.value());
    assertEquals("mona-org.linked_list-2", identity.key());
    assertEquals(identity, SwiftPackageIdentity.parse(identity.value()));
    assertTrue(SwiftScope.isValid("a".repeat(39)));
    assertTrue(SwiftPackageName.isValid("a".repeat(100)));
  }

  @Test
  void rejectsInvalidIdentityBoundariesAndSeparators() {
    for (String scope : new String[] {"", "-mona", "mona-", "mona--org", "a".repeat(40)}) {
      assertThrows(IllegalArgumentException.class, () -> SwiftScope.require(scope), scope);
    }
    for (String name : new String[] {
        "", "_LinkedList", "LinkedList-", "Linked--List", "Linked-_List", "a".repeat(101)
    }) {
      assertThrows(IllegalArgumentException.class, () -> SwiftPackageName.require(name), name);
    }
    assertThrows(IllegalArgumentException.class, () -> SwiftPackageIdentity.parse("mona"));
    assertThrows(IllegalArgumentException.class, () -> SwiftPackageIdentity.parse("a.b.c"));
  }

  @Test
  void parsesAllRegistryV1ResourcePaths() {
    assertEquals(SwiftPath.Kind.ROOT, parser.parse("").kind());
    assertEquals(SwiftPath.Kind.LOGIN, parser.parse("/login").kind());
    assertEquals(SwiftPath.Kind.IDENTIFIERS, parser.parse("identifiers").kind());

    SwiftPath releases = parser.parse("/m%6Fna/LinkedList");
    assertEquals(SwiftPath.Kind.RELEASE_LIST, releases.kind());
    assertEquals("mona.LinkedList", releases.identity());
    assertEquals("mona.linkedlist", releases.identityKey());
    assertNull(releases.version());

    SwiftPath releasesAlias = parser.parse("mona/LinkedList.json");
    assertEquals(SwiftPath.Kind.RELEASE_LIST, releasesAlias.kind());
    assertEquals("LinkedList", releasesAlias.name());

    SwiftPath metadata = parser.parse("mona/LinkedList/1.2.3-beta.1+build.7");
    assertEquals(SwiftPath.Kind.RELEASE_METADATA, metadata.kind());
    assertEquals("1.2.3-beta.1+build.7", metadata.version());
    assertTrue(metadata.hasReleaseCoordinate());

    SwiftPath metadataAlias = parser.parse("mona/LinkedList/1.2.3.json");
    assertEquals(SwiftPath.Kind.RELEASE_METADATA, metadataAlias.kind());
    assertEquals("1.2.3", metadataAlias.version());

    assertEquals(
        SwiftPath.Kind.MANIFEST,
        parser.parse("mona/LinkedList/1.2.3/Package.swift").kind());
    assertEquals(
        SwiftPath.Kind.SOURCE_ARCHIVE,
        parser.parse("mona/LinkedList/1.2.3.zip").kind());
  }

  @Test
  void rejectsAmbiguousTraversalAndDoubleEncodedPaths() {
    for (String path : new String[] {
        "//mona/LinkedList",
        "mona//LinkedList",
        "mona/LinkedList/",
        "mona/../LinkedList",
        "%2e%2e/LinkedList",
        "mona%2fother/LinkedList",
        "mona%5cother/LinkedList",
        "mona\\other/LinkedList",
        "%252e%252e/LinkedList",
        "mona/%ZZ",
        "mona/LinkedList.JSON",
        "mona/LinkedList.json.json",
        "mona/LinkedList/1.0.0.JSON",
        "mona/LinkedList/1.0.0.json.json",
        "mona/LinkedList?version=1.0.0",
        "mona/LinkedList/v1.0.0",
        "mona/LinkedList/1.0.0/Package@swift-5.9.swift"
    }) {
      assertEquals(SwiftPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
  }

  @Test
  void validatesEndpointSpecificQueryParametersExactlyOnce() {
    SwiftRequestTarget manifest = parser.parse(
        "mona/LinkedList/1.0.0/Package.swift", "swift-version=5.9");
    assertEquals("5.9", manifest.swiftVersion());

    SwiftRequestTarget identifiers = parser.parse(
        "identifiers", "url=https%3A%2F%2Fgithub.com%2Fmona%2FLinkedList.git");
    assertEquals("https://github.com/mona/LinkedList.git", identifiers.repositoryUrl());

    assertThrows(IllegalArgumentException.class, () -> parser.parse("identifiers", null));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("identifiers", "url=a&url=b"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("mona/LinkedList/1.0.0/Package.swift", "swift-version=5.9.1.2"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("mona/LinkedList", "swift-version=5.9"));
  }
}
