package com.github.klboke.kkrepo.server.offload;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

record DownloadOffloadMethod(String id, String label, String header, String target) {
  static final String REQUEST_HEADER = "X-KkRepo-File-Offload";
  static final String PROBE_HEADER = "X-KkRepo-Offload-Probe";
  static final String VERIFIED_HEADER = "X-KkRepo-File-Served";
  static final String INTERNAL_URI = "internal-uri";
  static final String ABSOLUTE_PATH = "absolute-path";
  static final String INTERNAL_PREFIX = "/_kkrepo_file_offload";
  static final String MOUNT_PREFIX = "/srv/kkrepo-blobs";

  private static final String ACCEL = "X-Accel-Redirect";
  private static final String SENDFILE = "X-Sendfile";
  private static final Pattern FIELD_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
  private static final Set<String> RESERVED_FIELDS = Set.of(
      "authorization", "connection", "cookie", "digest", "etag", "host", "keep-alive", "last-modified",
      "link", "location", "proxy-authenticate", "proxy-authorization", "range", "retry-after", "set-cookie",
      "te", "trailer", "transfer-encoding", "upgrade", "vary", "www-authenticate", "cache-control",
      "expires", "strict-transport-security", "referrer-policy", "permissions-policy", "x-content-type-options",
      "x-frame-options", "x-xss-protection", "x-kkrepo-file-offload", "x-kkrepo-file-served", "x-kkrepo-offload-probe");

  static final List<DownloadOffloadMethod> METHODS = List.of(
      new DownloadOffloadMethod("nginx", "NGINX", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("openresty", "OpenResty", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("tengine", "Tengine", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("angie", "Angie", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("npm", "Nginx Proxy Manager", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("ngf", "NGINX Gateway Fabric", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("caddy", "Caddy", ACCEL, INTERNAL_URI),
      new DownloadOffloadMethod("apache", "Apache HTTP Server", SENDFILE, ABSOLUTE_PATH),
      new DownloadOffloadMethod("lighttpd", "lighttpd", SENDFILE, ABSOLUTE_PATH),
      new DownloadOffloadMethod("custom", "Custom", null, null));

  static DownloadOffloadMethod byId(String id) {
    return METHODS.stream().filter(method -> method.id().equals(id)).findFirst().orElse(null);
  }

  static boolean validCustomHeader(String name) {
    if (name == null || !FIELD_NAME.matcher(name).matches()) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return !RESERVED_FIELDS.contains(lower)
        && !lower.startsWith("content-")
        && !lower.startsWith("access-control-")
        && !lower.startsWith("cross-origin-")
        && !lower.startsWith("docker-")
        && (!lower.startsWith("x-accel-") || lower.equals("x-accel-redirect"));
  }

  Contract resolve(String customHeader, String customTarget) {
    if ("custom".equals(id)) {
      return new Contract(customHeader, customTarget,
          "custom; header=" + customHeader + "; target=" + customTarget);
    }
    return new Contract(header, target, id);
  }

  record Contract(String header, String target, String marker) {}
}
