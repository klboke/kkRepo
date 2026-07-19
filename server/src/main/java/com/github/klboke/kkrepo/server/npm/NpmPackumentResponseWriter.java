package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a filtered npm packument without allocating a second full object tree. */
final class NpmPackumentResponseWriter {
  private NpmPackumentResponseWriter() {
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
    String tarballName = NpmMetadata.extractTarballName(rawTarball.toString());
    if (tarballName == null || tarballName.isBlank()) {
      return null;
    }
    String base = repositoryBaseUrl.endsWith("/")
        ? repositoryBaseUrl.substring(0, repositoryBaseUrl.length() - 1)
        : repositoryBaseUrl;
    return base + "/" + packageId.tarballPath(tarballName);
  }
}
