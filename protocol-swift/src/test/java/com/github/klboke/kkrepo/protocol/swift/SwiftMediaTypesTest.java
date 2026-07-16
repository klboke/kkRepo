package com.github.klboke.kkrepo.protocol.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwiftMediaTypesTest {
  @Test
  void negotiatesVersionedVersionlessAndConcreteResponseTypes() {
    assertAccepted(null, SwiftMediaTypes.Resource.JSON, SwiftMediaTypes.JSON);
    assertAccepted("*/*", SwiftMediaTypes.Resource.ARCHIVE, SwiftMediaTypes.ARCHIVE);
    assertAccepted(SwiftMediaTypes.VENDOR_JSON, SwiftMediaTypes.Resource.JSON, SwiftMediaTypes.JSON);
    assertAccepted("application/vnd.swift.registry.v1", SwiftMediaTypes.Resource.JSON,
        SwiftMediaTypes.JSON);
    assertAccepted("application/vnd.swift.registry+swift", SwiftMediaTypes.Resource.MANIFEST,
        SwiftMediaTypes.MANIFEST);
    assertAccepted("application/zip", SwiftMediaTypes.Resource.ARCHIVE, SwiftMediaTypes.ARCHIVE);
    assertAccepted(
        "application/vnd.swift.registry.v2+zip;q=0.4, " + SwiftMediaTypes.VENDOR_ZIP,
        SwiftMediaTypes.Resource.ARCHIVE,
        SwiftMediaTypes.ARCHIVE);
  }

  @Test
  void distinguishesInvalidVersionsFromValidButUnsupportedVersions() {
    SwiftMediaTypes.Negotiation invalid = SwiftMediaTypes.negotiate(
        "application/vnd.swift.registry.vnext+json", SwiftMediaTypes.Resource.JSON);
    assertFalse(invalid.accepted());
    assertEquals(SwiftMediaTypes.Outcome.INVALID_API_VERSION, invalid.outcome());
    assertEquals(400, invalid.httpStatus());
    assertEquals(SwiftMediaTypes.PROBLEM_JSON, invalid.responseContentType());

    assertEquals(
        SwiftMediaTypes.Outcome.INVALID_API_VERSION,
        SwiftMediaTypes.negotiate(
            "application/vnd.swift.registry.v01+json", SwiftMediaTypes.Resource.JSON).outcome());

    SwiftMediaTypes.Negotiation unsupported = SwiftMediaTypes.negotiate(
        "application/vnd.swift.registry.v2+json", SwiftMediaTypes.Resource.JSON);
    assertEquals(SwiftMediaTypes.Outcome.UNSUPPORTED_API_VERSION, unsupported.outcome());
    assertEquals(415, unsupported.httpStatus());

    SwiftMediaTypes.Negotiation wrongRepresentation = SwiftMediaTypes.negotiate(
        SwiftMediaTypes.VENDOR_ZIP, SwiftMediaTypes.Resource.MANIFEST);
    assertEquals(SwiftMediaTypes.Outcome.UNSUPPORTED_MEDIA_TYPE, wrongRepresentation.outcome());
    assertEquals(415, wrongRepresentation.httpStatus());
  }

  @Test
  void ignoresExplicitlyUnacceptableRanges() {
    SwiftMediaTypes.Negotiation result = SwiftMediaTypes.negotiate(
        SwiftMediaTypes.VENDOR_JSON + ";q=0", SwiftMediaTypes.Resource.JSON);
    assertEquals(SwiftMediaTypes.Outcome.UNSUPPORTED_MEDIA_TYPE, result.outcome());
  }

  private static void assertAccepted(
      String accept, SwiftMediaTypes.Resource resource, String contentType) {
    SwiftMediaTypes.Negotiation result = SwiftMediaTypes.negotiate(accept, resource);
    assertTrue(result.accepted());
    assertEquals(1, result.apiVersion());
    assertEquals(contentType, result.responseContentType());
  }
}
