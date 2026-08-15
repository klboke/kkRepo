package com.github.klboke.kkrepo.protocol.alpine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parser and deterministic renderer for the textual APKINDEX payload. */
public final class AlpineIndex {
  private static final int MAX_BYTES = 512 * 1024 * 1024;
  private static final int MAX_RECORDS = 2_000_000;

  private AlpineIndex() {
  }

  public static List<AlpineIndexRecord> parse(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("APKINDEX exceeds safety limits");
    }
    final String content;
    try {
      content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IllegalArgumentException("APKINDEX is not valid UTF-8", error);
    }
    if (content.indexOf('\0') >= 0 || content.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("APKINDEX contains unsafe characters");
    }
    ArrayList<AlpineIndexRecord> records = new ArrayList<>();
    ArrayList<AlpineIndexRecord.Field> fields = new ArrayList<>();
    for (String line : content.split("\\n", -1)) {
      if (line.isEmpty()) {
        if (!fields.isEmpty()) {
          records.add(new AlpineIndexRecord(fields));
          fields = new ArrayList<>();
          if (records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("APKINDEX has too many records");
          }
        }
        continue;
      }
      if (line.length() < 2 || line.charAt(1) != ':') {
        throw new IllegalArgumentException("Malformed APKINDEX line");
      }
      fields.add(new AlpineIndexRecord.Field(line.charAt(0), line.substring(2)));
    }
    if (!fields.isEmpty()) records.add(new AlpineIndexRecord(fields));
    return List.copyOf(records);
  }

  public static byte[] render(List<AlpineIndexRecord> records) {
    StringBuilder output = new StringBuilder();
    boolean first = true;
    for (AlpineIndexRecord record : records == null ? List.<AlpineIndexRecord>of() : records) {
      if (!first) output.append('\n');
      output.append(record.render());
      first = false;
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }
}
