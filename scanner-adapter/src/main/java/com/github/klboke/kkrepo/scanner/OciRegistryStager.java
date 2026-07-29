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
      List<ManifestSelection> selections = selectManifests(
          target, root, required, blobs, budget, deadline, available, missing);
      if (selections.isEmpty()) {
        throw rejected(
            "OCI_SCAN_FAILED", "No requested OCI platform could be staged", 422, false);
      }

      LinkedHashMap<String, Descriptor> content = new LinkedHashMap<>();
      List<Descriptor> layers = new ArrayList<>();
      int descriptorCount = 0;
      for (ManifestSelection selection : selections) {
        JsonNode manifest = selection.manifest().document();
        Descriptor config = descriptor(manifest.get("config"), "config");
        putConsistent(content, config);
        descriptorCount++;
        if (descriptorCount > Math.min(MAX_OCI_DESCRIPTORS, limits.maxArchiveEntries())) {
          throw rejected(
              "OCI_DESCRIPTOR_LIMIT", "OCI image has too many descriptors", 413, false);
        }
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
      List<String> available,
      List<String> missing)
      throws IOException {
    JsonNode manifests = root.document().get("manifests");
    List<ManifestSelection> selections = new ArrayList<>();
    if (manifests == null || !manifests.isArray()) {
      for (String platform : required) {
        available.add(platform);
        selections.add(new ManifestSelection(
            root,
            descriptorWithPlatform(root.descriptor(), platform)));
      }
      return deduplicateSelections(selections);
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
        missing.add(platform);
        continue;
      }
      FetchedManifest manifest =
          fetchManifest(target, selected.digest(), selected, blobs, budget, deadline);
      available.add(platform);
      selections.add(new ManifestSelection(manifest, selected));
    }
    return deduplicateSelections(selections);
  }

  private static List<ManifestSelection> deduplicateSelections(
      List<ManifestSelection> selections) {
    LinkedHashMap<String, ManifestSelection> unique = new LinkedHashMap<>();
    for (ManifestSelection selection : selections) {
      unique.putIfAbsent(
          selection.indexDescriptor().digest() + "\0" + selection.indexDescriptor().platform(),
          selection);
    }
    return List.copyOf(unique.values());
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
    validateContentLength(response, descriptor.size(), budget.remainingBytes(), "blob");
    Path destination = blobPath(blobs, descriptor.digest());
    Path temporary = Files.createTempFile(blobs, "download-", ".tmp");
    MessageDigest digest = sha256();
    long count = 0;
    try (InputStream raw = response.body();
        DigestInputStream input = new DigestInputStream(raw, digest);
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
    } finally {
      if (count != descriptor.size()) Files.deleteIfExists(temporary);
    }
    if (count != descriptor.size()) {
      throw rejected(
          "OCI_BLOB_SIZE_MISMATCH", "OCI blob size does not match its descriptor", 422, false);
    }
    String actual = HexFormat.of().formatHex(digest.digest());
    if (!descriptor.digest().substring("sha256:".length()).equals(actual)) {
      Files.deleteIfExists(temporary);
      throw rejected(
          "OCI_BLOB_DIGEST_MISMATCH", "OCI blob digest verification failed", 422, false);
    }
    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
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
    validateContentLength(response, null, maximum, kind);
    ByteArrayOutputStream output =
        new ByteArrayOutputStream((int) Math.min(maximum, 64 * 1024));
    try (InputStream input = response.body()) {
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
    }
    return output.toByteArray();
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
    if (actual == null) return false;
    String[] wanted = requested.split("/", 3);
    String[] found = actual.split("/", 3);
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

  private record RegistryTarget(
      String scheme, String authority, String repository, String token) {
    private static RegistryTarget from(OciScanRequest request) {
      URI registry = URI.create(request.registryUrl());
      String prefix = registry.getPath() == null
          ? "" : registry.getPath().replaceAll("^/+|/+$", "");
      String repository = prefix.isEmpty()
          ? request.repository() : prefix + "/" + request.repository();
      return new RegistryTarget(
          registry.getScheme(), registry.getRawAuthority(), repository, request.scopedBearerToken());
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
      FetchedManifest manifest, Descriptor indexDescriptor) {}

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
