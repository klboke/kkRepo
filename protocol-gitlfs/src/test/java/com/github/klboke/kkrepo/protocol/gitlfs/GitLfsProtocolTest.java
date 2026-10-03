package com.github.klboke.kkrepo.protocol.gitlfs;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitLfsProtocolTest {
  private static final String OID = "a".repeat(64);
  private GitLfsProtocol.Batch parse(String json) throws Exception {
    return GitLfsProtocol.parseBatch(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE);
  }
  private String batch(String fields) {
    return "{\"operation\":\"upload\",\"objects\":[{\"oid\":\"" + OID + "\",\"size\":0}]" + fields + "}";
  }
  @Test void supportsDefaultsNullRefZeroAndLongSize() throws Exception {
    assertTrue(parse(batch("")).upload());
    assertEquals(0, parse(batch(",\"ref\":null")).objects().getFirst().size());
    assertTrue(parse(batch(",\"transfers\":[\"tus\",\"basic\"],\"hash_algo\":\"sha256\"")).upload());
    assertEquals(Long.MAX_VALUE, parse(batch("").replace("\"size\":0", "\"size\":9223372036854775807")).objects().getFirst().size());
  }
  @Test void rejectsNegotiationAndMalformedRequests() {
    assertEquals(422, assertThrows(GitLfsException.class, () -> parse(batch(",\"transfers\":[\"tus\"]"))).status());
    assertEquals(409, assertThrows(GitLfsException.class, () -> parse(batch(",\"hash_algo\":\"sha512\""))).status());
    for (String size : new String[] {"-1", "1.5", "9223372036854775808", "\"0\"", "null"}) {
      assertEquals(422, assertThrows(GitLfsException.class,
          () -> parse(batch("").replace("\"size\":0", "\"size\":" + size))).status());
    }
    assertThrows(GitLfsException.class, () -> parse(batch("") + "{}"));
    assertEquals(422, assertThrows(GitLfsException.class, () -> parse(batch(",\"transfers\":\"basic\""))).status());
    assertEquals(422, assertThrows(GitLfsException.class, () -> parse("{broken")).status());
    assertThrows(GitLfsException.class, () -> parse(batch("").replace(OID, OID.toUpperCase())));
    assertThrows(GitLfsException.class, () -> GitLfsProtocol.objectOid("../" + OID));
    assertThrows(GitLfsException.class, () -> GitLfsProtocol.objectOid(OID + "/"));
  }
  @Test void boundsBodyAndPreservesMixedPerObjectErrors() throws Exception {
    assertEquals(413, assertThrows(GitLfsException.class, () -> parse(" ".repeat(GitLfsProtocol.MAX_BODY_BYTES + 1))).status());
    String object = "{\"oid\":\"" + OID + "\",\"size\":0}";
    var result = parse("{\"operation\":\"download\",\"objects\":[" + object + ",{\"oid\":\"bad\",\"size\":-1}]}");
    assertTrue(result.objects().get(0).valid());
    assertFalse(result.objects().get(1).valid());
    assertEquals(413, assertThrows(GitLfsException.class, () -> parse(
        "{\"operation\":\"upload\",\"objects\":[" + (object + ",").repeat(1000) + object + "]}")).status());
  }
}
