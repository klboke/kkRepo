package com.github.klboke.kkrepo.protocol.huggingface;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HuggingFaceContentIdentityTest {
  private static final String SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @Test
  void validatesLinkedContentHashAndSizeButNotXetProvenance() {
    var identity = new HuggingFaceContentIdentity(null, "sha256:" + SHA256, 4L, "xet-id");
    assertDoesNotThrow(() -> identity.verify(SHA256, 4));
    assertThrows(IllegalArgumentException.class, () -> identity.verify(SHA256, 5));
    assertThrows(IllegalArgumentException.class, () -> identity.verify("b".repeat(64), 4));
  }

  @Test
  void classifiesRegularGitAndLfsLinkedEtags() {
    String gitOid = "22ffb3454131a71d4144340befb799c66ad0c670";
    var regular = HuggingFaceContentIdentity.fromResolveHeaders(
        null, null, 548L, null, "\"" + gitOid + "\"", null);
    assertEquals(gitOid, regular.gitOid());
    assertNull(regular.linkedSha256());

    var lfs = HuggingFaceContentIdentity.fromResolveHeaders(
        null, null, 4L, "xet-id", "sha256:" + SHA256, null);
    assertNull(lfs.gitOid());
    assertEquals(SHA256, lfs.linkedSha256());
  }

  @Test
  void rejectsAChangedKnownIdentity() {
    assertThrows(IllegalArgumentException.class, () ->
        HuggingFaceContentIdentity.fromResolveHeaders(
            "a".repeat(40), null, 4L, null, "b".repeat(40), null));
    assertThrows(IllegalArgumentException.class, () ->
        HuggingFaceContentIdentity.fromResolveHeaders(
            null, SHA256, 4L, null, "b".repeat(64), null));
  }
}
