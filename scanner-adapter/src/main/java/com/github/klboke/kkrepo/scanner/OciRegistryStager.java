package com.github.klboke.kkrepo.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Materializes an authenticated registry image as a bounded local OCI layout.
 *
 * <p>Syft never receives registry credentials or an unbounded remote stream. Manifest descriptor
 * sizes are preflighted, every transferred blob is byte-counted and digest-verified, and all
 * selected layers share one archive/decompression budget before the scanner process starts.
 */
@Component
public class OciRegistryStager {
  private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
  private static final long MAX_CONFIG_BYTES = 16L * 1024 * 1024;
  private static final int MAX_OCI_DESCRIPTORS = 100_000;
  private static final String ACCEPT_MANIFESTS = String.join(
      ", ",
      "application/vnd.oci.image.index.v1+json",
      "application/vnd.oci.image.manifest.v1+json",
      "application/vnd.docker.distribution.manifest.list.v2+json",
      "application/vnd.docker.distribution.manifest.v2+json");

  private final HttpClient client;
  private final ObjectMapper mapper;
  private final ArchiveGuard archiveGuard;

  @Autowired
  public OciRegistryStager(ObjectMapper mapper, ArchiveGuard archiveGuard) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        mapper,
        archiveGuard);
  }

  OciRegistryStager(
      HttpClient client, ObjectMapper mapper, ArchiveGuard archiveGuard) {
    this.client = client;
    this.mapper = mapper;
    this.archiveGuard = archiveGuard;
  }

  public StagedImage stage(
      OciScanRequest request,
      ResourceLimits limits,
      Path workspace,
      ScanDeadline deadline) {
    try {
      deadline.check();
      Path layout = workspace.resolve("image-layout");
      Path blobs = layout.resolve("blobs/sha256");
      Files.createDirectories(blobs);
      TransferBudget budget = new TransferBudget(limits);
      RegistryTarget target = RegistryTarget.from(request);

      FetchedManifest root = fetchManifest(
          target, request.manifestDigest(), null, blobs, budget, deadline);
      List<String> required = request.requiredPlatforms().isEmpty()
          ? List.of("linux/amd64")
          : List.copyOf(new LinkedHashSet<>(request.requiredPlatforms()));
      List<String> available = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      List<ManifestSelection> candidates = selectManifests(
          target, root, required, blobs, budget, deadline, missing);
      if (candidates.isEmpty()) {
        throw rejected(
            "OCI_SCAN_FAILED", "No requested OCI platform could be staged", 422, false);
      }

      int descriptorCount = 0;
      LinkedHashMap<String, Descriptor> configs = new LinkedHashMap<>();
      for (ManifestSelection selection : candidates) {
        JsonNode manifest = selection.manifest().document();
        Descriptor config = descriptor(manifest.get("config"), "config");
        if (config.size() > MAX_CONFIG_BYTES) {
          throw rejected(
              "OCI_CONFIG_TOO_LARGE",
              "OCI image configuration exceeds the bounded JSON limit",
              413,
              false);
        }
        putConsistent(configs, config);
        descriptorCount++;
        if (descriptorCount > Math.min(MAX_OCI_DESCRIPTORS, limits.maxArchiveEntries())) {
          throw rejected(
              "OCI_DESCRIPTOR_LIMIT", "OCI image has too many descriptors", 413, false);
        }
      }
      List<Descriptor> outstandingConfigs = configs.values().stream()
          .filter(descriptor -> !Files.exists(blobPath(blobs, descriptor.digest())))
          .toList();
      budget.preflight(outstandingConfigs);
      for (Descriptor config : outstandingConfigs) {
        fetchBlob(target, config, blobs, budget, deadline);
      }

      Map<String, String> configPlatforms = new LinkedHashMap<>();
      List<ManifestSelection> selections = new ArrayList<>();
      for (ManifestSelection candidate : candidates) {
        Descriptor config =
            descriptor(candidate.manifest().document().get("config"), "config");
        String actualPlatform = configPlatforms.computeIfAbsent(
            config.digest(),
            ignored -> readConfigPlatform(config, blobs));
        if (!platformMatches(candidate.requestedPlatform(), actualPlatform)) {
          addDistinct(missing, candidate.requestedPlatform());
          continue;
        }
        available.add(candidate.requestedPlatform());
        selections.add(new ManifestSelection(
            candidate.manifest(),
            descriptorWithPlatform(candidate.indexDescriptor(), actualPlatform),
            candidate.requestedPlatform()));
      }
      if (selections.isEmpty()) {
        throw rejected(
            "OCI_SCAN_FAILED", "No requested OCI platform could be staged", 422, false);
      }

      LinkedHashMap<String, Descriptor> content = new LinkedHashMap<>();
      List<Descriptor> layers = new ArrayList<>();
      for (ManifestSelection selection : selections) {
        JsonNode manifest = selection.manifest().document();
        Descriptor config = descriptor(manifest.get("config"), "config");
        putConsistent(content, config);
        JsonNode rawLayers = manifest.get("layers");
        if (rawLayers == null || !rawLayers.isArray()) {
          throw rejected(
              "OCI_MANIFEST_INVALID", "OCI image manifest has no layer array", 422, false);
        }
        for (JsonNode rawLayer : rawLayers) {
          Descriptor layer = descriptor(rawLayer, "layer");
          putConsistent(content, layer);
          layers.add(layer);
          descriptorCount++;
          if (descriptorCount > Math.min(MAX_OCI_DESCRIPTORS, limits.maxArchiveEntries())) {
            throw rejected(
                "OCI_DESCRIPTOR_LIMIT", "OCI image has too many descriptors", 413, false);
          }
        }
      }

      List<Descriptor> outstanding = content.values().stream()
          .filter(descriptor -> !Files.exists(blobPath(blobs, descriptor.digest())))
          .toList();
      budget.preflight(outstanding);
      for (Descriptor descriptor : outstanding) {
        fetchBlob(target, descriptor, blobs, budget, deadline);
      }

      List<Path> layerPaths = layers.stream()
          .map(layer -> blobPath(blobs, layer.digest()))
          .distinct()
          .toList();
      ArchiveGuard.Inspection inspection =
          archiveGuard.inspectOciLayers(layerPaths, limits, workspace, deadline);
      writeLayout(layout, selections);
      deadline.check();
      return new StagedImage(
          layout,
          List.copyOf(available),
          List.copyOf(missing),
          budget.transferredBytes(),
          inspection.entries(),
          inspection.expandedBytes(),
          inspection.nestedArchives());
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "OCI_STAGE_IO", "Unable to stage the OCI image safely", 503, true, e);
    }
  }

  private List<ManifestSelection> selectManifests(
      RegistryTarget target,
      FetchedManifest root,
      List<String> required,
      Path blobs,
      TransferBudget budget,
      ScanDeadline deadline,
      List<String> missing)
      throws IOException {
    JsonNode manifests = root.document().get("manifests");
    List<ManifestSelection> selections = new ArrayList<>();
    if (manifests == null || !manifests.isArray()) {
      for (String platform : required) {
        selections.add(new ManifestSelection(
            root,
            root.descriptor(),
            platform));
      }
      return List.copyOf(selections);
    }

    for (String platform : required) {
      Descriptor selected = null;
      for (JsonNode candidate : manifests) {
        if (platformMatches(platform, candidate.get("platform"))) {
          selected = descriptor(candidate, "platform manifest");
          break;
        }
      }
      if (selected == null) {
        addDistinct(missing, platform);
        continue;
      }
      FetchedManifest manifest =
          fetchManifest(target, selected.digest(), selected, blobs, budget, deadline);
      selections.add(new ManifestSelection(manifest, selected, platform));
    }
    return List.copyOf(selections);
  }

  private FetchedManifest fetchManifest(
      RegistryTarget target,
      String reference,
      Descriptor expected,
      Path blobs,
      TransferBudget budget,
      ScanDeadline deadline)
      throws IOException {
    long maximum = Math.min(
        MAX_MANIFEST_BYTES,
        Math.min(limitsSingleFile(budget), budget.remainingBytes()));
    if (expected != null) {
      budget.validateDescriptor(expected);
      maximum = Math.min(maximum, expected.size());
    }
    byte[] body = getBytes(
        target.manifestUri(reference),
        target.token(),
        ACCEPT_MANIFESTS,
        maximum,
        budget,
        deadline,
        "manifest");
    verify(reference, expected, body);
    JsonNode document;
    try {
      document = mapper.readTree(body);
    } catch (IOException e) {
      throw rejected(
          "OCI_MANIFEST_INVALID", "OCI manifest JSON is malformed", 422, false, e);
    }
    if (document == null || !document.isObject() || document.path("schemaVersion").asInt() != 2) {
      throw rejected(
          "OCI_MANIFEST_INVALID", "OCI manifest schema is invalid", 422, false);
    }
    String mediaType = text(document, "mediaType");
    if (mediaType == null && expected != null) mediaType = expected.mediaType();
    if (mediaType == null) {
      mediaType = document.has("manifests")
          ? "application/vnd.oci.image.index.v1+json"
          : "application/vnd.oci.image.manifest.v1+json";
    }
    Descriptor actual = new Descriptor(reference, body.length, mediaType, null, null);
    writeBlob(blobs, reference, body);
    return new FetchedManifest(actual, document);
  }

  private void fetchBlob(
      RegistryTarget target,
      Descriptor descriptor,
      Path blobs,
      TransferBudget budget,
      ScanDeadline deadline)
      throws IOException {
    budget.validateDescriptor(descriptor);
    HttpResponse<InputStream> response = send(
        target.blobUri(descriptor.digest()),
        target.token(),
        "application/octet-stream",
        deadline);
    requireSuccess(response, "blob");
    Path temporary = null;
    boolean published = false;
    try (InputStream raw = new DeadlineInputStream(response.body(), deadline)) {
      validateContentLength(response, descriptor.size(), budget.remainingBytes(), "blob");
      Path destination = blobPath(blobs, descriptor.digest());
      temporary = Files.createTempFile(blobs, "download-", ".tmp");
      MessageDigest digest = sha256();
      long count = 0;
      try (DigestInputStream input = new DigestInputStream(raw, digest);
          var output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[64 * 1024];
        while (true) {
          deadline.check();
          int read = input.read(buffer);
          deadline.check();
          if (read < 0) break;
          if (read == 0) continue;
          count += read;
          budget.consume(read);
          if (count > descriptor.size()) {
            throw rejected(
                "OCI_BLOB_SIZE_MISMATCH", "OCI blob exceeds its descriptor size", 422, false);
          }
          output.write(buffer, 0, read);
        }
      }
      if (count != descriptor.size()) {
        throw rejected(
            "OCI_BLOB_SIZE_MISMATCH", "OCI blob size does not match its descriptor", 422, false);
      }
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!descriptor.digest().substring("sha256:".length()).equals(actual)) {
        throw rejected(
            "OCI_BLOB_DIGEST_MISMATCH", "OCI blob digest verification failed", 422, false);
      }
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      published = true;
    } finally {
      if (!published && temporary != null) Files.deleteIfExists(temporary);
    }
  }

  private byte[] getBytes(
      URI uri,
      String token,
      String accept,
      long maximum,
      TransferBudget budget,
      ScanDeadline deadline,
      String kind)
      throws IOException {
    if (maximum <= 0) {
      throw rejected("OCI_INPUT_TOO_LARGE", "OCI input exceeds the configured limit", 413, false);
    }
    HttpResponse<InputStream> response = send(uri, token, accept, deadline);
    requireSuccess(response, kind);
    try (InputStream input = new DeadlineInputStream(response.body(), deadline)) {
      validateContentLength(response, null, maximum, kind);
      ByteArrayOutputStream output =
          new ByteArrayOutputStream((int) Math.min(maximum, 64 * 1024));
      byte[] buffer = new byte[32 * 1024];
      while (true) {
        deadline.check();
        int read = input.read(buffer);
        deadline.check();
        if (read < 0) break;
        if (read == 0) continue;
        if (output.size() > maximum - read) {
          throw rejected(
              "OCI_MANIFEST_TOO_LARGE", "OCI manifest exceeds the configured limit", 413, false);
        }
        budget.consume(read);
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private HttpResponse<InputStream> send(
      URI uri, String token, String accept, ScanDeadline deadline) {
    try {
      deadline.check();
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(deadline.remaining())
          .header("Authorization", "Bearer " + token)
          .header("Accept", accept)
          .header("Accept-Encoding", "identity")
          .GET()
          .build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      deadline.check();
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScannerRequestException(
          "SCANNER_INTERRUPTED", "OCI staging was interrupted", 503, true, e);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "OCI_REGISTRY_IO", "OCI registry request failed", 503, true, e);
    }
  }

  private static void requireSuccess(HttpResponse<?> response, String kind) {
    int status = response.statusCode();
    if (status >= 200 && status < 300) return;
    try {
      if (response.body() instanceof InputStream input) input.close();
    } catch (IOException ignored) {
      // The status code remains the authoritative failure.
    }
    if (status == 401 || status == 403) {
      throw rejected(
          "OCI_REGISTRY_AUTH_FAILED",
          "OCI registry rejected scanner authorization",
          503,
          true);
    }
    if (status == 404) {
      throw rejected(
          "OCI_REGISTRY_CONTENT_MISSING",
          "OCI registry content is missing",
          503,
          true);
    }
    throw rejected(
        "OCI_REGISTRY_SCAN_FAILED",
        "OCI registry returned " + status + " for " + kind,
        503,
        true);
  }

  private static void validateContentLength(
      HttpResponse<?> response, Long expected, long maximum, String kind) {
    String raw = response.headers().firstValue("Content-Length").orElse(null);
    if (raw == null) return;
    long declared;
    try {
      declared = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw rejected(
          "OCI_REGISTRY_RESPONSE_INVALID",
          "OCI registry returned an invalid Content-Length",
          502,
          true);
    }
    if (declared < 0 || declared > maximum) {
      throw rejected(
          "OCI_INPUT_TOO_LARGE",
          "OCI " + kind + " exceeds the configured input limit",
          413,
          false);
    }
    if (expected != null && declared != expected) {
      throw rejected(
          "OCI_BLOB_SIZE_MISMATCH",
          "OCI " + kind + " Content-Length does not match its descriptor",
          422,
          false);
    }
  }

  private static void verify(String reference, Descriptor expected, byte[] body) {
    if (!DIGEST.matcher(reference).matches()) {
      throw rejected(
          "OCI_MANIFEST_INVALID", "OCI manifest digest is invalid", 422, false);
    }
    if (expected != null && expected.size() != body.length) {
      throw rejected(
          "OCI_BLOB_SIZE_MISMATCH", "OCI manifest size does not match its descriptor", 422, false);
    }
    String actual = ScannerDocumentMapper.sha256(body);
    if (!reference.substring("sha256:".length()).equals(actual)) {
      throw rejected(
          "OCI_BLOB_DIGEST_MISMATCH", "OCI manifest digest verification failed", 422, false);
    }
  }

  private void writeLayout(Path layout, List<ManifestSelection> selections)
      throws IOException {
    ObjectNode index = mapper.createObjectNode();
    index.put("schemaVersion", 2);
    ArrayNode manifests = index.putArray("manifests");
    Set<String> emitted = new LinkedHashSet<>();
    for (ManifestSelection selection : selections) {
      Descriptor descriptor = selection.indexDescriptor();
      String key = descriptor.digest() + "\0" + descriptor.platform();
      if (!emitted.add(key)) continue;
      ObjectNode raw = manifests.addObject();
      raw.put("mediaType", descriptor.mediaType());
      raw.put("digest", descriptor.digest());
      raw.put("size", descriptor.size());
      if (descriptor.platform() != null) {
        String[] pieces = descriptor.platform().split("/", 3);
        ObjectNode platform = raw.putObject("platform");
        platform.put("os", pieces[0]);
        platform.put("architecture", pieces[1]);
        if (pieces.length == 3) platform.put("variant", pieces[2]);
      }
    }
    Files.write(layout.resolve("index.json"), mapper.writeValueAsBytes(index));
    Files.writeString(
        layout.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}\n");
  }

  private static Descriptor descriptor(JsonNode raw, String kind) {
    if (raw == null || !raw.isObject()) {
      throw rejected(
          "OCI_MANIFEST_INVALID", "OCI " + kind + " descriptor is missing", 422, false);
    }
    String digest = text(raw, "digest");
    String mediaType = text(raw, "mediaType");
    JsonNode sizeNode = raw.get("size");
    if (digest == null
        || !DIGEST.matcher(digest).matches()
        || mediaType == null
        || sizeNode == null
        || !sizeNode.canConvertToLong()
        || sizeNode.longValue() < 0) {
      throw rejected(
          "OCI_MANIFEST_INVALID", "OCI " + kind + " descriptor is invalid", 422, false);
    }
    JsonNode platform = raw.get("platform");
    String platformValue = platform == null ? null : platform(platform);
    return new Descriptor(digest, sizeNode.longValue(), mediaType, platformValue, raw);
  }

  private static Descriptor descriptorWithPlatform(
      Descriptor descriptor, String platform) {
    return new Descriptor(
        descriptor.digest(),
        descriptor.size(),
        descriptor.mediaType(),
        platform,
        descriptor.raw());
  }

  private static void putConsistent(
      Map<String, Descriptor> descriptors, Descriptor candidate) {
    Descriptor existing = descriptors.putIfAbsent(candidate.digest(), candidate);
    if (existing != null
        && (existing.size() != candidate.size()
            || !existing.mediaType().equals(candidate.mediaType()))) {
      throw rejected(
          "OCI_MANIFEST_INVALID",
          "OCI descriptors disagree for digest " + candidate.digest(),
          422,
          false);
    }
  }

  private static boolean platformMatches(String requested, JsonNode rawPlatform) {
    if (rawPlatform == null || !rawPlatform.isObject()) return false;
    String actual = platform(rawPlatform);
    return platformMatches(requested, actual);
  }

  private static boolean platformMatches(String requested, String actual) {
    if (requested == null || actual == null) return false;
    String[] wanted = requested.split("/", 3);
    String[] found = actual.split("/", 3);
    if (wanted.length < 2 || found.length < 2) return false;
    if (!wanted[0].equals(found[0]) || !wanted[1].equals(found[1])) return false;
    return wanted.length < 3 || (found.length == 3 && wanted[2].equals(found[2]));
  }

  private static String platform(JsonNode raw) {
    String os = text(raw, "os");
    String architecture = text(raw, "architecture");
    if (os == null || architecture == null) return null;
    String variant = text(raw, "variant");
    return os + "/" + architecture + (variant == null ? "" : "/" + variant);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) return null;
    return value.textValue();
  }

  private String readConfigPlatform(Descriptor descriptor, Path blobs) {
    Path config = blobPath(blobs, descriptor.digest());
    try {
      if (Files.size(config) != descriptor.size()) {
        throw rejected(
            "OCI_BLOB_SIZE_MISMATCH",
            "OCI image configuration size does not match its descriptor",
            422,
            false);
      }
      JsonNode document;
      try (InputStream input = Files.newInputStream(config)) {
        document = mapper.readTree(input);
      }
      String value = document == null || !document.isObject() ? null : platform(document);
      if (value == null) {
        throw rejected(
            "OCI_CONFIG_INVALID",
            "OCI image configuration does not declare a valid platform",
            422,
            false);
      }
      return value;
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "OCI_STAGE_IO", "Unable to inspect the OCI image configuration", 503, true, e);
    }
  }

  private static void addDistinct(List<String> values, String value) {
    if (!values.contains(value)) values.add(value);
  }

  private static void writeBlob(Path blobs, String digest, byte[] body)
      throws IOException {
    Files.write(blobPath(blobs, digest), body);
  }

  private static Path blobPath(Path blobs, String digest) {
    return blobs.resolve(digest.substring("sha256:".length()));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static long limitsSingleFile(TransferBudget budget) {
    return budget.limits().maxSingleFileBytes();
  }

  private static ScannerRequestException rejected(
      String code, String message, int status, boolean retryable) {
    return new ScannerRequestException(code, message, status, retryable);
  }

  private static ScannerRequestException rejected(
      String code, String message, int status, boolean retryable, Throwable cause) {
    return new ScannerRequestException(code, message, status, retryable, cause);
  }

  private static final class TransferBudget {
    private final ResourceLimits limits;
    private long transferredBytes;

    private TransferBudget(ResourceLimits limits) {
      this.limits = limits;
    }

    private ResourceLimits limits() {
      return limits;
    }

    private long transferredBytes() {
      return transferredBytes;
    }

    private long remainingBytes() {
      return limits.maxInputBytes() - transferredBytes;
    }

    private void consume(long count) {
      if (count < 0 || count > remainingBytes()) {
        throw rejected(
            "OCI_INPUT_TOO_LARGE", "OCI input exceeds the configured limit", 413, false);
      }
      transferredBytes += count;
    }

    private void validateDescriptor(Descriptor descriptor) {
      if (descriptor.size() > limits.maxSingleFileBytes()) {
        throw rejected(
            "OCI_BLOB_TOO_LARGE",
            "OCI descriptor exceeds the single-file limit",
            413,
            false);
      }
    }

    private void preflight(List<Descriptor> descriptors) {
      long required = 0;
      for (Descriptor descriptor : descriptors) {
        validateDescriptor(descriptor);
        if (descriptor.size() > Long.MAX_VALUE - required) {
          throw rejected(
              "OCI_INPUT_TOO_LARGE", "OCI descriptor bytes overflow the input budget", 413, false);
        }
        required += descriptor.size();
      }
      if (required > remainingBytes()) {
        throw rejected(
            "OCI_INPUT_TOO_LARGE",
            "OCI descriptor bytes exceed the configured input limit",
            413,
            false);
      }
    }
  }

  /**
   * Closes a registry response body at the absolute scanner deadline.
   *
   * <p>Java HttpClient completes an {@code ofInputStream} response when headers arrive, so the
   * request timeout alone cannot bound a peer that stalls during a later body read. Closing the
   * body from a lightweight deadline watcher unblocks that read without relying on polling.
   */
  private static final class DeadlineInputStream extends InputStream {
    private final InputStream delegate;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean expired = new AtomicBoolean();
    private final Thread deadlineWatcher;

    private DeadlineInputStream(InputStream delegate, ScanDeadline deadline) {
      this.delegate = delegate;
      Duration remaining = deadline.remaining();
      this.deadlineWatcher = Thread.ofVirtual()
          .name("kkrepo-scanner-registry-deadline")
          .start(() -> expireAfter(remaining));
    }

    @Override
    public int read() throws IOException {
      try {
        int value = delegate.read();
        requireWithinDeadline();
        return value;
      } catch (IOException e) {
        throw translate(e);
      }
    }

    @Override
    public int read(byte[] value, int offset, int length) throws IOException {
      try {
        int count = delegate.read(value, offset, length);
        requireWithinDeadline();
        return count;
      } catch (IOException e) {
        throw translate(e);
      }
    }

    @Override
    public void close() throws IOException {
      deadlineWatcher.interrupt();
      if (closed.compareAndSet(false, true)) delegate.close();
    }

    private void expireAfter(Duration remaining) {
      try {
        Thread.sleep(remaining);
      } catch (InterruptedException stopped) {
        return;
      }
      expired.set(true);
      if (closed.compareAndSet(false, true)) {
        try {
          delegate.close();
        } catch (IOException ignored) {
          // The blocked reader translates the deadline once close returns or fails.
        }
      }
    }

    private void requireWithinDeadline() {
      if (expired.get()) throw timeout(null);
    }

    private RuntimeException translate(IOException failure) throws IOException {
      if (expired.get()) return timeout(failure);
      throw failure;
    }

    private static ScannerRequestException timeout(Throwable cause) {
      return cause == null
          ? new ScannerRequestException(
              "SCANNER_TIMEOUT",
              "Scanner request exceeded its end-to-end time limit",
              504,
              true)
          : new ScannerRequestException(
              "SCANNER_TIMEOUT",
              "Scanner request exceeded its end-to-end time limit",
              504,
              true,
              cause);
    }
  }

  private record RegistryTarget(
      String scheme, String authority, String repository, String token) {
    private static RegistryTarget from(OciScanRequest request) {
      URI registry = URI.create(request.registryUrl());
      String prefix = registry.getPath() == null
          ? "" : registry.getPath().replaceAll("^/+|/+$", "");
      String repository = prefix.isEmpty()
          ? request.repository() : prefix + "/" + request.repository();
      return new RegistryTarget(
          registry.getScheme(),
          registry.getRawAuthority(),
          repository,
          request.scopedBearerToken());
    }

    private URI manifestUri(String reference) {
      return URI.create(
          scheme + "://" + authority + "/v2/" + repository + "/manifests/" + reference);
    }

    private URI blobUri(String digest) {
      return URI.create(
          scheme + "://" + authority + "/v2/" + repository + "/blobs/" + digest);
    }
  }

  private record Descriptor(
      String digest, long size, String mediaType, String platform, JsonNode raw) {}

  private record FetchedManifest(Descriptor descriptor, JsonNode document) {}

  private record ManifestSelection(
      FetchedManifest manifest, Descriptor indexDescriptor, String requestedPlatform) {}

  public record StagedImage(
      Path layout,
      List<String> availablePlatforms,
      List<String> missingPlatforms,
      long transferredBytes,
      int archiveEntries,
      long expandedBytes,
      int nestedArchives) {
    public StagedImage {
      availablePlatforms =
          availablePlatforms == null ? List.of() : List.copyOf(availablePlatforms);
      missingPlatforms =
          missingPlatforms == null ? List.of() : List.copyOf(missingPlatforms);
    }
  }
}
