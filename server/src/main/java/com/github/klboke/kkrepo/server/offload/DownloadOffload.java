package com.github.klboke.kkrepo.server.offload;

import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.INTERNAL_PREFIX;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.INTERNAL_URI;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.MOUNT_PREFIX;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.PROBE_HEADER;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.REQUEST_HEADER;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.VERIFIED_HEADER;

import com.github.klboke.kkrepo.core.BlobOffloadSource;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Hands an already authorized full blob response to a trusted gateway. */
@Component
public class DownloadOffload {
  private static final List<String> REQUEST_CONDITIONS = List.of(
      HttpHeaders.RANGE, HttpHeaders.IF_RANGE, HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH,
      HttpHeaders.IF_MODIFIED_SINCE, HttpHeaders.IF_UNMODIFIED_SINCE);
  private static final Pattern BLOB_KEY = Pattern.compile("repositories/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+");

  private static final Pattern PROBE = Pattern.compile("[a-f0-9]{32}");

  private final DownloadOffloadSettings settings;

  public DownloadOffload(DownloadOffloadSettings settings) {
    this.settings = settings;
  }

  public boolean tryHandoff(HttpServletRequest request, int status, HttpHeaders headers, InputStream body) {
    if (request == null || status != 200 || !"GET".equals(request.getMethod())
        || !(body instanceof BlobOffloadSource source)
        || headers.getContentLength() <= 0 || headers.containsHeader(HttpHeaders.CONTENT_RANGE)
        || headers.containsHeader(HttpHeaders.CONTENT_ENCODING)
        || REQUEST_CONDITIONS.stream().anyMatch(name -> request.getHeader(name) != null)) {
      return false;
    }
    var markers = request.getHeaders(REQUEST_HEADER);
    if (markers == null || !markers.hasMoreElements() || !settings.trusted(request)) {
      return false;
    }
    String marker = markers.nextElement();
    if (markers.hasMoreElements()) {
      return false;
    }
    var saved = settings.runtime();
    if (saved == null || !saved.enabled() || !saved.contract().marker().equals(marker)) {
      return false;
    }
    var target = source.offloadTarget(headers.getContentLength()).orElse(null);
    if (target == null || !saved.storeIds().contains(target.storeId()) || !safeKey(target.objectKey())) {
      return false;
    }
    try {
      body.close();
    } catch (IOException error) {
      throw new java.io.UncheckedIOException("Could not close blob response before download offload", error);
    }
    var contract = saved.contract();
    String prefix = INTERNAL_URI.equals(contract.target()) ? INTERNAL_PREFIX : MOUNT_PREFIX;
    String value = prefix + "/" + target.storeId() + "/" + target.objectKey();
    headers.set(contract.header(), value);
    String nonce = request.getHeader(PROBE_HEADER);
    String verified = nonce != null && PROBE.matcher(nonce).matches() ? nonce + ":" + target.storeId() : null;
    if (verified != null) {
      headers.set(VERIFIED_HEADER, verified);
    }
    DownloadOffloadResponseFilter.approve(request, contract.header(), value, verified);
    headers.remove(HttpHeaders.CONTENT_LENGTH);
    return true;
  }

  private static boolean safeKey(String key) {
    return BLOB_KEY.matcher(key).matches()
        && !key.endsWith("/.") && !key.endsWith("/..") && !key.contains("/./") && !key.contains("/../");
  }
}
