import {
  escapeHtml,
  formatBytes,
  formatBytesDetail,
  formatInstant,
  pathSegments,
} from "../details/format.js";
import { codeValue, kv, maybeKv, shortDigest, usageSnippet } from "../details/pane.js";
import { currentRepository } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function dockerCoordinates(entry, detail = null) {
  const docker = detail?.docker || {};
  const image = docker.image_name || imageNameFromDockerPath(entry.path);
  const reference = docker.reference || dockerTagFromDockerPath(entry.path) || "latest";
  return { image, reference, digest: docker.digest || docker.raw_bytes_digest || "" };
}

function isDockerDetail(detail) {
  return currentRepository()?.format === "docker" || !!detail?.docker?.asset_kind;
}

function imageNameFromDockerPath(path) {
  const parts = pathSegments(path);
  const manifests = parts.lastIndexOf("manifests");
  if (manifests > 0) {
    const start = parts[0] === "v2" ? 1 : parts[0] === "docker" && parts[1] === "manifests" ? 2 : 0;
    return parts.slice(start, manifests).join("/");
  }
  const blobs = parts.lastIndexOf("blobs");
  if (blobs > 0) {
    const start = parts[0] === "v2" ? 1 : 0;
    return parts.slice(start, blobs).join("/");
  }
  return "";
}

function dockerTagFromDockerPath(path) {
  const parts = pathSegments(path);
  const manifests = parts.lastIndexOf("manifests");
  if (manifests >= 0 && parts[manifests + 1]) {
    const reference = parts[manifests + 1];
    return reference.includes(":") ? "" : reference;
  }
  return "";
}

function dockerImageReferenceBase(repo = currentRepository()) {
  if (!repo) return "";
  const docker = repo.docker || {};
  if ((docker.connectorEnabled || docker.httpsConnectorEnabled) && docker.connectorPublicUrl) {
    const value = docker.connectorPublicUrl.trim();
    try {
      const url = new URL(/^https?:\/\//i.test(value) ? value
        : value.startsWith("//") ? `${window.location.protocol}${value}` : `https://${value}`);
      if (url.host && !url.username && !url.password) {
        return `${url.host}${url.pathname.replace(/\/+$/, "").replace(/\/v2$/, "")}`;
      }
    } catch (_) {
      // Old saved configurations may contain an invalid public URL; retain usable routing below.
    }
  }
  if (docker.httpsConnectorEnabled && docker.httpsConnectorPort) {
    return `${window.location.hostname}:${docker.httpsConnectorPort}`;
  }
  if (docker.connectorEnabled && docker.connectorPort) {
    return `${window.location.hostname}:${docker.connectorPort}`;
  }
  return `${window.location.host}/${repo.name}`;
}

function dockerUsageDetail(entry, detail = null) {
  const { image, reference, digest } = dockerCoordinates(entry, detail);
  if (!image) return null;
  const base = dockerImageReferenceBase();
  const byTag = `${base}/${image}:${reference || "latest"}`;
  const snippets = [
    usageSnippet("docker pull", `docker pull ${byTag}`),
    usageSnippet("docker tag", `docker tag ${image}:${reference || "latest"} ${byTag}`),
    usageSnippet("docker push", `docker push ${byTag}`),
  ];
  if (digest) {
    snippets.push(usageSnippet("docker pull digest", `docker pull ${base}/${image}@${digest}`));
  }
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "docker"],
      ["Image", image],
      ["Reference", reference || digest || "-"],
      ["Digest", digest || "-"],
      ["Registry URL", base],
    ],
    snippets,
  };
}

function dockerKindLabel(kind) {
  const normalized = (kind || "").toString().toUpperCase();
  if (normalized === "MANIFEST") return "Manifest";
  if (normalized === "BLOB") return "Blob";
  return normalized || "-";
}

function dockerActorRows(detail) {
  const uploader = detail?.uploader || "-";
  const uploaderIp = detail?.uploaderIp || "";
  const proxy = uploader === "proxy";
  return `
    ${kv(proxy ? "Cached by" : "Uploaded by", uploader)}
    ${uploaderIp ? kv(proxy ? "Remote" : "Uploader IP", uploaderIp) : ""}
  `;
}

function dockerSizeRows(docker, detail, entry) {
  const manifestSize = docker?.manifest_size_bytes ?? detail?.size ?? entry.size;
  const blobSize = docker?.blob_size_bytes ?? detail?.size ?? entry.size;
  if ((docker?.asset_kind || "").toString().toUpperCase() === "BLOB") {
    return kv("Blob size", formatBytesDetail(blobSize));
  }
  return `
    ${kv("Manifest size", formatBytesDetail(manifestSize))}
    ${docker?.layer_size_bytes != null ? kv("Image size", formatBytesDetail(docker.layer_size_bytes)) : ""}
    ${docker?.cached_image_size_bytes != null ? kv("Cached image size", formatBytesDetail(docker.cached_image_size_bytes)) : ""}
  `;
}

function renderDockerSummaryPanel(entry, detail, sourceRepository) {
  const docker = detail?.docker || {};
  const { image, reference, digest } = dockerCoordinates(entry, detail);
  return `
    <section class="panel">
      <header class="panel-head">Summary</header>
      <div class="panel-body">
        ${kv("Repository", state.repo)}
        ${sourceRepository && sourceRepository !== state.repo ? kv("Source repository", sourceRepository) : ""}
        ${kv("Path", entry.path)}
        ${maybeKv("Image", image)}
        ${maybeKv("Reference", reference || digest || "-")}
        ${kv("Digest", codeValue(digest || docker.raw_bytes_digest || "-"), { raw: true })}
        ${kv("Asset type", dockerKindLabel(docker.asset_kind))}
        ${dockerSizeRows(docker, detail, entry)}
        ${maybeKv("Cached platform", docker.cached_image_platform)}
        ${dockerActorRows(detail)}
        ${kv("Last updated", formatInstant(detail?.lastUpdatedAt || entry.lastUpdatedAt))}
      </div>
    </section>`;
}

function renderDockerMetadataPanel(detail) {
  const docker = detail?.docker || {};
  if (!Object.keys(docker).length) return "";
  const platforms = Array.isArray(docker.platforms) ? docker.platforms : [];
  const configs = Array.isArray(docker.config_descriptors) ? docker.config_descriptors : [];
  const layers = Array.isArray(docker.layer_descriptors) ? docker.layer_descriptors : [];
  const manifests = Array.isArray(docker.manifest_descriptors) ? docker.manifest_descriptors : [];
  const referrers = Array.isArray(docker.referrers) ? docker.referrers : [];
  const cachedPlatforms = docker.cached_platform_count != null
    ? `${docker.cached_platform_count}${docker.platform_count ? ` / ${docker.platform_count}` : ""}`
    : "";
  return `
    <section class="panel">
      <header class="panel-head">Docker metadata</header>
      <div class="panel-body">
        ${maybeKv("Media type", docker.media_type || detail?.contentType)}
        ${maybeKv("Layers", docker.layer_count)}
        ${maybeKv("Cached layers", docker.cached_layer_count != null && docker.layer_count ? `${docker.cached_layer_count} / ${docker.layer_count}` : docker.cached_layer_count)}
        ${maybeKv("Layer size", docker.layer_size_bytes != null ? formatBytesDetail(docker.layer_size_bytes) : "")}
        ${maybeKv("Cached layer size", docker.cached_layer_size_bytes != null ? formatBytesDetail(docker.cached_layer_size_bytes) : "")}
        ${maybeKv("Referenced size", docker.referenced_size_bytes != null ? formatBytesDetail(docker.referenced_size_bytes) : "")}
        ${maybeKv("Platforms", docker.platform_count)}
        ${maybeKv("Cached platforms", cachedPlatforms)}
        ${maybeKv("Platform summary", docker.platform_summary)}
        ${maybeKv("Descriptors", docker.descriptor_count)}
        ${maybeKv("Tags", Array.isArray(docker.tags) ? docker.tags.join(", ") : "")}
        ${maybeKv("Referrers", docker.referrer_count)}
        ${renderDockerPlatforms(platforms)}
        ${renderDockerDescriptorList("Config", configs)}
        ${renderDockerDescriptorList("Layers", layers)}
        ${renderDockerDescriptorList("Child manifests", manifests)}
        ${renderDockerReferrers(referrers)}
      </div>
    </section>`;
}

function renderDockerPlatforms(platforms) {
  if (!platforms.length) return "";
  const rows = platforms.map((platform) => `
    <div class="platform-row">
      <span class="platform-name">${escapeHtml(platform.platform || "-")}</span>
      <code class="platform-digest">${escapeHtml(shortDigest(platform.digest || ""))}</code>
      <span class="platform-size">${escapeHtml(platform.cached_image_size_bytes != null
        ? formatBytes(platform.cached_image_size_bytes)
        : platform.manifest_size_bytes != null ? formatBytes(platform.manifest_size_bytes) : "-")}</span>
    </div>`).join("");
  return `<div class="platform-list">${rows}</div>`;
}

function renderDockerDescriptorList(title, descriptors) {
  if (!descriptors.length) return "";
  const rows = descriptors.map((descriptor) => `
    <div class="docker-descriptor-row">
      <code class="descriptor-digest" title="${escapeHtml(descriptor.digest || "")}">${escapeHtml(shortDigest(descriptor.digest || ""))}</code>
      <span class="descriptor-media" title="${escapeHtml(descriptor.media_type || "")}">${escapeHtml(descriptor.media_type || "-")}</span>
      <span class="descriptor-size">${escapeHtml(descriptor.size_bytes != null ? formatBytes(descriptor.size_bytes) : "-")}</span>
      ${descriptor.platform ? `<span class="descriptor-platform">${escapeHtml(descriptor.platform)}</span>` : ""}
    </div>`).join("");
  return `
    <div class="docker-descriptor-list">
      <div class="docker-descriptor-title">${escapeHtml(title)}</div>
      ${rows}
    </div>`;
}

function renderDockerReferrers(referrers) {
  if (!referrers.length) return "";
  const rows = referrers.map((referrer) => `
    <div class="docker-descriptor-row docker-referrer-row">
      <code class="descriptor-digest" title="${escapeHtml(referrer.digest || "")}">${escapeHtml(shortDigest(referrer.digest || ""))}</code>
      <span class="descriptor-media" title="${escapeHtml(referrer.artifact_type || referrer.media_type || "")}">${escapeHtml(referrer.artifact_type || referrer.media_type || "-")}</span>
      <span class="descriptor-size">${escapeHtml(referrer.size_bytes != null ? formatBytes(referrer.size_bytes) : "-")}</span>
      <span class="descriptor-platform">${escapeHtml(referrer.image_name || "")}</span>
    </div>`).join("");
  return `
    <div class="docker-descriptor-list">
      <div class="docker-descriptor-title">Referrers</div>
      ${rows}
    </div>`;
}

export {
  dockerUsageDetail,
  imageNameFromDockerPath,
  isDockerDetail,
  renderDockerMetadataPanel,
  renderDockerSummaryPanel,
};
