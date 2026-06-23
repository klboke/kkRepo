package com.github.klboke.kkrepo.server.docker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestDescriptor;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DockerManifestParser {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;

  public DockerManifestParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public DockerManifestMetadata parse(byte[] body, String contentTypeHint) {
    Map<String, Object> root;
    try {
      root = objectMapper.readValue(body, MAP_TYPE);
    } catch (IOException e) {
      throw new DockerProtocolException(DockerErrorCode.MANIFEST_INVALID, "manifest body is not valid JSON", 400);
    }
    String mediaType = firstText(root.get("mediaType"), contentTypeHint, DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST);
    validateMediaType(mediaType);
    String artifactType = text(root.get("artifactType"));
    Map<String, Object> annotations = objectMap(root.get("annotations"));
    String subjectDigest = null;
    Object subject = root.get("subject");
    if (subject instanceof Map<?, ?> subjectMap) {
      subjectDigest = text(subjectMap.get("digest"));
      if (subjectDigest != null) {
        DockerDigest.parse(subjectDigest);
      }
    }
    List<DockerManifestDescriptor> references = new ArrayList<>();
    if (root.get("config") instanceof Map<?, ?> config) {
      artifactType = firstText(artifactType, legacyArtifactType(config));
      references.add(descriptor("CONFIG", config));
    }
    Object layers = root.get("layers");
    if (layers instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          references.add(descriptor("LAYER", map));
        }
      }
    }
    Object manifests = root.get("manifests");
    if (manifests instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          references.add(descriptor("MANIFEST", map));
        }
      }
    }
    Object blobs = root.get("blobs");
    if (blobs instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          references.add(descriptor("LAYER", map));
        }
      }
    }
    return new DockerManifestMetadata(
        mediaType,
        artifactType,
        subjectDigest,
        annotations,
        List.copyOf(references));
  }

  private static String legacyArtifactType(Map<?, ?> config) {
    String mediaType = text(config.get("mediaType"));
    if (mediaType == null) {
      return null;
    }
    String normalized = mediaType.toLowerCase(Locale.ROOT);
    if (normalized.equals(DockerConstants.MEDIA_TYPE_OCI_IMAGE_CONFIG)
        || normalized.equals(DockerConstants.MEDIA_TYPE_OCI_EMPTY)
        || normalized.equals("application/vnd.docker.container.image.v1+json")) {
      return null;
    }
    return mediaType;
  }

  private DockerManifestDescriptor descriptor(String kind, Map<?, ?> map) {
    String digest = text(map.get("digest"));
    if (digest == null) {
      throw new DockerProtocolException(DockerErrorCode.MANIFEST_INVALID, "descriptor digest is required", 400);
    }
    DockerDigest.parse(digest);
    String mediaType = text(map.get("mediaType"));
    Long size = number(map.get("size"));
    Map<String, Object> platform = objectMap(map.get("platform"));
    Map<String, Object> annotations = objectMap(map.get("annotations"));
    return new DockerManifestDescriptor(kind, digest, mediaType, size, platform, annotations);
  }

  private static void validateMediaType(String mediaType) {
    String value = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
    if (value.equals(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST)
        || value.equals(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST_LIST)
        || value.equals(DockerConstants.MEDIA_TYPE_OCI_MANIFEST)
        || value.equals(DockerConstants.MEDIA_TYPE_OCI_INDEX)
        || value.equals(DockerConstants.MEDIA_TYPE_OCI_ARTIFACT)) {
      return;
    }
    throw new DockerProtocolException(DockerErrorCode.MANIFEST_INVALID, "unsupported manifest media type", 415);
  }

  private static String firstText(Object... values) {
    for (Object value : values) {
      String text = text(value);
      if (text != null) {
        return text;
      }
    }
    return null;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text;
  }

  private static Long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }
}
