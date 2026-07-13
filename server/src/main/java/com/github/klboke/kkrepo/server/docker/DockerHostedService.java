package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DockerHostedService {
  private final DockerBlobStore blobStore;
  private final DockerManifestStore manifestStore;
  private final DockerUploadService uploadService;
  private final DockerMetrics metrics;

  public DockerHostedService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerUploadService uploadService) {
    this(blobStore, manifestStore, uploadService, (DockerMetrics) null);
  }

  @Autowired
  public DockerHostedService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerUploadService uploadService,
      ObjectProvider<DockerMetrics> metricsProvider) {
    this(blobStore, manifestStore, uploadService,
        metricsProvider == null ? null : metricsProvider.getIfAvailable());
  }

  public DockerHostedService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerUploadService uploadService,
      DockerMetrics metrics) {
    this.blobStore = blobStore;
    this.manifestStore = manifestStore;
    this.uploadService = uploadService;
    this.metrics = metrics;
  }

  public DockerResponse getManifest(RepositoryRuntime runtime, String imageName, String reference, boolean headOnly) {
    return getManifest(runtime, imageName, reference, headOnly, List.of());
  }

  public DockerResponse getManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      boolean headOnly,
      List<String> acceptHeaders) {
    ensureType(runtime, RepositoryType.HOSTED);
    return manifestStore.serveManifest(runtime, imageName, reference, headOnly, acceptHeaders);
  }

  public DockerResponse putManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      InputStream body,
      String contentType,
      List<String> tags,
      String userId,
      String remoteAddr) throws IOException {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "writing Docker manifests");
    if (reference != null && !DockerPathParser.isDigestReference(reference)) {
      DockerPathParser.validateTag(reference);
    }
    var stored = manifestStore.putManifest(
        runtime, imageName, reference, body.readAllBytes(), contentType, userId, remoteAddr, true, tags);
    DockerResponse response = DockerResponse.noBody(201)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, stored.manifest().digest());
    if (stored.manifest().subjectDigest() != null) {
      response.withHeader(DockerConstants.OCI_SUBJECT_HEADER, stored.manifest().subjectDigest());
    }
    return response;
  }

  public DockerResponse deleteManifest(RepositoryRuntime runtime, String imageName, String reference) {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "deleting Docker manifests");
    int deleted = manifestStore.deleteReference(runtime, imageName, reference);
    if (deleted == 0) {
      throw new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference);
    }
    return DockerResponse.noBody(202);
  }

  public DockerResponse deleteBlob(RepositoryRuntime runtime, DockerDigest digest) {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "deleting Docker blobs");
    blobStore.deleteBlob(runtime, digest);
    return DockerResponse.noBody(202);
  }

  public DockerResponse getBlob(RepositoryRuntime runtime, DockerDigest digest, boolean headOnly) {
    ensureType(runtime, RepositoryType.HOSTED);
    return blobStore.getBlob(runtime, digest, headOnly);
  }

  public DockerUploadService.UploadStatus startUpload(
      RepositoryRuntime runtime,
      String imageName,
      String mountDigest,
      String fromImage,
      RepositoryRuntime sourceRuntime,
      String userId,
      String remoteAddr) {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "uploading Docker blobs");
    return uploadService.start(runtime, imageName, mountDigest, fromImage, sourceRuntime, userId, remoteAddr);
  }

  public DockerUploadService.UploadStatus uploadStatus(RepositoryRuntime runtime, String uuid) {
    ensureType(runtime, RepositoryType.HOSTED);
    return uploadService.status(runtime, uuid);
  }

  public DockerUploadService.UploadStatus appendUpload(
      RepositoryRuntime runtime,
      String uuid,
      InputStream body,
      Long contentLength,
      String contentRange) {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "uploading Docker blobs");
    return uploadService.append(runtime, uuid, body, contentLength, contentRange);
  }

  public DockerUploadService.CompleteResult completeUpload(
      RepositoryRuntime runtime,
      String uuid,
      InputStream body,
      Long contentLength,
      String contentRange,
      DockerDigest digest,
      String userId,
      String remoteAddr) {
    ensureType(runtime, RepositoryType.HOSTED);
    ensureMutationsAllowed(runtime, "uploading Docker blobs");
    return uploadService.complete(runtime, uuid, body, contentLength, contentRange, digest, userId, remoteAddr);
  }

  public DockerResponse cancelUpload(RepositoryRuntime runtime, String uuid) {
    ensureType(runtime, RepositoryType.HOSTED);
    uploadService.cancel(runtime, uuid);
    return DockerResponse.noBody(204);
  }

  public DockerTagList tags(RepositoryRuntime runtime, String imageName, String last, int limit) {
    ensureType(runtime, RepositoryType.HOSTED);
    return manifestStore.listTags(runtime, imageName, last, limit);
  }

  public DockerCatalogList catalog(RepositoryRuntime runtime, String last, int limit) {
    ensureType(runtime, RepositoryType.HOSTED);
    return manifestStore.listCatalog(runtime, last, limit);
  }

  public Map<String, Object> referrers(RepositoryRuntime runtime, DockerDigest digest, String artifactType) {
    ensureType(runtime, RepositoryType.HOSTED);
    List<Map<String, Object>> manifests = manifestStore.referrers(runtime, digest.value(), artifactType).stream()
        .map(row -> {
          Map<String, Object> descriptor = new LinkedHashMap<>();
          descriptor.put("mediaType", row.mediaType());
          descriptor.put("digest", row.digest());
          descriptor.put("size", row.size());
          if (row.artifactType() != null && !row.artifactType().isBlank()) {
            descriptor.put("artifactType", row.artifactType());
          }
          Map<String, Object> annotations = annotations(row);
          if (!annotations.isEmpty()) {
            descriptor.put("annotations", annotations);
          }
          return descriptor;
        })
        .toList();
    recordReferrers(runtime, "local", manifests.size());
    return Map.of(
        "schemaVersion", 2,
        "mediaType", DockerConstants.MEDIA_TYPE_OCI_INDEX,
        "manifests", manifests);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> annotations(DockerManifestRecord row) {
    Object value = row.attributes() == null ? null : row.attributes().get("annotations");
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private void recordReferrers(RepositoryRuntime runtime, String outcome, long count) {
    if (metrics != null) {
      metrics.referrers(runtime, outcome, count);
    }
  }

  private static void ensureType(RepositoryRuntime runtime, RepositoryType expected) {
    if (runtime.type() != expected) {
      throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "unsupported repository type", 405);
    }
  }

  private static void ensureMutationsAllowed(RepositoryRuntime runtime, String operation) {
    String policy = runtime.writePolicy() == null || runtime.writePolicy().isBlank()
        ? "ALLOW_ONCE"
        : runtime.writePolicy().trim().toUpperCase(java.util.Locale.ROOT);
    if ("DENY".equals(policy)) {
      throw new DockerProtocolException(DockerErrorCode.DENIED, "Write policy DENY forbids " + operation, 403);
    }
  }
}
