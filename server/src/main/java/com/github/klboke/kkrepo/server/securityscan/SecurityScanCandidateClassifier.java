package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Protocol-aware candidate classification. Metadata and signatures never reach a scanner. */
@Component
public class SecurityScanCandidateClassifier {
  private static final Set<String> ARCHIVE_SUFFIXES = Set.of(
      ".zip", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".xz", ".tar.bz2", ".tbz2",
      ".jar", ".war", ".ear", ".whl", ".crate", ".gem", ".nupkg", ".rpm");

  public Classification classify(
      AssetRecord asset, AssetBlobRecord blob, ScanProfile profile) {
    if (asset == null || blob == null) {
      return notApplicable("MISSING_CONTENT");
    }
    return classify(
        asset.format(),
        asset.path(),
        asset.kind(),
        asset.contentType(),
        blob.size(),
        profile);
  }

  Classification classify(
      RepositoryFormat format,
      String assetPath,
      String assetKind,
      String assetContentType,
      long blobSize,
      ScanProfile profile) {
    if (blobSize < 0 || blobSize > profile.maxInputBytes()) {
      return new Classification(
          CandidateDisposition.REJECTED_BY_LIMIT,
          null,
          null,
          "INPUT_SIZE_LIMIT");
    }
    String path = normalizePath(assetPath);
    String kind = lower(assetKind);
    String mediaType = lower(assetContentType);

    if (format == RepositoryFormat.DOCKER) {
      if (isDockerManifest(kind, mediaType, path)) {
        return scannable(SubjectKind.OCI_MANIFEST, TargetClassification.OCI_IMAGE);
      }
      return notApplicable("OCI_LAYER_OR_METADATA");
    }

    boolean scannable = switch (format) {
      case MAVEN2 -> hasAnySuffix(path, ".jar", ".war", ".ear", ".zip")
          && !metadataOrSignature(path);
      case NPM -> hasAnySuffix(path, ".tgz") && !containsAny(kind, "metadata", "packument");
      case PYPI -> hasAnySuffix(path, ".whl", ".tar.gz", ".zip")
          && !containsAny(kind, "index", "metadata");
      case GO -> hasAnySuffix(path, ".zip") && !hasAnySuffix(path, ".info", ".mod");
      case HELM -> hasAnySuffix(path, ".tgz") && !hasAnySuffix(path, ".prov");
      case CARGO -> hasAnySuffix(path, ".crate") && !containsAny(kind, "index", "metadata");
      case PUB -> (hasAnySuffix(path, ".tar.gz", ".tgz")
          || containsAny(kind, "archive", "package"))
          && !containsAny(kind, "metadata");
      case COMPOSER -> isArchive(path) && !containsAny(kind, "metadata", "provider");
      case TERRAFORM -> isArchive(path) && !metadataOrSignature(path);
      case SWIFT -> isArchive(path) && !containsAny(kind, "manifest", "metadata");
      case ANSIBLEGALAXY -> hasAnySuffix(path, ".tar.gz")
          && !containsAny(kind, "metadata", "signature", "import");
      case NUGET -> hasAnySuffix(path, ".nupkg") && !hasAnySuffix(path, ".snupkg");
      case RUBYGEMS -> hasAnySuffix(path, ".gem") && !containsAny(kind, "index", "spec");
      case YUM -> hasAnySuffix(path, ".rpm") && !path.contains("/repodata/");
      case RAW -> rawAllowed(path, profile);
      case DOCKER -> false;
    };
    if (!scannable) {
      return notApplicable("PROTOCOL_METADATA_OR_UNSUPPORTED_TYPE");
    }
    TargetClassification target = format == RepositoryFormat.RAW
        ? TargetClassification.ARCHIVE
        : TargetClassification.PACKAGE;
    return scannable(SubjectKind.ASSET_BLOB, target);
  }

  private static boolean rawAllowed(String path, ScanProfile profile) {
    Object configured = profile.targetRules().get("rawArchiveSuffixes");
    if (configured instanceof Iterable<?> values) {
      for (Object value : values) {
        if (value != null && path.endsWith(lower(value.toString()))) {
          return true;
        }
      }
      return false;
    }
    return ARCHIVE_SUFFIXES.stream().anyMatch(path::endsWith);
  }

  private static boolean isDockerManifest(String kind, String mediaType, String path) {
    return containsAny(kind, "manifest", "index")
        || mediaType.contains("manifest")
        || mediaType.contains("image.index")
        || path.contains("/manifests/");
  }

  private static boolean metadataOrSignature(String path) {
    return hasAnySuffix(
        path,
        ".sha1",
        ".sha256",
        ".sha512",
        ".md5",
        ".asc",
        ".sig",
        ".signature",
        "maven-metadata.xml",
        "index.yaml",
        "packages.json",
        "sha256sums");
  }

  private static boolean isArchive(String path) {
    return ARCHIVE_SUFFIXES.stream().anyMatch(path::endsWith);
  }

  private static boolean hasAnySuffix(String value, String... suffixes) {
    for (String suffix : suffixes) {
      if (value.endsWith(suffix)) return true;
    }
    return false;
  }

  private static boolean containsAny(String value, String... fragments) {
    for (String fragment : fragments) {
      if (value.contains(fragment)) return true;
    }
    return false;
  }

  private static String normalizePath(String value) {
    if (value == null) return "";
    return value.replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static Classification scannable(
      SubjectKind kind, TargetClassification classification) {
    return new Classification(CandidateDisposition.SCANNABLE, kind, classification, "SCANNABLE");
  }

  private static Classification notApplicable(String reason) {
    return new Classification(CandidateDisposition.NOT_APPLICABLE, null, null, reason);
  }

  public record Classification(
      CandidateDisposition disposition,
      SubjectKind subjectKind,
      TargetClassification targetClassification,
      String reasonCode) {}
}
