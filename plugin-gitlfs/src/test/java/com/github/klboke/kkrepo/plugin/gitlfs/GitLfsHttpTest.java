package com.github.klboke.kkrepo.plugin.gitlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsByteRange;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitLfsHttpTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "application/vnd.git-lfs+json|true",
      "application/vnd.git-lfs+json; charset=utf-8|true",
      "APPLICATION/VND.GIT-LFS+JSON|true",
      " application/vnd.git-lfs+json |true",
      "application/vnd.git-lfs+json;foo|true",
      "application/vnd.git-lfs+json; q=0.5|true",
      "application/vnd.git-lfs+json;charset=\"utf-8\"|true",
      "application/*+json|true",
      "application/*|true",
      "*/*|true",
      "*|true",
      "application/json|false",
      "application/vnd.git-lfs|false",
      "application/x+json|false",
      "application/*+xml|false",
      "text/*|false",
      "text/plain|false",
      "*/json|false",
      "broken;|false",
      "/json|false",
      "application/|false",
      "application/vnd.git-lfs+json, text/plain|false",
      "application/vnd.git-lfs+json; charset=bogus-charset|false",
      "application/vnd.git-lfs+json; q=2|false"})
  void matchesTheGitLfsMediaTypeLikeTheFormerHostParser(String contentType, boolean expected) {
    assertEquals(expected, GitLfsHttp.isLfsJson(contentType));
  }

  @Test
  void rejectsAMissingContentType() {
    assertEquals(false, GitLfsHttp.isLfsJson(null));
    assertEquals(false, GitLfsHttp.isLfsJson(""));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "bytes=1-3|1|3",
      "bytes=-4|2|5",
      "bytes=4-|4|5",
      "bytes=4-999|4|5",
      "bytes=+1-3|1|3",
      "bytes= 1-3 |1|3",
      "bytes=-999|0|5",
      "bytes=0-0|0|0"})
  void resolvesOneSatisfiableRange(String header, long start, long end) {
    GitLfsByteRange range = GitLfsHttp.singleRange(header, 6);
    assertEquals(start, range.start());
    assertEquals(end, range.end());
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "bytes=999-", "bytes=6-7", "bytes=3-1", "bytes=1--3", "bytes=", "bytes=  ", "Bytes=1-3",
      "bytes=1", "bytes=a-b", "bytes=-0", "bytes=--1", "bytes=0-1,2-3", "''"})
  void rejectsMalformedMultipleOrUnsatisfiableRanges(String header) {
    assertNull(GitLfsHttp.singleRange(header, 6));
  }

  @Test
  void emptyObjectsHaveNoSatisfiableRange() {
    assertNull(GitLfsHttp.singleRange("bytes=0-0", 0));
  }

  @Test
  void formatsAndParsesHttpDates() {
    Instant instant = Instant.parse("2026-01-01T00:00:00Z");
    assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", GitLfsHttp.httpDate(instant));
    assertEquals(instant, GitLfsHttp.parseHttpDate("Thu, 01 Jan 2026 00:00:00 GMT"));
    assertEquals(instant, GitLfsHttp.parseHttpDate("Thursday, 01-Jan-26 00:00:00 GMT"));
    assertEquals(instant, GitLfsHttp.parseHttpDate("Thu Jan 01 00:00:00 2026"));
    assertNull(GitLfsHttp.parseHttpDate("yesterday"));
  }

  @Test
  void conditionalRequestsPreferEntityTagsOverDates() {
    Instant updated = Instant.parse("2026-01-01T00:00:00.900Z");
    assertEquals(true, GitLfsHttp.notModified("\"sha\"", null, "sha", updated));
    assertEquals(true, GitLfsHttp.notModified("\"x\", W/\"sha\"", null, "sha", updated));
    assertEquals(true, GitLfsHttp.notModified("*", null, null, updated));
    assertEquals(false, GitLfsHttp.notModified("\"x\"", "Thu, 01 Jan 2026 00:00:00 GMT", "sha", updated));
    assertEquals(true, GitLfsHttp.notModified(null, "Thu, 01 Jan 2026 00:00:00 GMT", "sha", updated));
    assertEquals(false, GitLfsHttp.notModified(null, "Wed, 31 Dec 2025 23:59:59 GMT", "sha", updated));
    assertEquals(false, GitLfsHttp.notModified(null, "not a date", "sha", updated));
    assertEquals(false, GitLfsHttp.notModified(null, "Thu, 01 Jan 2026 00:00:00 GMT", "sha", null));
    assertEquals("\"sha\"", GitLfsHttp.entityTag("sha"));
    assertEquals("W/\"sha\"", GitLfsHttp.entityTag("W/\"sha\""));
  }
}
