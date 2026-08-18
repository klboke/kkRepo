package com.github.klboke.kkrepo.scanner;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded static model inspection. The implementation never imports or deserializes model code. */
final class HuggingFaceModelInspector {
  private static final long MAX_SAFETENSORS_HEADER = 16L * 1024 * 1024;
  private static final int MAX_TENSORS = 100_000;
  private static final int MAX_SHARDS = 250_000;
  private final ObjectMapper json;

  HuggingFaceModelInspector() {
    json = new ObjectMapper();
    json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxDocumentLength(MAX_SAFETENSORS_HEADER)
        .maxTokenCount(2_000_000)
        .maxNestingDepth(64)
        .maxStringLength(16 * 1024)
        .maxNameLength(4 * 1024)
        .build());
  }

  boolean supports(ScannerArtifactType type) {
    return switch (type) {
      case MODEL_INDEX, SAFETENSORS, PICKLE, PKL, PYTORCH, PYTORCH_WEIGHTS,
          CHECKPOINT, PYTORCH_BIN, ONNX, KERAS, TENSORFLOW, FLAX_MSGPACK, GGUF -> true;
      default -> false;
    };
  }

  Map<String, Object> inspect(
      Path input, ScannerArtifactType type, ResourceLimits limits, ScanDeadline deadline) {
    if (!supports(type)) return Map.of();
    deadline.check();
    LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
    summary.put("inputSchema", "huggingface-model-file-v1");
    summary.put("modelArtifactType", type.wireValue());
    summary.put("inspectionMode", "static-non-executing");
    if (type == ScannerArtifactType.SAFETENSORS) {
      summary.putAll(inspectSafeTensors(input, limits, deadline));
    } else if (type == ScannerArtifactType.MODEL_INDEX) {
      summary.putAll(inspectShardIndex(input, limits, deadline));
    } else if (pickleFamily(type)) {
      summary.putAll(inspectPickle(input, deadline));
    }
    deadline.check();
    return Map.copyOf(summary);
  }

  private Map<String, Object> inspectSafeTensors(
      Path input, ResourceLimits limits, ScanDeadline deadline) {
    try (SeekableByteChannel channel = Files.newByteChannel(input, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size < 10) throw rejected("SAFETENSORS_TRUNCATED", "SafeTensors input is truncated");
      ByteBuffer lengthBytes = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
      readFully(channel, lengthBytes);
      lengthBytes.flip();
      long headerLength = lengthBytes.getLong();
      long maximum = Math.min(MAX_SAFETENSORS_HEADER, limits.maxSingleFileBytes());
      if (headerLength <= 1 || headerLength > maximum || headerLength > size - Long.BYTES
          || headerLength > Integer.MAX_VALUE) {
        throw rejected("SAFETENSORS_HEADER_LIMIT", "SafeTensors header length is invalid");
      }
      ByteBuffer header = ByteBuffer.allocate((int) headerLength);
      readFully(channel, header);
      deadline.check();
      JsonNode root = json.readTree(header.array());
      if (root == null || !root.isObject()) {
        throw rejected("SAFETENSORS_HEADER_INVALID", "SafeTensors header must be an object");
      }
      long payloadBytes = size - Long.BYTES - headerLength;
      List<Range> ranges = new ArrayList<>();
      int tensorCount = 0;
      var fields = root.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if ("__metadata__".equals(field.getKey())) continue;
        if (++tensorCount > MAX_TENSORS) {
          throw rejected("SAFETENSORS_TENSOR_LIMIT", "SafeTensors tensor count exceeds limit");
        }
        JsonNode tensor = field.getValue();
        JsonNode offsets = tensor.path("data_offsets");
        JsonNode shape = tensor.path("shape");
        if (!tensor.isObject() || !tensor.path("dtype").isTextual()
            || !shape.isArray() || shape.size() > 32
            || !offsets.isArray() || offsets.size() != 2
            || !offsets.get(0).canConvertToLong() || !offsets.get(1).canConvertToLong()) {
          throw rejected("SAFETENSORS_TENSOR_INVALID", "SafeTensors tensor metadata is invalid");
        }
        for (JsonNode dimension : shape) {
          if (!dimension.canConvertToLong() || dimension.longValue() < 0) {
            throw rejected("SAFETENSORS_SHAPE_INVALID", "SafeTensors shape is invalid");
          }
        }
        long start = offsets.get(0).longValue();
        long end = offsets.get(1).longValue();
        if (start < 0 || end < start || end > payloadBytes) {
          throw rejected("SAFETENSORS_OFFSET_INVALID", "SafeTensors data offset is invalid");
        }
        ranges.add(new Range(start, end));
      }
      ranges.sort(Comparator.comparingLong(Range::start).thenComparingLong(Range::end));
      long previousEnd = 0;
      for (Range range : ranges) {
        if (range.start() < previousEnd) {
          throw rejected("SAFETENSORS_OFFSET_OVERLAP", "SafeTensors data offsets overlap");
        }
        previousEnd = range.end();
      }
      return Map.of(
          "safetensorsHeaderBytes", headerLength,
          "safetensorsPayloadBytes", payloadBytes,
          "safetensorsTensorCount", tensorCount,
          "safetensorsOffsetsValid", true);
    } catch (ScannerRequestException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new ScannerRequestException(
          "SAFETENSORS_INVALID", "Unable to inspect SafeTensors input", 422, false, failure);
    }
  }

  private Map<String, Object> inspectShardIndex(
      Path input, ResourceLimits limits, ScanDeadline deadline) {
    try {
      long size = Files.size(input);
      if (size > Math.min(MAX_SAFETENSORS_HEADER, limits.maxSingleFileBytes())) {
        throw rejected("MODEL_INDEX_LIMIT", "Model shard index exceeds limit");
      }
      JsonNode root;
      try (InputStream stream = Files.newInputStream(input)) {
        root = json.readTree(stream);
      }
      JsonNode weightMap = root == null ? null : root.path("weight_map");
      if (weightMap == null || !weightMap.isObject()) {
        throw rejected("MODEL_INDEX_INVALID", "Model shard index requires weight_map");
      }
      int entries = 0;
      java.util.LinkedHashSet<String> shards = new java.util.LinkedHashSet<>();
      var fields = weightMap.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        if (++entries > MAX_SHARDS || !entry.getValue().isTextual()) {
          throw rejected("MODEL_INDEX_LIMIT", "Model shard index exceeds entry limit");
        }
        String shard = entry.getValue().textValue();
        validateRelativePath(shard);
        shards.add(shard);
      }
      deadline.check();
      return Map.of(
          "modelIndexEntries", entries,
          "modelIndexShardCount", shards.size(),
          "modelIndexPathsValid", true);
    } catch (ScannerRequestException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new ScannerRequestException(
          "MODEL_INDEX_INVALID", "Unable to inspect model shard index", 422, false, failure);
    }
  }

  private Map<String, Object> inspectPickle(Path input, ScanDeadline deadline) {
    int globalOpcodes = 0;
    int reduceOpcodes = 0;
    int constructorOpcodes = 0;
    try (InputStream stream = new BufferedInputStream(Files.newInputStream(input))) {
      int value;
      long offset = 0;
      while ((value = stream.read()) >= 0) {
        offset++;
        if ((offset & 0xffff) == 0) deadline.check();
        switch (value) {
          case 'c' -> {
            globalOpcodes++;
            skipLine(stream);
            skipLine(stream);
          }
          case 0x93 -> globalOpcodes++;
          case 'R' -> reduceOpcodes++;
          case 'i' -> {
            constructorOpcodes++;
            skipLine(stream);
            skipLine(stream);
          }
          case 'o', 0x81, 0x92 -> constructorOpcodes++;
          case 0x82 -> {
            constructorOpcodes++;
            skipFully(stream, 1);
          }
          case 0x83 -> {
            constructorOpcodes++;
            skipFully(stream, 2);
          }
          case 0x84 -> {
            constructorOpcodes++;
            skipFully(stream, 4);
          }
          case 0x80 -> skipFully(stream, 1);
          case 0x95, 'G', 0x8d, 0x8e, 0x96 -> skipLengthOrFixed(stream, value);
          case 'J', 'X', 'B', 'T' -> skipLength32(stream);
          case 'K', 'U', 'C', 0x8c, 0x8a -> skipLength8(stream);
          case 'M' -> skipFully(stream, 2);
          case 0x8b -> skipLength32(stream);
          case 'F', 'I', 'L', 'P', 'S', 'V', 'g', 'p' -> skipLine(stream);
          default -> { }
        }
      }
    } catch (IOException failure) {
      throw new ScannerRequestException(
          "PICKLE_INSPECTION_FAILED", "Unable to statically inspect pickle input", 422, false,
          failure);
    }
    int dangerous = globalOpcodes + reduceOpcodes + constructorOpcodes;
    return Map.of(
        "pickleGlobalOpcodes", globalOpcodes,
        "pickleReduceOpcodes", reduceOpcodes,
        "pickleConstructorOpcodes", constructorOpcodes,
        "pickleDangerousOpcodeCount", dangerous,
        "pickleRequiresReview", dangerous > 0);
  }

  private static boolean pickleFamily(ScannerArtifactType type) {
    return switch (type) {
      case PICKLE, PKL, PYTORCH, PYTORCH_WEIGHTS, CHECKPOINT, PYTORCH_BIN -> true;
      default -> false;
    };
  }

  private static void validateRelativePath(String value) {
    if (value == null || value.isBlank() || value.length() > 2_048
        || value.startsWith("/") || value.startsWith("\\") || value.indexOf('\\') >= 0
        || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw rejected("MODEL_INDEX_PATH_INVALID", "Model shard path is invalid");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
        throw rejected("MODEL_INDEX_PATH_INVALID", "Model shard path is invalid");
      }
    }
  }

  private static void readFully(SeekableByteChannel channel, ByteBuffer target) throws IOException {
    while (target.hasRemaining()) {
      if (channel.read(target) < 0) throw new IOException("truncated input");
    }
  }

  private static void skipLengthOrFixed(InputStream stream, int opcode) throws IOException {
    if (opcode == 0x95 || opcode == 'G') {
      skipFully(stream, 8);
      return;
    }
    long length = readLittleEndian(stream, 8);
    skipFully(stream, length);
  }

  private static void skipLength32(InputStream stream) throws IOException {
    long length = readLittleEndian(stream, 4);
    skipFully(stream, length);
  }

  private static void skipLength8(InputStream stream) throws IOException {
    int length = stream.read();
    if (length < 0) throw new IOException("truncated pickle opcode");
    skipFully(stream, length);
  }

  private static long readLittleEndian(InputStream stream, int bytes) throws IOException {
    long value = 0;
    for (int index = 0; index < bytes; index++) {
      int octet = stream.read();
      if (octet < 0) throw new IOException("truncated pickle opcode");
      if (index >= Long.BYTES || (index == 7 && (octet & 0x80) != 0)) {
        throw new IOException("pickle length overflow");
      }
      value |= (long) octet << (index * 8);
    }
    return value;
  }

  private static void skipFully(InputStream stream, long bytes) throws IOException {
    if (bytes < 0) throw new IOException("negative pickle length");
    stream.skipNBytes(bytes);
  }

  private static void skipLine(InputStream stream) throws IOException {
    int value;
    while ((value = stream.read()) >= 0 && value != '\n') { }
    if (value < 0) throw new IOException("truncated pickle line");
  }

  private static ScannerRequestException rejected(String code, String message) {
    return new ScannerRequestException(code, message, 422, false);
  }

  private record Range(long start, long end) { }
}
