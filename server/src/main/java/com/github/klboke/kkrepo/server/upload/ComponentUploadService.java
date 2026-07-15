package com.github.klboke.kkrepo.server.upload;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cargo.CargoHostedService;
import com.github.klboke.kkrepo.server.composer.ComposerHostedService;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenHostedService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.pub.PubHostedService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.terraform.TerraformService;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.yum.YumService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ComponentUploadService {
  private static final List<UploadDefinition> DEFINITIONS = List.of(
      new UploadDefinition(
          "maven2",
          true,
          List.of(
              field("groupId", "STRING", "Group ID", false, "Component coordinates"),
              field("artifactId", "STRING", "Artifact ID", false, "Component coordinates"),
              field("version", "STRING", "Version", false, "Component coordinates"),
              field("generate-pom", "BOOLEAN", "Generate a POM file with these coordinates", true,
                  "Component coordinates"),
              field("packaging", "STRING", "Packaging", true, "Component coordinates")),
          List.of(
              field("classifier", "STRING", "Classifier", true, null),
              field("extension", "STRING", "Extension", false, null),
              field("asset", "FILE", "Asset", false, null))),
      singleAsset("npm"),
      singleAsset("pypi"),
      singleAsset("helm"),
      singleAsset("cargo"),
      singleAsset("pub"),
      new UploadDefinition(
          "composer",
          false,
          List.of(
              field("name", "STRING", "Package name override (vendor/package)", true,
                  "Component coordinates"),
              field("version", "STRING", "Package version override", true,
                  "Component coordinates")),
          List.of(field("asset", "FILE", "Composer archive", false, null))),
      new UploadDefinition(
          "terraform",
          false,
          List.of(
              field("kind", "STRING", "module or provider", false, "Component coordinates"),
              field("namespace", "STRING", "Namespace", false, "Component coordinates"),
              field("name", "STRING", "Module name or provider type", false, "Component coordinates"),
              field("version", "STRING", "Semantic version", false, "Component coordinates"),
              field("system", "STRING", "Module target system", true, "Component coordinates"),
              field("os", "STRING", "Provider operating system", true, "Component coordinates"),
              field("arch", "STRING", "Provider architecture", true, "Component coordinates")),
          List.of(field("asset", "FILE", "Terraform module/provider archive", false, null))),
      rawLikeUpload("nuget"),
      rawLikeUpload("rubygems"),
      rawLikeUpload("yum"),
      rawUpload());

  private final RepositoryRuntimeRegistry registry;
  private final AssetDao assetDao;
  private final MavenHostedService mavenHosted;
  private final NpmHostedService npmHosted;
  private final PypiHostedService pypiHosted;
  private final HelmHostedService helmHosted;
  private final CargoHostedService cargoHosted;
  private final PubHostedService pubHosted;
  private final ComposerHostedService composerHosted;
  private final RawHostedService rawHosted;
  private final YumService yumService;
  private final TerraformService terraformService;
  private final MavenPathParser mavenPathParser = new MavenPathParser();
  private final TerraformPathParser terraformPathParser = new TerraformPathParser();

  @Autowired
  public ComponentUploadService(
      RepositoryRuntimeRegistry registry,
      AssetDao assetDao,
      MavenHostedService mavenHosted,
      NpmHostedService npmHosted,
      PypiHostedService pypiHosted,
      HelmHostedService helmHosted,
      CargoHostedService cargoHosted,
      PubHostedService pubHosted,
      ComposerHostedService composerHosted,
      RawHostedService rawHosted,
      YumService yumService,
      TerraformService terraformService) {
    this.registry = registry;
    this.assetDao = assetDao;
    this.mavenHosted = mavenHosted;
    this.npmHosted = npmHosted;
    this.pypiHosted = pypiHosted;
    this.helmHosted = helmHosted;
    this.cargoHosted = cargoHosted;
    this.pubHosted = pubHosted;
    this.composerHosted = composerHosted;
    this.rawHosted = rawHosted;
    this.yumService = yumService;
    this.terraformService = terraformService;
  }

  public ComponentUploadService(
      RepositoryRuntimeRegistry registry, AssetDao assetDao, MavenHostedService mavenHosted,
      NpmHostedService npmHosted, PypiHostedService pypiHosted, HelmHostedService helmHosted,
      CargoHostedService cargoHosted, PubHostedService pubHosted, ComposerHostedService composerHosted,
      RawHostedService rawHosted, YumService yumService) {
    this(registry, assetDao, mavenHosted, npmHosted, pypiHosted, helmHosted, cargoHosted,
        pubHosted, composerHosted, rawHosted, yumService, null);
  }

  /** Backward-compatible constructor used by existing focused upload tests. */
  public ComponentUploadService(
      RepositoryRuntimeRegistry registry,
      AssetDao assetDao,
      MavenHostedService mavenHosted,
      NpmHostedService npmHosted,
      PypiHostedService pypiHosted,
      HelmHostedService helmHosted,
      CargoHostedService cargoHosted,
      PubHostedService pubHosted,
      RawHostedService rawHosted,
      YumService yumService) {
    this(registry, assetDao, mavenHosted, npmHosted, pypiHosted, helmHosted, cargoHosted,
        pubHosted, null, rawHosted, yumService, null);
  }

  public List<UploadDefinition> definitions() {
    return DEFINITIONS;
  }

  public UploadDefinition definition(String format) {
    String normalized = normalizeFormat(format);
    return DEFINITIONS.stream()
        .filter(def -> def.format().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new UploadValidationException("Unsupported upload format: " + format));
  }

  public UploadResult upload(
      String repository,
      Map<String, String[]> rawFields,
      MultiValueMap<String, MultipartFile> rawFiles,
      String createdBy,
      String createdByIp) throws IOException {
    RepositoryRuntime runtime = registry.resolve(repository)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException("Repository not found: " + repository));
    if (!runtime.online()) {
      throw new UploadValidationException("Repository is offline: " + repository);
    }
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Upload is only valid on hosted repositories");
    }
    if (runtime.format() == RepositoryFormat.TERRAFORM
        && rawFiles.values().stream()
            .flatMap(List::stream)
            .filter(file -> file != null && !file.isEmpty())
            .count() != 1) {
      throw new UploadValidationException("Terraform upload requires exactly one archive");
    }
    String format = formatLabel(runtime.format());
    NormalizedUpload upload = normalize(format, rawFields, rawFiles);
    List<String> paths = switch (runtime.format()) {
      case MAVEN2 -> uploadMaven(runtime, upload, createdBy, createdByIp);
      case NPM -> uploadNpm(runtime, upload, createdBy, createdByIp);
      case PYPI -> uploadPypi(runtime, upload, createdBy, createdByIp);
      case HELM -> uploadHelm(runtime, upload, createdBy, createdByIp);
      case GO -> throw new UploadValidationException("Go hosted upload is not supported");
      case CARGO -> uploadCargo(runtime, upload, createdBy, createdByIp);
      case PUB -> uploadPub(runtime, upload, createdBy, createdByIp);
      case COMPOSER -> uploadComposer(runtime, upload, createdBy, createdByIp);
      case TERRAFORM -> uploadTerraform(runtime, upload, createdBy, createdByIp);
      case DOCKER -> throw new UploadValidationException("Docker hosted upload must use the Docker Registry V2 API");
      case NUGET -> uploadRaw(runtime, upload, createdBy, createdByIp);
      case RUBYGEMS -> uploadRaw(runtime, upload, createdBy, createdByIp);
      case YUM -> uploadYum(runtime, upload, createdBy, createdByIp);
      case RAW -> uploadRaw(runtime, upload, createdBy, createdByIp);
    };
    return new UploadResult(paths);
  }

  private List<String> uploadTerraform(
      RepositoryRuntime runtime, NormalizedUpload upload, String createdBy, String createdByIp) throws IOException {
    if (terraformService == null) throw new UploadValidationException("Terraform upload service is unavailable");
    if (upload.assets().size() != 1) {
      throw new UploadValidationException("Terraform upload requires exactly one archive");
    }
    String kind = requireField(upload.fields(), "kind").trim().toLowerCase(Locale.ROOT);
    String namespace = requireField(upload.fields(), "namespace");
    String name = requireField(upload.fields(), "name");
    String version = requireField(upload.fields(), "version");
    AssetUpload asset = upload.assets().getFirst();
    String filename = asset.file().getOriginalFilename();
    if (filename == null || filename.isBlank()) filename = "terraform.zip";
    String path;
    String disposition = null;
    String protocols = null;
    if ("module".equals(kind)) {
      String system = requireField(upload.fields(), "system");
      path = "v1/modules/" + namespace + "/" + name + "/" + system + "/" + version + "/" + filename;
    } else if ("provider".equals(kind)) {
      String os = requireField(upload.fields(), "os");
      String arch = requireField(upload.fields(), "arch");
      protocols = upload.fields().get("protocols");
      path = "v1/providers/" + namespace + "/" + name + "/" + version + "/download/" + os + "/" + arch;
      disposition = "attachment; filename=\"" + filename.replace("\"", "") + "\"";
    } else {
      throw new UploadValidationException("terraform.kind must be module or provider");
    }
    try (var body = asset.file().getInputStream()) {
      terraformService.put(runtime, terraformPathParser.parse(path), body, asset.file().getContentType(),
          disposition, protocols, createdBy, createdByIp);
    }
    return List.of(path);
  }

  private List<String> uploadMaven(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    String groupId = requireField(upload.fields(), "groupId");
    String artifactId = requireField(upload.fields(), "artifactId");
    String version = requireField(upload.fields(), "version");
    validateMavenSegment("groupId", groupId);
    validateMavenSegment("artifactId", artifactId);
    validateMavenSegment("version", version);
    List<AssetUpload> assets = upload.assets();
    if (assets.isEmpty()) {
      throw new UploadValidationException("Maven upload requires at least one asset");
    }
    List<MavenUploadItem> items = new ArrayList<>();
    boolean hasPom = false;
    for (AssetUpload asset : assets) {
      String extension = normalizeExtension(requireAssetField(asset, "extension"));
      String classifier = blankToNull(asset.fields().get("classifier"));
      validateMavenSegment(asset.key() + ".extension", extension);
      if (classifier != null) {
        validateMavenSegment(asset.key() + ".classifier", classifier);
      }
      if ("pom".equals(extension) && classifier == null) {
        hasPom = true;
      }
      String path = mavenPath(groupId, artifactId, version, classifier, extension);
      items.add(new MavenUploadItem(path, contentType(extension), asset.file(), null));
    }
    if (truthy(upload.fields().get("generate-pom")) && !hasPom) {
      String packaging = blankToNull(upload.fields().get("packaging"));
      String pom = generatedPom(groupId, artifactId, version, packaging);
      String path = mavenPath(groupId, artifactId, version, null, "pom");
      items.add(new MavenUploadItem(
          path, MediaType.APPLICATION_XML_VALUE, null, pom.getBytes(StandardCharsets.UTF_8)));
    }
    ensureNoExistingAssets(runtime, items.stream().map(MavenUploadItem::path).toList());
    for (MavenUploadItem item : items) {
      MavenPath mavenPath = mavenPathParser.parsePath(item.path());
      if (item.file() != null) {
        try (var input = item.file().getInputStream()) {
          mavenHosted.put(runtime, mavenPath, input, item.contentType(), createdBy, createdByIp, true);
        }
      } else {
        mavenHosted.put(runtime, mavenPath,
            new ByteArrayInputStream(item.body()), item.contentType(), createdBy, createdByIp, true);
      }
    }
    return items.stream().map(MavenUploadItem::path).toList();
  }

  private List<String> uploadNpm(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "npm");
    npmHosted.uploadTarball(runtime, asset.file(), createdBy, createdByIp);
    return List.of(originalFilename(asset.file()));
  }

  private List<String> uploadPypi(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "PyPI");
    pypiHosted.uploadAsset(runtime, asset.file(), createdBy, createdByIp);
    return List.of(originalFilename(asset.file()));
  }

  private List<String> uploadHelm(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Helm");
    helmHosted.push(runtime, asset.file(), createdBy, createdByIp);
    return List.of(originalFilename(asset.file()));
  }

  private List<String> uploadCargo(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Cargo");
    String filename = originalFilename(asset.file());
    if (!filename.endsWith(".crate")) {
      throw new UploadValidationException("Cargo upload requires a .crate asset");
    }
    try (var input = asset.file().getInputStream()) {
      return List.of(cargoHosted.uploadCrate(runtime, input, createdBy, createdByIp));
    }
  }

  private List<String> uploadPub(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Pub");
    String filename = originalFilename(asset.file());
    if (!filename.endsWith(".tar.gz")) {
      throw new UploadValidationException("Pub upload requires a .tar.gz archive");
    }
    try (var input = asset.file().getInputStream()) {
      return List.of(pubHosted.uploadArchive(runtime, input, createdBy, createdByIp, "component-upload"));
    }
  }

  private List<String> uploadComposer(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Composer");
    String filename = originalFilename(asset.file());
    String lower = filename.toLowerCase(Locale.ROOT);
    if (!(lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz")
        || lower.endsWith(".tgz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2"))) {
      throw new UploadValidationException("Composer upload requires a ZIP or TAR archive");
    }
    try (var input = asset.file().getInputStream()) {
      return List.of(composerHosted.uploadArchive(
          runtime,
          input,
          filename,
          asset.file().getContentType(),
          blankToNull(upload.fields().get("name")),
          blankToNull(upload.fields().get("version")),
          createdBy,
          createdByIp));
    }
  }

  private List<String> uploadRaw(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Raw");
    String filename = blankToNull(asset.fields().get("filename"));
    if (filename == null) {
      filename = originalFilename(asset.file());
    }
    String path = rawPath(upload.fields().get("directory"), filename);
    ensureNoExistingAssets(runtime, List.of(path));
    try (var input = asset.file().getInputStream()) {
      rawHosted.put(runtime, path, input, asset.file().getContentType(), createdBy, createdByIp);
    }
    return List.of(path);
  }

  private List<String> uploadYum(
      RepositoryRuntime runtime,
      NormalizedUpload upload,
      String createdBy,
      String createdByIp) throws IOException {
    AssetUpload asset = singleAsset(upload, "Yum");
    String filename = blankToNull(asset.fields().get("filename"));
    if (filename == null) {
      filename = originalFilename(asset.file());
    }
    String path = rawPath(upload.fields().get("directory"), filename);
    ensureNoExistingAssets(runtime, List.of(path));
    try (var input = asset.file().getInputStream()) {
      yumService.put(runtime, path, input, asset.file().getContentType(), createdBy, createdByIp);
    }
    return List.of(path);
  }

  private NormalizedUpload normalize(
      String format,
      Map<String, String[]> rawFields,
      MultiValueMap<String, MultipartFile> rawFiles) {
    Map<String, String> fields = new LinkedHashMap<>();
    rawFields.forEach((key, values) -> {
      String normalized = stripFormatPrefix(format, key);
      if (normalized == null || normalized.isBlank() || "repository".equals(normalized)
          || "NX-ANTI-CSRF-TOKEN".equals(normalized)) {
        return;
      }
      String value = firstValue(values);
      if (value != null) fields.put(normalized, value);
    });

    Map<String, MultipartFile> files = new LinkedHashMap<>();
    rawFiles.forEach((key, values) -> {
      String normalized = stripFormatPrefix(format, key);
      MultipartFile file = firstFile(values);
      if (normalized != null && !normalized.isBlank() && file != null && !file.isEmpty()) {
        files.put(normalized, file);
      }
    });
    if (files.isEmpty()) {
      throw new UploadValidationException("Upload requires a file asset");
    }

    List<AssetUpload> assets = files.entrySet().stream()
        .sorted(Comparator.comparingInt(entry -> assetSort(entry.getKey())))
        .map(entry -> {
          String assetKey = entry.getKey();
          Map<String, String> assetFields = new LinkedHashMap<>();
          String prefix = assetKey + ".";
          fields.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
              assetFields.put(key.substring(prefix.length()), value);
            }
          });
          return new AssetUpload(assetKey, entry.getValue(), assetFields);
        })
        .toList();

    Map<String, String> componentFields = new LinkedHashMap<>();
    fields.forEach((key, value) -> {
      boolean belongsToAsset = assets.stream().anyMatch(asset -> key.startsWith(asset.key() + "."));
      if (!belongsToAsset) componentFields.put(key, value);
    });
    return new NormalizedUpload(componentFields, assets);
  }

  private static UploadFieldDefinition field(
      String name, String type, String description, boolean optional, String group) {
    return new UploadFieldDefinition(name, type, description, optional, group);
  }

  private static UploadDefinition singleAsset(String format) {
    return new UploadDefinition(format, false, List.of(),
        List.of(field("asset", "FILE", "Asset", false, null)));
  }

  private static UploadDefinition rawUpload() {
    return new UploadDefinition("raw", true,
        List.of(field("directory", "STRING", "Destination for uploaded files", false,
            "Component attributes")),
        List.of(
            field("filename", "STRING", "Filename", false, null),
            field("asset", "FILE", "Asset", false, null)));
  }

  private static UploadDefinition rawLikeUpload(String format) {
    return new UploadDefinition(format, true,
        List.of(field("directory", "STRING", "Destination for uploaded files", false,
            "Component attributes")),
        List.of(
            field("filename", "STRING", "Filename", false, null),
            field("asset", "FILE", "Asset", false, null)));
  }

  private static String normalizeFormat(String format) {
    if (format == null) return "";
    return format.trim().toLowerCase(Locale.ROOT);
  }

  private static String formatLabel(RepositoryFormat format) {
    return switch (format) {
      case MAVEN2 -> "maven2";
      case NPM -> "npm";
      case PYPI -> "pypi";
      case HELM -> "helm";
      case GO -> "go";
      case CARGO -> "cargo";
      case PUB -> "pub";
      case COMPOSER -> "composer";
      case DOCKER -> "docker";
      case NUGET -> "nuget";
      case RUBYGEMS -> "rubygems";
      case YUM -> "yum";
      case TERRAFORM -> "terraform";
      case RAW -> "raw";
    };
  }

  private static String stripFormatPrefix(String format, String key) {
    if (key == null) return null;
    String prefix = format + ".";
    return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
  }

  private static String firstValue(String[] values) {
    if (values == null || values.length == 0) return null;
    return values[0];
  }

  private static MultipartFile firstFile(List<MultipartFile> values) {
    if (values == null) return null;
    for (MultipartFile file : values) {
      if (file != null && !file.isEmpty()) return file;
    }
    return null;
  }

  private static int assetSort(String key) {
    if ("asset".equals(key)) return 0;
    if (key != null && key.startsWith("asset")) {
      try {
        return Integer.parseInt(key.substring("asset".length()));
      } catch (NumberFormatException ignored) {
      }
    }
    return 1000;
  }

  private static String requireField(Map<String, String> fields, String name) {
    String value = blankToNull(fields.get(name));
    if (value == null) {
      throw new UploadValidationException("Upload is missing field: " + name);
    }
    return value;
  }

  private static String requireAssetField(AssetUpload asset, String name) {
    String value = blankToNull(asset.fields().get(name));
    if (value == null) {
      throw new UploadValidationException("Upload asset is missing field: " + asset.key() + "." + name);
    }
    return value;
  }

  private static AssetUpload singleAsset(NormalizedUpload upload, String label) {
    if (upload.assets().size() != 1) {
      throw new UploadValidationException(label + " upload requires exactly one asset");
    }
    return upload.assets().getFirst();
  }

  private void ensureNoExistingAssets(RepositoryRuntime runtime, List<String> paths) {
    Set<String> seen = new LinkedHashSet<>();
    for (String path : paths) {
      if (!seen.add(path)) {
        throw new UploadValidationException("Upload contains duplicate asset path: " + path);
      }
      if (assetDao.findAssetByPath(runtime.id(), path).isPresent()) {
        throw new UploadValidationException("Asset already exists: " + path);
      }
    }
  }

  private static String normalizeExtension(String extension) {
    String result = extension.trim();
    while (result.startsWith(".")) result = result.substring(1);
    if (result.isBlank() || result.contains("/") || result.contains("\\")) {
      throw new UploadValidationException("Invalid Maven asset extension: " + extension);
    }
    return result;
  }

  private static void validateMavenSegment(String field, String value) {
    if (value == null || value.isBlank()
        || value.indexOf('\0') >= 0
        || value.contains("/")
        || value.contains("\\")
        || value.equals(".")
        || value.equals("..")
        || value.contains("..")) {
      throw new UploadValidationException("Invalid Maven " + field + ": " + value);
    }
  }

  private static String mavenPath(
      String groupId,
      String artifactId,
      String version,
      String classifier,
      String extension) {
    String filename = artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + extension;
    return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + filename;
  }

  private static String contentType(String extension) {
    return switch (extension) {
      case "pom", "xml" -> MediaType.APPLICATION_XML_VALUE;
      case "jar" -> "application/java-archive";
      case "asc", "sha1", "sha256", "sha512", "md5" -> MediaType.TEXT_PLAIN_VALUE;
      default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
    };
  }

  private static boolean truthy(String value) {
    if (value == null) return false;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true", "1", "yes", "on" -> true;
      default -> false;
    };
  }

  private static String generatedPom(String groupId, String artifactId, String version, String packaging) {
    return """
        <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>%s
        </project>
        """.formatted(
        xml(groupId),
        xml(artifactId),
        xml(version),
        packaging == null ? "" : "\n  <packaging>" + xml(packaging) + "</packaging>");
  }

  private static String xml(String value) {
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String originalFilename(MultipartFile file) {
    String name = file.getOriginalFilename();
    if (name == null || name.isBlank()) return "asset";
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return slash < 0 ? name : name.substring(slash + 1);
  }

  private static String rawPath(String directory, String filename) {
    String dir = directory == null ? "" : directory.trim().replaceAll("/+", "/");
    String file = filename == null ? "" : filename.trim().replaceAll("/+", "/");
    while (dir.startsWith("/")) dir = dir.substring(1);
    while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
    while (file.startsWith("/")) file = file.substring(1);
    while (file.endsWith("/")) file = file.substring(0, file.length() - 1);
    if (file.isBlank()) {
      throw new UploadValidationException("Raw upload filename is required");
    }
    String path = dir.isBlank() ? file : dir + "/" + file;
    if (hasParentSegment(path)) {
      throw new UploadValidationException("Raw upload path cannot contain '..'");
    }
    return path;
  }

  private static boolean hasParentSegment(String path) {
    for (String segment : path.split("/")) {
      if ("..".equals(segment)) return true;
    }
    return false;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record UploadResult(List<String> paths) {
    public String searchTerm() {
      if (paths == null || paths.isEmpty()) return "";
      String prefix = paths.getFirst();
      for (String path : paths) {
        int max = Math.min(prefix.length(), path.length());
        int i = 0;
        while (i < max && prefix.charAt(i) == path.charAt(i)) i++;
        prefix = prefix.substring(0, i);
        if (prefix.isEmpty()) return "";
      }
      return prefix;
    }
  }

  private record NormalizedUpload(Map<String, String> fields, List<AssetUpload> assets) {}

  private record AssetUpload(String key, MultipartFile file, Map<String, String> fields) {}

  private record MavenUploadItem(String path, String contentType, MultipartFile file, byte[] body) {}
}
