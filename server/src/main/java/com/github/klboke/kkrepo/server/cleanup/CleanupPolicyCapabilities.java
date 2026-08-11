package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyVersions;
import com.github.klboke.kkrepo.protocol.apt.DebianVersions;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.protocol.conda.CondaVersions;
import com.github.klboke.kkrepo.protocol.conan.ConanVersions;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenVersionComparator;
import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import com.github.klboke.kkrepo.protocol.terraform.TerraformVersions;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CleanupPolicyCapabilities {
  private final Map<RepositoryFormat, Comparator<String>> versionComparators;

  public CleanupPolicyCapabilities() {
    EnumMap<RepositoryFormat, Comparator<String>> comparators =
        new EnumMap<>(RepositoryFormat.class);
    comparators.put(RepositoryFormat.MAVEN2, MavenVersionComparator.INSTANCE);
    comparators.put(RepositoryFormat.CARGO, CargoVersions::compare);
    comparators.put(RepositoryFormat.PUB, PubVersions.COMPARATOR);
    comparators.put(RepositoryFormat.TERRAFORM, TerraformVersions.comparator());
    comparators.put(RepositoryFormat.SWIFT, SwiftVersions.COMPARATOR);
    comparators.put(RepositoryFormat.ANSIBLEGALAXY, AnsibleGalaxyVersions.COMPARATOR);
    comparators.put(RepositoryFormat.CONDA, CondaVersions.COMPARATOR);
    comparators.put(RepositoryFormat.CONAN, ConanVersions.comparator());
    comparators.put(RepositoryFormat.APT, DebianVersions::compare);
    this.versionComparators = Map.copyOf(comparators);
  }

  public List<FormatCapability> all() {
    return java.util.Arrays.stream(RepositoryFormat.values())
        .map(format -> new FormatCapability(
            format,
            true,
            versionComparators.containsKey(format),
            true,
            true))
        .toList();
  }

  public Optional<Comparator<String>> versionComparator(RepositoryFormat format) {
    return Optional.ofNullable(versionComparators.get(format));
  }

  public boolean supportsRetainCount(RepositoryFormat format) {
    return versionComparators.containsKey(format);
  }

  public boolean supportsLastDownloaded(RepositoryFormat format) {
    return true;
  }

  public boolean supportsExecute(RepositoryFormat format) {
    return true;
  }

  public record FormatCapability(
      RepositoryFormat format,
      boolean tryRunSupported,
      boolean retainCountSupported,
      boolean lastDownloadedSupported,
      boolean executeSupported) {
  }
}
