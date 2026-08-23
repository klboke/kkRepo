package com.github.klboke.kkrepo.protocol.r;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parser and deterministic renderer for CRAN PACKAGES DCF metadata. */
public final class RPackageIndex {
  public static final List<String> FIELD_ORDER = List.of(
      "Package", "Version", "Priority", "Depends", "Imports", "LinkingTo", "Suggests",
      "Enhances", "License", "License_is_FOSS", "License_restricts_use", "OS_type", "Archs",
      "MD5sum", "NeedsCompilation", "File", "Path");
  public static final Comparator<RPackageMetadata> ORDER =
      Comparator.comparing(RPackageMetadata::packageName)
          .thenComparing(RPackageMetadata::version, RVersions.COMPARATOR);

  private RPackageIndex() {
  }

  public static List<RPackageMetadata> parse(byte[] bytes) {
    List<RPackageMetadata> result = new ArrayList<>();
    for (Map<String, String> fields : RDcf.parse(bytes)) {
      result.add(RPackageMetadata.fromIndexRecord(fields));
    }
    return List.copyOf(result);
  }

  public static byte[] render(List<RPackageMetadata> packages) {
    List<RPackageMetadata> sorted = new ArrayList<>(packages == null ? List.of() : packages);
    sorted.sort(ORDER);
    StringBuilder output = new StringBuilder();
    for (RPackageMetadata metadata : sorted) {
      LinkedHashMap<String, String> fields = new LinkedHashMap<>(metadata.fields());
      fields.put("Package", metadata.packageName());
      fields.put("Version", metadata.version());
      output.append(RDcf.renderRecord(fields, FIELD_ORDER)).append('\n');
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }
}
