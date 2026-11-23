package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a filtered npm packument without allocating a second full object tree. */
final class NpmPackumentResponseWriter {
  private NpmPackumentResponseWriter() {
  }

  /** Renders a fresh group cache projection without materializing its JSON as maps and lists. */
  static byte[] writeCached(
      ObjectMapper mapper,
      InputStream input,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    try (JsonParser parser = mapper.getFactory().createParser(input);
         ByteArrayBuilder output = new ByteArrayBuilder();
         JsonGenerator generator = mapper.getFactory().createGenerator(output)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new JsonParseException(parser, "Expected a cached npm packument object");
      }
      generator.writeStartObject();
      boolean hasVersions = false;
      boolean hasDistTags = false;
      for (String field; (field = parser.nextFieldName()) != null; ) {
        parser.nextToken();
        if (variant.abbreviated() && !NpmMetadata.isAbbreviatedRootField(field)) {
          parser.skipChildren();
          continue;
        }
        generator.writeFieldName(field);
        switch (field) {
          case NpmMetadata.VERSIONS -> {
            hasVersions = true;
            copyCachedVersions(mapper, parser, generator, variant, packageId, repositoryBaseUrl);
          }
          case NpmMetadata.DIST_TAGS -> {
            hasDistTags = true;
            if (parser.isExpectedStartObjectToken()) {
              generator.copyCurrentStructure(parser);
            } else {
              parser.skipChildren();
              writeEmptyObject(generator);
            }
          }
          default -> generator.copyCurrentStructure(parser);
        }
      }
      if (!hasVersions) {
        generator.writeFieldName(NpmMetadata.VERSIONS);
        writeEmptyObject(generator);
      }
      if (!hasDistTags) {
        generator.writeFieldName(NpmMetadata.DIST_TAGS);
        writeEmptyObject(generator);
      }
      generator.writeEndObject();
      generator.flush();
      return output.toByteArray();
    }
  }

  private static void copyCachedVersions(
      ObjectMapper mapper,
      JsonParser parser,
      JsonGenerator generator,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    if (parser.isExpectedStartObjectToken()) {
      for (String version; (version = parser.nextFieldName()) != null; ) {
        generator.writeFieldName(version);
        if (parser.nextToken() == JsonToken.START_OBJECT) {
          copyCachedVersion(mapper, parser, generator, variant, packageId, repositoryBaseUrl);
        } else {
          generator.copyCurrentStructure(parser);
        }
      }
    } else {
      parser.skipChildren();
    }
    generator.writeEndObject();
  }

  private static void copyCachedVersion(
      ObjectMapper mapper,
      JsonParser parser,
      JsonGenerator generator,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    boolean hasInstallScriptField = false;
    boolean hasInstallScript = false;
    for (String field; (field = parser.nextFieldName()) != null; ) {
      parser.nextToken();
      if (variant.abbreviated() && !NpmMetadata.isAbbreviatedVersionField(field)) {
        if ("scripts".equals(field) && parser.isExpectedStartObjectToken() && !hasInstallScriptField) {
          hasInstallScript = NpmMetadata.hasInstallScript(mapper.readValue(parser, Object.class));
        } else {
          parser.skipChildren();
        }
        continue;
      }
      hasInstallScriptField |= "hasInstallScript".equals(field);
      generator.writeFieldName(field);
      if (NpmMetadata.DIST.equals(field) && parser.isExpectedStartObjectToken()) {
        copyCachedDist(mapper, parser, generator, packageId, repositoryBaseUrl);
      } else {
        generator.copyCurrentStructure(parser);
      }
    }
    if (variant.abbreviated() && !hasInstallScriptField && hasInstallScript) {
      generator.writeBooleanField("hasInstallScript", true);
    }
    generator.writeEndObject();
  }

  private static void copyCachedDist(
      ObjectMapper mapper,
      JsonParser parser,
      JsonGenerator generator,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    for (String field; (field = parser.nextFieldName()) != null; ) {
      generator.writeFieldName(field);
      JsonToken value = parser.nextToken();
      if (!NpmMetadata.TARBALL.equals(field) || value == JsonToken.VALUE_NULL) {
        generator.copyCurrentStructure(parser);
        continue;
      }
      if (value == JsonToken.VALUE_STRING) {
        String original = parser.getText();
        String rewritten = rewrittenTarballUrl(original, packageId, repositoryBaseUrl);
        generator.writeString(rewritten == null ? original : rewritten);
      } else {
        Object original = mapper.readValue(parser, Object.class);
        String rewritten = rewrittenTarballUrl(original, packageId, repositoryBaseUrl);
        if (rewritten == null) {
          mapper.writeValue(generator, original);
        } else {
          generator.writeString(rewritten);
        }
      }
    }
    generator.writeEndObject();
  }

  private static void writeEmptyObject(JsonGenerator generator) throws IOException {
    generator.writeStartObject();
    generator.writeEndObject();
  }

  static byte[] write(
      ObjectMapper mapper,
      Map<String, Object> packageRoot,
      NpmMinimumReleaseAge.Analysis analysis,
      Instant evaluatedAt,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) {
    Map<String, Object> versions = NpmMetadata.versions(packageRoot);
    List<String> visibleVersions = analysis == null
        ? List.copyOf(versions.keySet())
        : analysis.visibleVersions(evaluatedAt);
    Map<String, Object> distTags = analysis == null
        ? new LinkedHashMap<>(NpmMetadata.distTags(packageRoot))
        : analysis.filteredDistTags(packageRoot, evaluatedAt, visibleVersions);
    Map<String, Object> time = analysis == null
        ? null
        : analysis.filteredTime(packageRoot, visibleVersions);

    try (ByteArrayBuilder output = new ByteArrayBuilder();
         JsonGenerator generator = mapper.getFactory().createGenerator(output)) {
      generator.writeStartObject();
      for (Map.Entry<String, Object> field : packageRoot.entrySet()) {
        if (variant.abbreviated() && !NpmMetadata.isAbbreviatedRootField(field.getKey())) {
          continue;
        }
        generator.writeFieldName(field.getKey());
        if (NpmMetadata.VERSIONS.equals(field.getKey())) {
          writeVersions(
              mapper, generator, versions, visibleVersions, variant, packageId, repositoryBaseUrl);
        } else if (NpmMetadata.DIST_TAGS.equals(field.getKey())) {
          mapper.writeValue(generator, distTags);
        } else if (NpmMetadata.TIME.equals(field.getKey()) && time != null) {
          mapper.writeValue(generator, time);
        } else {
          mapper.writeValue(generator, field.getValue());
        }
      }
      generator.writeEndObject();
      generator.flush();
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize npm packument", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static void writeVersions(
      ObjectMapper mapper,
      JsonGenerator generator,
      Map<String, Object> versions,
      List<String> visibleVersions,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    for (String version : visibleVersions) {
      if (!versions.containsKey(version)) {
        continue;
      }
      generator.writeFieldName(version);
      Object rawVersion = versions.get(version);
      if (!(rawVersion instanceof Map<?, ?> versionMap)) {
        mapper.writeValue(generator, rawVersion);
        continue;
      }
      writeVersion(
          mapper,
          generator,
          (Map<String, Object>) versionMap,
          variant,
          packageId,
          repositoryBaseUrl);
    }
    generator.writeEndObject();
  }

  @SuppressWarnings("unchecked")
  private static void writeVersion(
      ObjectMapper mapper,
      JsonGenerator generator,
      Map<String, Object> version,
      NpmPackumentVariant variant,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    boolean hasInstallScriptField = false;
    for (Map.Entry<String, Object> field : version.entrySet()) {
      if (variant.abbreviated() && !NpmMetadata.isAbbreviatedVersionField(field.getKey())) {
        continue;
      }
      hasInstallScriptField |= "hasInstallScript".equals(field.getKey());
      generator.writeFieldName(field.getKey());
      if (NpmMetadata.DIST.equals(field.getKey()) && field.getValue() instanceof Map<?, ?> dist) {
        writeDist(
            mapper,
            generator,
            (Map<String, Object>) dist,
            packageId,
            repositoryBaseUrl);
      } else {
        mapper.writeValue(generator, field.getValue());
      }
    }
    if (variant.abbreviated() && !hasInstallScriptField
        && NpmMetadata.hasInstallScript(version.get("scripts"))) {
      generator.writeBooleanField("hasInstallScript", true);
    }
    generator.writeEndObject();
  }

  private static void writeDist(
      ObjectMapper mapper,
      JsonGenerator generator,
      Map<String, Object> dist,
      NpmPackageId packageId,
      String repositoryBaseUrl) throws IOException {
    generator.writeStartObject();
    for (Map.Entry<String, Object> field : dist.entrySet()) {
      generator.writeFieldName(field.getKey());
      if (NpmMetadata.TARBALL.equals(field.getKey())) {
        String rewritten = rewrittenTarballUrl(
            field.getValue(), packageId, repositoryBaseUrl);
        if (rewritten == null) {
          mapper.writeValue(generator, field.getValue());
        } else {
          generator.writeString(rewritten);
        }
      } else {
        mapper.writeValue(generator, field.getValue());
      }
    }
    generator.writeEndObject();
  }

  private static String rewrittenTarballUrl(
      Object rawTarball,
      NpmPackageId packageId,
      String repositoryBaseUrl) {
    if (rawTarball == null || repositoryBaseUrl == null || repositoryBaseUrl.isBlank()) {
      return null;
    }
    String tarballName = NpmMetadata.tarballFilename(rawTarball.toString());
    if (tarballName == null || tarballName.isBlank()) {
      return null;
    }
    String base = repositoryBaseUrl.endsWith("/")
        ? repositoryBaseUrl.substring(0, repositoryBaseUrl.length() - 1)
        : repositoryBaseUrl;
    return base + "/" + packageId.tarballPath(tarballName);
  }
}
