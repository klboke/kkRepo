package com.github.klboke.kkrepo.server.yum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Minimal RPM header shared by parser and database lifecycle tests. */
public final class YumTestFixtures {
  private YumTestFixtures() {}

  public static byte[] rpmFixture() {
    try {
      ByteArrayOutputStream rpm = new ByteArrayOutputStream();
      putInt(rpm, 0xedabeedb);
      rpm.write(new byte[92]);
      rpm.write(header(List.of()));
      while (rpm.size() % 8 != 0) {
        rpm.write(0);
      }
      rpm.write(header(List.of(
          Entry.string(1000, "demo"),
          Entry.string(1001, "1.2.3"),
          Entry.string(1002, "4"),
          Entry.int32(1003, 7),
          Entry.i18n(1004, "Demo package"),
          Entry.i18n(1005, "Demo package description"),
          Entry.int32(1006, 1710000000),
          Entry.string(1007, "builder"),
          Entry.int32(1009, 4096),
          Entry.string(1011, "Example"),
          Entry.string(1014, "MIT"),
          Entry.string(1015, "packager"),
          Entry.i18n(1016, "Development/Tools"),
          Entry.string(1020, "https://example.invalid/demo"),
          Entry.string(1022, "x86_64"),
          Entry.int16Array(1030, List.of(0100755, 0040755)),
          Entry.stringArray(1035, List.of("digest-bin", "digest-etc")),
          Entry.int32Array(1037, List.of(0, 0)),
          Entry.string(1044, "demo-1.2.3-4.src.rpm"),
          Entry.int32(1046, 1024),
          Entry.stringArray(1047, List.of("demo")),
          Entry.int32Array(1112, List.of(8)),
          Entry.stringArray(1113, List.of("7:1.2.3-4")),
          Entry.stringArray(1049, List.of("rpmlib(CompressedFileNames)", "/usr/bin/demo", "/bin/sh",
              "pre-required")),
          Entry.int32Array(1048, List.of(8, 0, 0, 520)),
          Entry.stringArray(1050, List.of("0:0-0", "", "", "1:2.0-3")),
          Entry.stringArray(1054, List.of("old-demo")),
          Entry.int32Array(1053, List.of(10)),
          Entry.stringArray(1055, List.of("1:1.0-1")),
          Entry.stringArray(1090, List.of("older-demo")),
          Entry.int32Array(1114, List.of(10)),
          Entry.stringArray(1115, List.of("1:0.9-1")),
          Entry.int32Array(1116, List.of(0, 1)),
          Entry.stringArray(1117, List.of("demo", "demo")),
          Entry.stringArray(1118, List.of("/usr/bin/", "/etc/")),
          Entry.int64(271, 2048),
          Entry.int64(5009, 4096),
          Entry.int32(5011, 8))));
      return rpm.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] header(List<Entry> entries) throws IOException {
    List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt(Entry::tag)).toList();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    List<Index> indexes = new ArrayList<>();
    for (Entry entry : sorted) {
      indexes.add(new Index(entry.tag(), entry.type(), data.size(), entry.count()));
      data.write(entry.value());
    }
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    putInt(header, 0x8eade801);
    putInt(header, 0);
    putInt(header, sorted.size());
    putInt(header, data.size());
    for (Index index : indexes) {
      putInt(header, index.tag());
      putInt(header, index.type());
      putInt(header, index.offset());
      putInt(header, index.count());
    }
    header.write(data.toByteArray());
    return header.toByteArray();
  }

  private static void putInt(ByteArrayOutputStream out, int value) throws IOException {
    out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array());
  }

  private record Index(int tag, int type, int offset, int count) {
  }

  private record Entry(int tag, int type, byte[] value, int count) {
    static Entry string(int tag, String value) {
      return new Entry(tag, 6, stringBytes(value), 1);
    }

    static Entry i18n(int tag, String value) {
      return new Entry(tag, 9, stringBytes(value), 1);
    }

    static Entry stringArray(int tag, List<String> values) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String value : values) {
          out.write(stringBytes(value));
        }
        return new Entry(tag, 8, out.toByteArray(), values.size());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    static Entry int16Array(int tag, List<Integer> values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.size() * 2).order(ByteOrder.BIG_ENDIAN);
      values.forEach(value -> buffer.putShort((short) value.intValue()));
      return new Entry(tag, 3, buffer.array(), values.size());
    }

    static Entry int32(int tag, int value) {
      return int32Array(tag, List.of(value));
    }

    static Entry int32Array(int tag, List<Integer> values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.BIG_ENDIAN);
      values.forEach(buffer::putInt);
      return new Entry(tag, 4, buffer.array(), values.size());
    }

    static Entry int64(int tag, long value) {
      ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value);
      return new Entry(tag, 5, buffer.array(), 1);
    }

    private static byte[] stringBytes(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] result = new byte[bytes.length + 1];
      System.arraycopy(bytes, 0, result, 0, bytes.length);
      return result;
    }
  }

}
