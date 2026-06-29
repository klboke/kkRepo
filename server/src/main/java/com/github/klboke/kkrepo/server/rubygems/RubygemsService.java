package com.github.klboke.kkrepo.server.rubygems;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RubygemsService {
  private static final String GEM_CONTENT_TYPE = "application/octet-stream";
  private static final String TEXT_CONTENT_TYPE = "text/plain";
  private static final String SPECS_PATH = "specs.4.8.gz";
  private static final String LATEST_SPECS_PATH = "latest_specs.4.8.gz";
  private static final String PRERELEASE_SPECS_PATH = "prerelease_specs.4.8.gz";
  private static final String VERSIONS_PATH = "versions";
  private static final String NAMES_PATH = "names";

  private final RawHostedService hosted;
  private final RawProxyService proxy;
  private final RawGroupService group;
  private final AssetDao assetDao;
  private final RepositoryIndexRebuildDao indexRebuildDao;

  @Autowired
  public RubygemsService(
      RawHostedService hosted,
      RawProxyService proxy,
      RawGroupService group,
      AssetDao assetDao,
      RepositoryIndexRebuildDao indexRebuildDao) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.assetDao = assetDao;
    this.indexRebuildDao = indexRebuildDao;
  }

  RubygemsService(
      RawHostedService hosted,
      RawProxyService proxy,
      RawGroupService group,
      AssetDao assetDao) {
    this(hosted, proxy, group, assetDao, null);
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    String fullPath = normalize(rawPath);
    String path = pathOnly(fullPath);
    if (runtime.type() == RepositoryType.PROXY) {
      return proxyGet(runtime, fullPath, headOnly);
    }
    return switch (path) {
      case "", SPECS_PATH -> specs(runtime, SpecMode.ALL, headOnly);
      case LATEST_SPECS_PATH -> specs(runtime, SpecMode.LATEST, headOnly);
      case PRERELEASE_SPECS_PATH -> specs(runtime, SpecMode.PRERELEASE, headOnly);
      case VERSIONS_PATH -> compactVersions(runtime, headOnly);
      case NAMES_PATH -> compactNames(runtime, headOnly);
      case "api/v1/dependencies" -> dependencyApi(runtime, fullPath, headOnly);
      default -> {
        if (path.startsWith("info/")) {
          yield compactInfo(runtime, path.substring("info/".length()), headOnly);
        }
        if (path.startsWith("quick/Marshal.4.8/") && path.endsWith(".gemspec.rz")) {
          yield quickMarshal(runtime, path, headOnly);
        }
        yield dispatchRawGet(runtime, path, headOnly);
      }
    };
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp) {
    String path = pathOnly(normalize(rawPath));
    if (!path.isEmpty() && !path.equals("api/v1/gems")) {
      throw new MavenExceptions.MethodNotAllowed("RubyGems push requires POST /api/v1/gems");
    }
    return push(runtime, body, contentType, createdBy, createdByIp);
  }

  public MavenResponse push(
      RepositoryRuntime runtime,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    Path temp = null;
    try {
      temp = Files.createTempFile("kkrepo-rubygems-", ".gem");
      try (OutputStream out = Files.newOutputStream(temp)) {
        body.transferTo(out);
      }
      GemMetadata metadata = readGemMetadata(temp);
      String gemPath = gemPath(metadata);
      Map<String, String> attrs = new LinkedHashMap<>();
      attrs.put("rubygems.name", metadata.name());
      attrs.put("rubygems.version", metadata.version());
      attrs.put("rubygems.platform", metadata.platform());
      if (!metadata.summary().isBlank()) {
        attrs.put("rubygems.summary", metadata.summary());
      }
      if (!metadata.authors().isEmpty()) {
        attrs.put("rubygems.authors", authorsAttr(metadata.authors()));
      }
      if (!metadata.dependencies().isEmpty()) {
        attrs.put("rubygems.dependencies", dependenciesAttr(metadata.dependencies()));
      }
      try (InputStream storedBody = Files.newInputStream(temp)) {
        MavenResponse response = hosted.putWithAttributes(runtime, gemPath, storedBody,
            blankToDefault(contentType, GEM_CONTENT_TYPE), attrs, createdBy, createdByIp);
        refreshGeneratedMetadataAfterGemChange(runtime, metadata);
        return response;
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to store RubyGems package", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // best effort cleanup
        }
      }
    }
  }

  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    ensureHosted(runtime);
    String path = normalize(rawPath);
    if (path.startsWith("api/v1/gems/yank")) {
      GemIdentity identity = yankIdentity(path);
      if (identity == null) {
        throw new MavenExceptions.MethodNotAllowed("RubyGems yank requires gem_name and version");
      }
      MavenResponse response = hosted.delete(runtime, gemPath(identity.name(), identity.version(), identity.platform()));
      refreshGeneratedMetadataAfterDelete(runtime, response, identity);
      return response;
    }
    GemIdentity identity = path.endsWith(".gem")
        ? gemFromPath(path).map(gem -> new GemIdentity(gem.name(), gem.version(), gem.platform())).orElse(null)
        : null;
    MavenResponse response = hosted.delete(runtime, path);
    refreshGeneratedMetadataAfterDelete(runtime, response, identity);
    return response;
  }

  private MavenResponse specs(RepositoryRuntime runtime, SpecMode mode, boolean headOnly) {
    if (runtime.isHosted()) {
      String path = specsPath(mode);
      Optional<MavenResponse> stored = storedGenerated(runtime, path, headOnly);
      if (stored.isPresent()) return stored.get();
    }
    List<GemMetadata> gems = gems(runtime);
    if (mode == SpecMode.PRERELEASE) {
      gems = gems.stream().filter(gem -> gem.version().contains("-")).toList();
    } else if (mode == SpecMode.LATEST) {
      Map<String, GemMetadata> latest = new TreeMap<>();
      for (GemMetadata gem : gems) {
        if (gem.version().contains("-")) continue;
        String key = gem.name() + "\n" + gem.platform();
        GemMetadata current = latest.get(key);
        if (current == null || compareGemVersions(gem.version(), current.version()) > 0) {
          latest.put(key, gem);
        }
      }
      gems = new ArrayList<>(latest.values());
    }
    byte[] response = gzip(rubyMarshalSpecs(gems));
    writeGeneratedIfHosted(runtime, specsPath(mode), response, GEM_CONTENT_TYPE);
    return bytes(response, GEM_CONTENT_TYPE, headOnly);
  }

  private MavenResponse compactVersions(RepositoryRuntime runtime, boolean headOnly) {
    if (runtime.isHosted()) {
      Optional<MavenResponse> stored = storedGenerated(runtime, VERSIONS_PATH, headOnly);
      if (stored.isPresent()) return stored.get();
    }
    byte[] response = compactVersionsBody(gemsByName(runtime)).getBytes(StandardCharsets.UTF_8);
    writeGeneratedIfHosted(runtime, VERSIONS_PATH, response, TEXT_CONTENT_TYPE);
    return bytes(response, TEXT_CONTENT_TYPE, headOnly);
  }

  private String compactVersionsBody(Map<String, List<GemMetadata>> byName) {
    StringBuilder body = new StringBuilder();
    body.append("created_at: ").append(System.currentTimeMillis() / 1000L).append('\n');
    body.append("---\n");
    for (Map.Entry<String, List<GemMetadata>> entry : byName.entrySet()) {
      body.append(entry.getKey()).append(' ');
      String info = compactInfoBody(entry.getValue());
      for (int i = 0; i < entry.getValue().size(); i++) {
        if (i > 0) body.append(',');
        GemMetadata gem = entry.getValue().get(i);
        body.append(gem.version());
        if (!"ruby".equals(gem.platform())) {
          body.append('-').append(gem.platform());
        }
      }
      body.append(' ').append(md5(info.getBytes(StandardCharsets.UTF_8))).append('\n');
    }
    return body.toString();
  }

  private MavenResponse compactNames(RepositoryRuntime runtime, boolean headOnly) {
    if (runtime.isHosted()) {
      Optional<MavenResponse> stored = storedGenerated(runtime, NAMES_PATH, headOnly);
      if (stored.isPresent()) return stored.get();
    }
    StringBuilder body = new StringBuilder();
    for (String name : gemsByName(runtime).keySet()) {
      body.append(name).append('\n');
    }
    byte[] response = body.toString().getBytes(StandardCharsets.UTF_8);
    writeGeneratedIfHosted(runtime, NAMES_PATH, response, TEXT_CONTENT_TYPE);
    return bytes(response, TEXT_CONTENT_TYPE, headOnly);
  }

  private MavenResponse compactInfo(RepositoryRuntime runtime, String name, boolean headOnly) {
    String decoded = decode(name);
    String path = infoPath(decoded);
    if (runtime.isHosted()) {
      Optional<MavenResponse> stored = storedGenerated(runtime, path, headOnly);
      if (stored.isPresent()) return stored.get();
    }
    List<GemMetadata> gems = gemsByExactName(runtime, decoded);
    String body = compactInfoBody(gems);
    if (!gems.isEmpty()) {
      writeGeneratedIfHosted(runtime, path, body.getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE);
    }
    return text(body, headOnly);
  }

  private static String compactInfoBody(List<GemMetadata> gems) {
    StringBuilder body = new StringBuilder();
    body.append("---\n");
    for (GemMetadata gem : gems) {
      body.append(gem.version());
      if (!"ruby".equals(gem.platform())) {
        body.append('-').append(gem.platform());
      }
      if (!gem.dependencies().isEmpty()) {
        body.append(' ').append(compactDependencies(gem.dependencies()));
      }
      if (gem.sha256() != null && !gem.sha256().isBlank()) {
        if (gem.dependencies().isEmpty()) {
          body.append(' ');
        }
        body.append("|checksum:").append(gem.sha256());
      }
      body.append('\n');
    }
    return body.toString();
  }

  private MavenResponse dependencyApi(RepositoryRuntime runtime, String fullPath, boolean headOnly) {
    Set<String> requested = requestedGemNames(fullPath);
    List<GemMetadata> gems = requested.isEmpty()
        ? gems(runtime)
        : gemsByExactNames(runtime, requested);
    ByteArrayOutputStream marshal = new ByteArrayOutputStream();
    marshal.writeBytes(new byte[] {4, 8, '['});
    writeFixnum(marshal, gems.size());
    for (GemMetadata gem : gems) {
      writeHash(marshal, Map.of(
          "name", gem.name(),
          "number", gem.version(),
          "platform", gem.platform(),
          "dependencies", dependencyApiDependencies(gem.dependencies())));
    }
    return bytes(marshal.toByteArray(), "application/octet-stream", headOnly);
  }

  private MavenResponse quickMarshal(RepositoryRuntime runtime, String path, boolean headOnly) {
    if (runtime.isHosted()) {
      Optional<MavenResponse> stored = storedGenerated(runtime, path, headOnly);
      if (stored.isPresent()) return stored.get();
    }
    String filename = path.substring("quick/Marshal.4.8/".length(),
        path.length() - ".gemspec.rz".length());
    GemMetadata gem = gemForFullName(runtime, filename)
        .orElseGet(() -> gemFromPath("gems/" + filename + ".gem")
            .orElse(new GemMetadata(filename, "0", "ruby", List.of())));
    byte[] response = deflateLikeRuby(rubyMarshalGemSpecification(gem));
    writeGeneratedIfHosted(runtime, path, response, GEM_CONTENT_TYPE);
    return bytes(response, GEM_CONTENT_TYPE, headOnly);
  }

  private Optional<GemMetadata> gemForFullName(RepositoryRuntime runtime, String fullName) {
    if (runtime.type() == RepositoryType.GROUP) {
      for (RepositoryRuntime member : runtime.members()) {
        Optional<GemMetadata> gem = gemForFullName(member, fullName);
        if (gem.isPresent()) return gem;
      }
      return Optional.empty();
    }
    return gemFromAssetPath(runtime, "gems/" + fullName + ".gem")
        .filter(gem -> fullName(gem).equals(fullName));
  }

  private MavenResponse dispatchRawGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.get(runtime, rawPath, headOnly);
      case PROXY -> proxyGet(runtime, rawPath, headOnly);
      case GROUP -> group.get(runtime, rawPath, headOnly);
    };
  }

  private MavenResponse proxyGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return proxy.getAssetFromUrl(runtime, rawPath, remoteUrlForPath(runtime, rawPath), headOnly);
  }

  private Optional<MavenResponse> storedGenerated(RepositoryRuntime runtime, String path, boolean headOnly) {
    if (hosted == null || runtime == null || !runtime.isHosted()) {
      return Optional.empty();
    }
    try {
      return Optional.of(hosted.get(runtime, path, headOnly));
    } catch (MavenExceptions.MavenNotFoundException ignored) {
      return Optional.empty();
    }
  }

  private void writeGeneratedIfHosted(RepositoryRuntime runtime, String path, byte[] bytes, String contentType) {
    if (hosted == null || runtime == null || !runtime.isHosted()) {
      return;
    }
    hosted.putGenerated(runtime, path, new ByteArrayInputStream(bytes), contentType, "system", "metadata");
  }

  private void refreshGeneratedMetadataAfterGemChange(RepositoryRuntime runtime, GemMetadata changed) {
    if (hosted == null || assetDao == null || runtime == null || !runtime.isHosted()) {
      return;
    }
    if (changed != null) {
      writeInfo(runtime, changed.name());
      writeQuickSpec(runtime, changed);
    }
    rebuildGeneratedMetadata(runtime);
    enqueueGeneratedMetadataRebuild(runtime);
  }

  private void refreshGeneratedMetadataAfterDelete(
      RepositoryRuntime runtime,
      MavenResponse response,
      GemIdentity identity) {
    if (response == null || response.status() != 204
        || hosted == null || assetDao == null || runtime == null || !runtime.isHosted()) {
      return;
    }
    String name = identity == null ? null : identity.name();
    if (name != null && !name.isBlank()) {
      writeInfo(runtime, name);
    }
    if (identity != null) {
      hosted.delete(runtime, quickSpecPath(fullName(identity)));
    }
    rebuildGeneratedMetadata(runtime);
    enqueueGeneratedMetadataRebuild(runtime);
  }

  public void rebuildGeneratedMetadata(RepositoryRuntime runtime) {
    List<GemMetadata> gems = gems(runtime);
    writeGeneratedIfHosted(runtime, SPECS_PATH, gzip(rubyMarshalSpecs(gems)), GEM_CONTENT_TYPE);
    writeGeneratedIfHosted(runtime, LATEST_SPECS_PATH,
        gzip(rubyMarshalSpecs(latestSpecs(gems))), GEM_CONTENT_TYPE);
    writeGeneratedIfHosted(runtime, PRERELEASE_SPECS_PATH,
        gzip(rubyMarshalSpecs(gems.stream().filter(gem -> gem.version().contains("-")).toList())),
        GEM_CONTENT_TYPE);
    Map<String, List<GemMetadata>> byName = byName(gems);
    writeGeneratedIfHosted(runtime, VERSIONS_PATH,
        compactVersionsBody(byName).getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE);
    StringBuilder names = new StringBuilder();
    byName.keySet().forEach(name -> names.append(name).append('\n'));
    writeGeneratedIfHosted(runtime, NAMES_PATH, names.toString().getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE);
  }

  private void enqueueGeneratedMetadataRebuild(RepositoryRuntime runtime) {
    if (indexRebuildDao != null) {
      indexRebuildDao.enqueue(runtime.id(), RepositoryIndexRebuildDao.RUBYGEMS_METADATA);
    }
  }

  private void writeInfo(RepositoryRuntime runtime, String name) {
    List<GemMetadata> named = gemsByExactName(runtime, name);
    if (named.isEmpty()) {
      hosted.delete(runtime, infoPath(name));
    } else {
      writeGeneratedIfHosted(runtime, infoPath(name),
          compactInfoBody(named).getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE);
    }
  }

  private void writeQuickSpec(RepositoryRuntime runtime, GemMetadata gem) {
    writeGeneratedIfHosted(runtime, quickSpecPath(fullName(gem)),
        deflateLikeRuby(rubyMarshalGemSpecification(gem)), GEM_CONTENT_TYPE);
  }

  private List<GemMetadata> gems(RepositoryRuntime runtime) {
    return switch (runtime.type()) {
      case HOSTED, PROXY -> localGems(runtime);
      case GROUP -> {
        List<GemMetadata> merged = new ArrayList<>();
        for (RepositoryRuntime member : runtime.members()) {
          try {
            merged.addAll(gems(member));
          } catch (MavenExceptions.MavenNotFoundException ignored) {
            // skip empty member
          }
        }
        yield deduplicate(merged);
      }
    };
  }

  private List<GemMetadata> localGems(RepositoryRuntime runtime) {
    return localGems(runtime, "gems/");
  }

  private List<GemMetadata> localGems(RepositoryRuntime runtime, String prefix) {
    List<GemMetadata> gems = new ArrayList<>();
    List<AssetRecord> assets = assetDao.listAssetsByPrefix(runtime.id(), prefix);
    Map<Long, AssetBlobRecord> blobs = blobsByAssetId(assets);
    for (AssetRecord asset : assets) {
      if (!asset.path().endsWith(".gem")) continue;
      Optional<GemMetadata> fromAsset = gemFromAsset(asset, blobs.get(asset.assetBlobId()));
      if (fromAsset.isPresent()) {
        gems.add(fromAsset.get());
      } else {
        gemFromPath(asset.path()).ifPresent(gems::add);
      }
    }
    return sorted(deduplicate(gems));
  }

  private Map<Long, AssetBlobRecord> blobsByAssetId(List<AssetRecord> assets) {
    List<Long> blobIds = assets.stream()
        .map(AssetRecord::assetBlobId)
        .filter(id -> id != null)
        .toList();
    return assetDao.findBlobsByIds(blobIds);
  }

  private Optional<GemMetadata> gemFromAsset(AssetRecord asset) {
    if (asset.assetBlobId() == null) return Optional.empty();
    return gemFromAsset(asset, assetDao.findBlobById(asset.assetBlobId()).orElse(null));
  }

  private Optional<GemMetadata> gemFromAsset(AssetRecord asset, AssetBlobRecord blob) {
    if (blob == null) return Optional.empty();
    Map<String, Object> attrs = blob.attributes();
    String name = stringAttr(attrs, "rubygems.name");
    String version = stringAttr(attrs, "rubygems.version");
    if (name == null || version == null) {
      return gemFromPath(asset.path()).map(gem -> gem.withSha256(blob.sha256()));
    }
    String platform = stringAttr(attrs, "rubygems.platform");
    return Optional.of(new GemMetadata(
        name,
        version,
        platform == null || platform.isBlank() ? "ruby" : platform,
        dependenciesFromAttr(stringAttr(attrs, "rubygems.dependencies")),
        blob.sha256(),
        stringAttr(attrs, "rubygems.summary"),
        authorsFromAttr(stringAttr(attrs, "rubygems.authors"))));
  }

  private Optional<GemMetadata> gemFromAssetPath(RepositoryRuntime runtime, String path) {
    if (assetDao == null) return Optional.empty();
    return assetDao.findAssetByPath(runtime.id(), path).flatMap(this::gemFromAsset);
  }

  private Map<String, List<GemMetadata>> gemsByName(RepositoryRuntime runtime) {
    return byName(gems(runtime));
  }

  private List<GemMetadata> gemsByExactName(RepositoryRuntime runtime, String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    if (runtime.type() == RepositoryType.GROUP) {
      List<GemMetadata> merged = new ArrayList<>();
      for (RepositoryRuntime member : runtime.members()) {
        merged.addAll(gemsByExactName(member, name));
      }
      return sorted(deduplicate(merged));
    }
    return localGems(runtime, "gems/" + name + "-").stream()
        .filter(gem -> name.equals(gem.name()))
        .toList();
  }

  private List<GemMetadata> gemsByExactNames(RepositoryRuntime runtime, Set<String> names) {
    List<GemMetadata> gems = new ArrayList<>();
    for (String name : names) {
      gems.addAll(gemsByExactName(runtime, name));
    }
    return sorted(deduplicate(gems));
  }

  private static List<GemMetadata> sorted(List<GemMetadata> gems) {
    return gems.stream()
        .sorted(Comparator.comparing(GemMetadata::name)
            .thenComparing(GemMetadata::version, RubygemsService::compareGemVersions)
            .thenComparing(GemMetadata::platform))
        .toList();
  }

  private static Map<String, List<GemMetadata>> byName(List<GemMetadata> gems) {
    Map<String, List<GemMetadata>> byName = new TreeMap<>();
    for (GemMetadata gem : gems) {
      byName.computeIfAbsent(gem.name(), ignored -> new ArrayList<>()).add(gem);
    }
    byName.replaceAll((ignored, list) -> sorted(list));
    return byName;
  }

  private static List<GemMetadata> latestSpecs(List<GemMetadata> gems) {
    Map<String, GemMetadata> latest = new TreeMap<>();
    for (GemMetadata gem : gems) {
      if (gem.version().contains("-")) continue;
      String key = gem.name() + "\n" + gem.platform();
      GemMetadata current = latest.get(key);
      if (current == null || compareGemVersions(gem.version(), current.version()) > 0) {
        latest.put(key, gem);
      }
    }
    return new ArrayList<>(latest.values());
  }

  private static List<GemMetadata> deduplicate(List<GemMetadata> gems) {
    Map<String, GemMetadata> byCoordinate = new LinkedHashMap<>();
    for (GemMetadata gem : gems) {
      byCoordinate.put(gem.name() + "\n" + gem.version() + "\n" + gem.platform(), gem);
    }
    return new ArrayList<>(byCoordinate.values());
  }

  private static Optional<GemMetadata> gemFromPath(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (!file.endsWith(".gem")) return Optional.empty();
    file = file.substring(0, file.length() - ".gem".length());
    int versionStart = -1;
    for (int i = 0; i < file.length() - 1; i++) {
      if (file.charAt(i) == '-' && Character.isDigit(file.charAt(i + 1))) {
        versionStart = i;
      }
    }
    if (versionStart <= 0) return Optional.empty();
    String name = file.substring(0, versionStart);
    String versionAndPlatform = file.substring(versionStart + 1);
    String platform = "ruby";
    String version = versionAndPlatform;
    int platformStart = versionAndPlatform.indexOf('-', firstNonVersionChar(versionAndPlatform));
    if (platformStart > 0) {
      version = versionAndPlatform.substring(0, platformStart);
      platform = versionAndPlatform.substring(platformStart + 1);
    }
    return Optional.of(new GemMetadata(name, version, platform, List.of()));
  }

  private static int firstNonVersionChar(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!(Character.isDigit(c) || c == '.')) return i;
    }
    return value.length();
  }

  private static GemIdentity yankIdentity(String path) {
    int queryIndex = path.indexOf('?');
    if (queryIndex < 0 || queryIndex == path.length() - 1) return null;
    Map<String, String> query = query(path.substring(queryIndex + 1));
    String name = firstNonBlank(query.get("gem_name"), query.get("name"));
    String version = query.get("version");
    String platform = firstNonBlank(query.get("platform"), "ruby");
    return name == null || version == null ? null : new GemIdentity(name, version, platform);
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      result.put(decode(key), decode(value));
    }
    return result;
  }

  static Set<String> requestedGemNames(String fullPath) {
    int queryIndex = fullPath.indexOf('?');
    if (queryIndex < 0 || queryIndex == fullPath.length() - 1) return Set.of();
    String raw = query(fullPath.substring(queryIndex + 1)).get("gems");
    if (raw == null || raw.isBlank()) return Set.of();
    Set<String> names = new LinkedHashSet<>();
    for (String name : raw.split(",")) {
      String decoded = decode(name).trim();
      if (!decoded.isBlank()) names.add(decoded);
    }
    return names;
  }

  static GemMetadata readGemMetadata(Path gem) throws IOException {
    try (InputStream raw = Files.newInputStream(gem);
         TarArchiveInputStream tar = new TarArchiveInputStream(raw)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (!entry.isDirectory() && "metadata.gz".equals(entry.getName())) {
          try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(tar)) {
            return parseMetadataYaml(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
          }
        }
      }
    }
    throw new MavenExceptions.MethodNotAllowed("RubyGems package is missing metadata.gz");
  }

  static GemMetadata parseMetadataYaml(String yaml) {
    String name = null;
    String version = null;
    String platform = "ruby";
    String summary = "";
    List<String> authors = new ArrayList<>();
    List<GemDependency> dependencies = new ArrayList<>();
    String dependencyName = null;
    List<String> dependencyRequirements = List.of();
    String pendingRequirementOperator = null;
    boolean inDependencies = false;
    boolean inRequirements = false;
    boolean inAuthors = false;
    for (String rawLine : yaml.split("\\R")) {
      String line = rawLine.stripTrailing();
      String trimmed = line.trim();
      if (trimmed.equals("dependencies:")) {
        inDependencies = true;
        inAuthors = false;
        continue;
      }
      if (!inDependencies) {
        if (trimmed.equals("authors:")) {
          inAuthors = true;
          continue;
        }
        if (inAuthors && trimmed.startsWith("-")) {
          String author = cleanYaml(trimmed.substring(1));
          if (!author.isBlank()) authors.add(author);
          continue;
        }
        if (!trimmed.startsWith("-") && trimmed.contains(":")) {
          inAuthors = false;
        }
        if (trimmed.startsWith("name:")) name = cleanYaml(trimmed.substring("name:".length()));
        if (version == null && trimmed.startsWith("version:")
            && !trimmed.contains("!ruby/object:Gem::Version")) {
          version = cleanYaml(trimmed.substring("version:".length()));
        }
        if (trimmed.startsWith("platform:")) platform = cleanYaml(trimmed.substring("platform:".length()));
        if (trimmed.startsWith("summary:")) summary = cleanYaml(trimmed.substring("summary:".length()));
        continue;
      }
      if (trimmed.startsWith("- !ruby/object:Gem::Dependency")) {
        if (dependencyName != null) dependencies.add(new GemDependency(dependencyName, dependencyRequirements));
        dependencyName = null;
        dependencyRequirements = List.of();
        pendingRequirementOperator = null;
        inRequirements = false;
        continue;
      }
      if (trimmed.startsWith("name:")) {
        dependencyName = cleanYaml(trimmed.substring("name:".length()));
        continue;
      }
      if (trimmed.startsWith("requirements:")) {
        inRequirements = true;
        dependencyRequirements = new ArrayList<>();
        pendingRequirementOperator = null;
        continue;
      }
      if (inRequirements && trimmed.startsWith("- -")) {
        pendingRequirementOperator = cleanYaml(trimmed.substring(3));
        continue;
      }
      if (inRequirements && pendingRequirementOperator != null && trimmed.startsWith("version:")) {
        String versionRequirement = cleanYaml(trimmed.substring("version:".length()));
        if (!versionRequirement.isBlank()) {
          ((ArrayList<String>) dependencyRequirements).add(
              (pendingRequirementOperator + " " + versionRequirement).trim());
        }
        pendingRequirementOperator = null;
        continue;
      }
      if (inRequirements && trimmed.startsWith("-") && !trimmed.contains("!ruby/object")) {
        String requirement = cleanYaml(trimmed.substring(1));
        if (!requirement.isBlank()) ((ArrayList<String>) dependencyRequirements).add(requirement);
      }
      if (version == null && trimmed.startsWith("version:")) {
        version = cleanYaml(trimmed.substring("version:".length()));
      }
    }
    if (dependencyName != null) dependencies.add(new GemDependency(dependencyName, dependencyRequirements));
    if (version == null) {
      version = parseNestedVersion(yaml);
    }
    if (name == null || version == null) {
      throw new MavenExceptions.MethodNotAllowed("Invalid RubyGems metadata");
    }
    if (platform == null || platform.isBlank() || "ruby".equalsIgnoreCase(platform)) {
      platform = "ruby";
    }
    return new GemMetadata(name, version, platform, dependencies.stream()
        .filter(dep -> dep.name() != null && !dep.name().isBlank())
        .toList(), "", summary, authors);
  }

  private static String parseNestedVersion(String yaml) {
    boolean inVersion = false;
    for (String rawLine : yaml.split("\\R")) {
      String trimmed = rawLine.trim();
      if (trimmed.equals("version: !ruby/object:Gem::Version")) {
        inVersion = true;
        continue;
      }
      if (inVersion && trimmed.startsWith("version:")) {
        return cleanYaml(trimmed.substring("version:".length()));
      }
      if (inVersion && !trimmed.isBlank() && !trimmed.startsWith("version:")) {
        inVersion = false;
      }
    }
    return null;
  }

  static byte[] rubyMarshalSpecs(List<GemMetadata> gems) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {4, 8, '['});
    writeFixnum(out, gems.size());
    for (GemMetadata gem : gems) {
      out.write('[');
      writeFixnum(out, 3);
      writeRubyString(out, gem.name());
      writeGemVersion(out, gem.version());
      writeRubyString(out, gem.platform());
    }
    return out.toByteArray();
  }

  private static byte[] rubyMarshalGemSpecification(GemMetadata gem) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {4, 8});
    out.write('u');
    writeSymbol(out, "Gem::Specification");
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(new byte[] {4, 8, '['});
    writeFixnum(payload, 19);
    writeRubyString(payload, "3.0.6");
    writeRubyInteger(payload, 4);
    writeRubyString(payload, gem.name());
    writeGemVersion(payload, gem.version());
    writeRubyTime(payload);
    writeRubyString(payload, gem.effectiveSummary());
    writeGemRequirement(payload, ">= 0");
    writeGemRequirement(payload, ">= 0");
    writeRubyString(payload, gem.platform());
    payload.write('[');
    writeFixnum(payload, gem.dependencies().size());
    for (GemDependency dependency : gem.dependencies()) {
      writeGemDependency(payload, dependency);
    }
    writeRubyString(payload, "");
    writeRubyNil(payload);
    writeRubyStringArray(payload, gem.authors());
    writeRubyNil(payload);
    writeRubyNil(payload);
    payload.write('T');
    writeRubyString(payload, gem.platform());
    payload.write('[');
    writeFixnum(payload, 0);
    payload.write('{');
    writeFixnum(payload, 0);
    byte[] payloadBytes = payload.toByteArray();
    writeFixnum(out, payloadBytes.length);
    out.writeBytes(payloadBytes);
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private static void writeObject(ByteArrayOutputStream out, Object value) {
    if (value instanceof String s) {
      writeRubyString(out, s);
    } else if (value instanceof List<?> list) {
      out.write('[');
      writeFixnum(out, list.size());
      for (Object item : list) {
        if (item instanceof GemDependency dep) {
          out.write('[');
          writeFixnum(out, dep.requirements().size() + 1);
          writeRubyString(out, dep.name());
          for (String requirement : dep.requirements()) {
            writeRubyString(out, requirement);
          }
        } else if (item instanceof List<?> nested) {
          writeObject(out, nested);
        } else {
          writeObject(out, String.valueOf(item));
        }
      }
    } else if (value instanceof Map<?, ?> map) {
      writeHash(out, (Map<String, Object>) map);
    } else {
      writeRubyString(out, String.valueOf(value));
    }
  }

  private static void writeHash(ByteArrayOutputStream out, Map<String, Object> map) {
    out.write('{');
    writeFixnum(out, map.size());
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      writeSymbol(out, entry.getKey());
      writeObject(out, entry.getValue());
    }
  }

  private static void writeGemVersion(ByteArrayOutputStream out, String version) {
    out.write('U');
    writeSymbol(out, "Gem::Version");
    out.write('[');
    writeFixnum(out, 1);
    writeRubyString(out, version);
  }

  private static void writeGemRequirement(ByteArrayOutputStream out, String requirement) {
    writeGemRequirement(out, List.of(requirement));
  }

  private static void writeGemRequirement(ByteArrayOutputStream out, List<String> requirements) {
    out.write('U');
    writeSymbol(out, "Gem::Requirement");
    List<String> effective = requirements == null || requirements.isEmpty() ? List.of(">= 0") : requirements;
    out.write('[');
    writeFixnum(out, 1);
    out.write('[');
    writeFixnum(out, effective.size());
    for (String requirement : effective) {
      String[] parts = splitRequirement(requirement);
      out.write('[');
      writeFixnum(out, 2);
      writeRubyString(out, parts[0]);
      writeGemVersion(out, parts[1]);
    }
  }

  private static void writeGemDependency(ByteArrayOutputStream out, GemDependency dependency) {
    out.write('o');
    writeSymbol(out, "Gem::Dependency");
    writeFixnum(out, 5);
    writeSymbol(out, "@name");
    writeRubyString(out, dependency.name());
    writeSymbol(out, "@requirement");
    writeGemRequirement(out, dependency.requirements());
    writeSymbol(out, "@type");
    writeSymbol(out, "runtime");
    writeSymbol(out, "@prerelease");
    out.write('F');
    writeSymbol(out, "@version_requirements");
    writeGemRequirement(out, dependency.requirements());
  }

  private static String[] splitRequirement(String requirement) {
    String value = requirement == null || requirement.isBlank() ? ">= 0" : requirement.trim();
    int versionStart = 0;
    while (versionStart < value.length() && !Character.isDigit(value.charAt(versionStart))) {
      versionStart++;
    }
    if (versionStart <= 0 || versionStart >= value.length()) {
      return new String[] {">=", "0"};
    }
    String operator = value.substring(0, versionStart).trim();
    String version = value.substring(versionStart).trim();
    if (operator.isBlank() || version.isBlank()) {
      return new String[] {">=", "0"};
    }
    return new String[] {operator, version};
  }

  private static void writeRubyTime(ByteArrayOutputStream out) {
    out.write('I');
    out.write('u');
    writeSymbol(out, "Time");
    out.writeBytes(new byte[] {
        13, 32, (byte) 0x95, 31, (byte) 0xc0, 0, 0, 0, 0, 6
    });
    writeSymbol(out, "zone");
    writeRubyString(out, "UTC");
  }

  private static void writeRubyString(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.write('I');
    out.write('"');
    writeFixnum(out, bytes.length);
    out.writeBytes(bytes);
    writeFixnum(out, 1);
    writeSymbol(out, "E");
    out.write('T');
  }

  private static void writeRubyStringArray(ByteArrayOutputStream out, List<String> values) {
    out.write('[');
    writeFixnum(out, values.size());
    for (String value : values) {
      writeRubyString(out, value);
    }
  }

  private static void writeRubyNil(ByteArrayOutputStream out) {
    out.write('0');
  }

  private static void writeSymbol(ByteArrayOutputStream out, String value) {
    out.write(':');
    writeRubySymbol(out, value);
  }

  private static void writeRubyInteger(ByteArrayOutputStream out, int value) {
    out.write('i');
    writeFixnum(out, value);
  }

  private static void writeRubySymbol(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeFixnum(out, bytes.length);
    out.writeBytes(bytes);
  }

  private static void writeFixnum(ByteArrayOutputStream out, int value) {
    if (value == 0) {
      out.write(0);
    } else if (value > 0 && value < 123) {
      out.write(value + 5);
    } else if (value < 0 && value > -124) {
      out.write((256 + value - 5) & 0xff);
    } else {
      int bytes = value;
      int count = 0;
      while (bytes != 0 && bytes != -1) {
        count++;
        bytes >>= 8;
      }
      out.write(count);
      for (int i = 0; i < count; i++) {
        out.write((value >> (8 * i)) & 0xff);
      }
    }
  }

  private static MavenResponse bytes(byte[] value, String contentType, boolean headOnly) {
    if (headOnly) {
      return MavenResponse.noBody(200, value.length, contentType, null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(value), value.length, contentType, null, null);
  }

  private static MavenResponse text(String value, boolean headOnly) {
    return bytes(value.getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE, headOnly);
  }

  private static byte[] gzip(byte[] payload) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        gzip.write(payload);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to gzip RubyGems index", e);
    }
  }

  private static byte[] deflateLikeRuby(byte[] payload) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
        deflater.write(payload);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deflate RubyGems quick spec", e);
    }
  }

  private static String gemPath(GemMetadata metadata) {
    return gemPath(metadata.name(), metadata.version(), metadata.platform());
  }

  private static String gemPath(String name, String version, String platform) {
    String file = name + "-" + version;
    if (platform != null && !platform.isBlank() && !"ruby".equals(platform)) {
      file += "-" + platform;
    }
    return "gems/" + file + ".gem";
  }

  private static String specsPath(SpecMode mode) {
    return switch (mode) {
      case ALL -> SPECS_PATH;
      case LATEST -> LATEST_SPECS_PATH;
      case PRERELEASE -> PRERELEASE_SPECS_PATH;
    };
  }

  private static String infoPath(String name) {
    return "info/" + name;
  }

  private static String quickSpecPath(String fullName) {
    return "quick/Marshal.4.8/" + fullName + ".gemspec.rz";
  }

  private static String fullName(GemMetadata metadata) {
    String path = gemPath(metadata);
    return path.substring("gems/".length(), path.length() - ".gem".length());
  }

  private static String fullName(GemIdentity identity) {
    String path = gemPath(identity.name(), identity.version(), identity.platform());
    return path.substring("gems/".length(), path.length() - ".gem".length());
  }

  private static String dependenciesAttr(List<GemDependency> dependencies) {
    StringBuilder out = new StringBuilder();
    for (GemDependency dep : dependencies) {
      if (!out.isEmpty()) out.append('\n');
      out.append(dep.name()).append('|');
      for (int i = 0; i < dep.requirements().size(); i++) {
        if (i > 0) out.append(',');
        out.append(dep.requirements().get(i));
      }
    }
    return out.toString();
  }

  private static String authorsAttr(List<String> authors) {
    return String.join("\n", authors);
  }

  private static List<String> authorsFromAttr(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return List.of(raw.split("\\R")).stream()
        .filter(author -> !author.isBlank())
        .toList();
  }

  private static List<GemDependency> dependenciesFromAttr(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<GemDependency> deps = new ArrayList<>();
    for (String line : raw.split("\\R")) {
      int sep = line.indexOf('|');
      if (sep <= 0) continue;
      String name = line.substring(0, sep);
      List<String> requirements = List.of(line.substring(sep + 1).split(",")).stream()
          .filter(s -> !s.isBlank())
          .toList();
      deps.add(new GemDependency(name, requirements));
    }
    return deps;
  }

  private static String compactDependencies(List<GemDependency> dependencies) {
    StringBuilder out = new StringBuilder();
    for (GemDependency dep : dependencies) {
      if (!out.isEmpty()) out.append(',');
      out.append(dep.name());
      if (!dep.requirements().isEmpty()) {
        out.append(':');
        for (int i = 0; i < dep.requirements().size(); i++) {
          if (i > 0) out.append('&');
          out.append(dep.requirements().get(i));
        }
      }
    }
    return out.toString();
  }

  private static Map<String, Object> dependencyApiDependencies(List<GemDependency> dependencies) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (dependencies == null) {
      return out;
    }
    for (GemDependency dep : dependencies) {
      if (dep.name() == null || dep.name().isBlank()) {
        continue;
      }
      out.put(dep.name(), dependencyApiRequirement(dep.requirements()));
    }
    return out;
  }

  private static String dependencyApiRequirement(List<String> requirements) {
    if (requirements == null || requirements.isEmpty()) {
      return ">= 0";
    }
    return String.join(", ", requirements);
  }

  private static int compareGemVersions(String left, String right) {
    List<String> l = versionParts(left);
    List<String> r = versionParts(right);
    int max = Math.max(l.size(), r.size());
    for (int i = 0; i < max; i++) {
      String a = i < l.size() ? l.get(i) : "0";
      String b = i < r.size() ? r.get(i) : "0";
      int cmp;
      if (a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit)) {
        cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
      } else {
        cmp = a.compareToIgnoreCase(b);
      }
      if (cmp != 0) return cmp;
    }
    return 0;
  }

  private static List<String> versionParts(String version) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < version.length(); i++) {
      char c = version.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        current.append(c);
      } else if (!current.isEmpty()) {
        parts.add(current.toString());
        current.setLength(0);
      }
    }
    if (!current.isEmpty()) parts.add(current.toString());
    return parts;
  }

  private static String cleanYaml(String value) {
    String cleaned = value == null ? "" : value.trim();
    while (cleaned.startsWith("-")) cleaned = cleaned.substring(1).trim();
    if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
        || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    }
    return cleaned;
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    Object v = attrs == null ? null : attrs.get(key);
    return v == null ? null : v.toString();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String md5(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing MD5", e);
    }
  }

  private static String normalize(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim().replaceAll("/+", "/");
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
    return path;
  }

  private static String pathOnly(String path) {
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  static String remoteUrlForPath(RepositoryRuntime runtime, String path) {
    String base = runtime.proxyRemoteUrl();
    if (base == null || base.isBlank()) {
      throw new IllegalStateException("RubyGems proxy " + runtime.name() + " has no remote URL configured");
    }
    String pathOnly = path == null ? "" : path;
    String pathQuery = "";
    int pathQueryIndex = pathOnly.indexOf('?');
    if (pathQueryIndex >= 0) {
      pathQuery = pathOnly.substring(pathQueryIndex + 1);
      pathOnly = pathOnly.substring(0, pathQueryIndex);
    }
    String trimmed = base.trim();
    int query = trimmed.indexOf('?');
    String suffix = query < 0 ? "" : trimmed.substring(query);
    if (query >= 0) trimmed = trimmed.substring(0, query);
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed + "/" + encodePath(pathOnly) + querySuffix(pathQuery, suffix);
  }

  private static String encodePath(String path) {
    String[] segments = path.split("/", -1);
    StringBuilder out = new StringBuilder(path.length() + 16);
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) out.append('/');
      out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }

  private static String querySuffix(String pathQuery, String baseQuerySuffix) {
    boolean hasPathQuery = pathQuery != null && !pathQuery.isBlank();
    boolean hasBaseQuery = baseQuerySuffix != null && !baseQuerySuffix.isBlank();
    if (!hasPathQuery) return hasBaseQuery ? baseQuerySuffix : "";
    if (!hasBaseQuery) return "?" + pathQuery;
    return "?" + pathQuery + "&" + baseQuerySuffix.substring(1);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.type() != RepositoryType.HOSTED) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted RubyGems repositories");
    }
  }

  private enum SpecMode {
    ALL,
    LATEST,
    PRERELEASE
  }

  record GemMetadata(
      String name,
      String version,
      String platform,
      List<GemDependency> dependencies,
      String sha256,
      String summary,
      List<String> authors) {
    GemMetadata {
      dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      sha256 = sha256 == null ? "" : sha256;
      summary = summary == null ? "" : summary;
      authors = authors == null ? List.of() : List.copyOf(authors);
    }

    GemMetadata(String name, String version, String platform, List<GemDependency> dependencies) {
      this(name, version, platform, dependencies, "", "", List.of());
    }

    GemMetadata(
        String name,
        String version,
        String platform,
        List<GemDependency> dependencies,
        String sha256) {
      this(name, version, platform, dependencies, sha256, "", List.of());
    }

    GemMetadata withSha256(String sha256) {
      return new GemMetadata(name, version, platform, dependencies, sha256, summary, authors);
    }

    String effectiveSummary() {
      return summary == null || summary.isBlank() ? name : summary;
    }
  }

  record GemDependency(String name, List<String> requirements) {
  }

  private record GemIdentity(String name, String version, String platform) {
  }
}
