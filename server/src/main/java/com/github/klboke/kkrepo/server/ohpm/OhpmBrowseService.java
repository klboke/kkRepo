package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmBrowsePath;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmGroupMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OhpmBrowseService {
  private final AssetDao assets;
  private final ObjectMapper json;

  public OhpmBrowseService(AssetDao assets, ObjectMapper json) {
    this.assets = assets;
    this.json = json;
  }

  public String downloadUrl(RepositoryRecord visible, RepositoryRecord source, String publicPath) {
    OhpmBrowsePath path = OhpmBrowsePath.parse(publicPath);
    String url = RepositoryUrlPrefix.currentPath() + visible.name() + "/" + path.storagePath();
    if (visible.type() != RepositoryType.GROUP) return url;
    JsonNode dist = metadata(source, path).path("versions").path(path.version()).path("dist");
    try {
      var har = new OhpmIntegrity(dist.path("integrity").textValue());
      var hsp = dist.has("integrity_hsp") ? new OhpmIntegrity(dist.path("integrity_hsp").textValue()) : null;
      return url + "?" + OhpmGroupMetadata.SOURCE_PARAMETER + "=" + OhpmGroupMetadata.sourceFingerprint(
          visible.id(), source.id(), path.packageId(), path.version(), har, hsp);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "OHPM cached metadata is unavailable; refresh the package");
    }
  }

  public Map<String, Object> attributes(RepositoryRecord source, String publicPath) {
    OhpmBrowsePath path = OhpmBrowsePath.parse(publicPath);
    JsonNode root = metadata(source, path);
    JsonNode manifest = root.path("versions").path(path.version());
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", path.packageId().id());
    values.put("version", path.version());
    values.put("sourceRepository", source.name());
    for (String field : new String[] {"packageType", "hspType", "main", "types", "description", "license", "_ohpmVersion"}) {
      if (manifest.path(field).isTextual()) values.put(field, manifest.path(field).textValue());
    }
    var tags = new ArrayList<String>();
    root.path("dist-tags").fields().forEachRemaining(entry -> {
      if (path.version().equals(entry.getValue().textValue())) tags.add(entry.getKey());
    });
    values.put("dist-tags", tags);
    return values;
  }

  private JsonNode metadata(RepositoryRecord source, OhpmBrowsePath path) {
    return assets.findAssetByPath(source.id(), path.packageId().id())
        .<JsonNode>map(root -> json.valueToTree(root.attributes().get(source.type() == RepositoryType.HOSTED
            ? "ohpmMetadata" : "ohpmProxyMetadata")))
        .orElseGet(() -> json.createObjectNode());
  }
}
