package com.github.klboke.kkrepo.protocol.helm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class HelmChartPackageParser {
  private static final String CHART_YAML = "Chart.yaml";
  private static final int MAX_CHART_YAML_BYTES = 1024 * 1024;

  public HelmChartMetadata parse(InputStream input) throws IOException {
    byte[] chartYaml = readChartYaml(input);
    Object loaded = newYaml().load(new ByteArrayInputStream(chartYaml));
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Chart.yaml is not a YAML mapping");
    }
    HelmChartMetadata metadata = HelmChartMetadata.fromYamlMap(castMap(map));
    metadata.requireNameAndVersion();
    return metadata;
  }

  private static Yaml newYaml() {
    // Yaml retains mutable load state, while this parser is shared by singleton hosted services.
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(MAX_CHART_YAML_BYTES);
    options.setMaxAliasesForCollections(50);
    return new Yaml(new SafeConstructor(options));
  }

  private byte[] readChartYaml(InputStream input) throws IOException {
    try (GZIPInputStream gzip = new GZIPInputStream(input);
         TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      ArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isDirectory() && isChartYamlEntry(entry.getName())) {
          return readCurrentEntry(tar);
        }
      }
    }
    throw new IllegalArgumentException(CHART_YAML + " not found in Helm chart package");
  }

  private static boolean isChartYamlEntry(String name) {
    if (name == null) return false;
    String normalized = name.replace('\\', '/');
    if (normalized.startsWith("./")) normalized = normalized.substring(2);
    if (CHART_YAML.equals(normalized)) return true;
    int firstSlash = normalized.indexOf('/');
    return firstSlash > 0
        && normalized.indexOf('/', firstSlash + 1) < 0
        && normalized.substring(firstSlash + 1).equals(CHART_YAML);
  }

  private static byte[] readCurrentEntry(TarArchiveInputStream tar) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buffer = new byte[8192];
    int total = 0;
    int n;
    while ((n = tar.read(buffer)) > 0) {
      if (total > MAX_CHART_YAML_BYTES - n) {
        throw new IllegalArgumentException(
            CHART_YAML + " exceeds the " + MAX_CHART_YAML_BYTES + " byte limit");
      }
      out.write(buffer, 0, n);
      total += n;
    }
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
