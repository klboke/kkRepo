package com.github.klboke.kkrepo.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UriPathDecoderTest {
  @Test
  void decodesOnceAndPreservesPlusAndPathStructure() {
    assertEquals("/github.com/!azure/sdk/@v/v2.0.0+incompatible.info/",
        UriPathDecoder.decodePath("/github.com/%21azure/sdk/@v/v2.0.0%2Bincompatible.info/"));
    assertEquals("a+b", UriPathDecoder.decodeSegment("a+b"));
    assertEquals("%21azure", UriPathDecoder.decodeSegment("%2521azure"));
    assertEquals("a//b/", UriPathDecoder.decodePath("a//b/"));
    assertEquals("", UriPathDecoder.decodePath(""));
  }

  @Test
  void rejectsMalformedEscapesAndUtf8WithoutEchoingCredentials() {
    for (String value : new String[] {"%", "%2", "%GG", "%C3%28", "%80", "%C0%AF",
        "%ED%A0%80", "%F4%90%80%80", "secret%C3x%A9"}) {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> UriPathDecoder.decodeComponent(value), value);
      assertEquals("Invalid URI percent-encoding or UTF-8", error.getMessage());
    }
  }

  @Test
  void preservesValidUnicodeIncludingTheReplacementCharacter() {
    assertEquals("模型+café", UriPathDecoder.decodeSegment("%E6%A8%A1%E5%9E%8B+caf%C3%A9"));
    assertEquals("模型+é", UriPathDecoder.decodeSegment("模型+%C3%A9"));
    assertEquals("\uFFFD", UriPathDecoder.decodeSegment("%EF%BF%BD"));
  }

  @Test
  void rejectsUnsafeDecodedSegments() {
    for (String value : new String[] {"%2f", "%2F", "%5c", "a\\b", ".", "..",
        "%2e%2E", "%00", "%1f", "%7f"}) {
      assertThrows(IllegalArgumentException.class, () -> UriPathDecoder.decodeSegment(value), value);
      assertThrows(IllegalArgumentException.class, () -> UriPathDecoder.decodePath("repo/" + value), value);
    }
  }

  @Test
  void opaqueComponentsLeaveProtocolSpecificSeparatorsToTheCaller() {
    assertEquals("refs/pr/3", UriPathDecoder.decodeComponent("refs%2Fpr%2F3"));
    assertEquals("dTo/+", UriPathDecoder.decodeComponent("dTo%2F%2B"));
    assertEquals("https://example.com/repo", UriPathDecoder.decodeComponent("https%3A%2F%2Fexample.com%2Frepo"));
  }
}
