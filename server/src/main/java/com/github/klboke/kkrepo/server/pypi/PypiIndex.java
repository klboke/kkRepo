package com.github.klboke.kkrepo.server.pypi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PypiIndex {
  private static final Pattern LINK = Pattern.compile(
      "<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern HREF = Pattern.compile(
      "\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern REQUIRES = Pattern.compile(
      "\\bdata-requires-python\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern UPLOAD_TIME = Pattern.compile(
      "\\bdata-upload-time\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final ObjectMapper JSON = new ObjectMapper();

  private PypiIndex() {
  }

  /**
   * Parses a PEP 691 JSON project page. Files without a parseable PEP 700 {@code upload-time} keep a
   * null time so callers can treat them as unknown.
   */
  static List<PypiLink> parseJson(String json) throws IOException {
    List<PypiLink> result = new ArrayList<>();
    JsonNode files = JSON.readTree(json == null ? "" : json).path("files");
    for (JsonNode file : files) {
      String name = file.path("filename").asText("");
      String url = file.path("url").asText("");
      if (name.isEmpty() || url.isEmpty()) continue;
      String sha256 = file.path("hashes").path("sha256").asText("");
      String href = sha256.isEmpty() ? url : url + "#sha256=" + sha256;
      String requires = file.path("requires-python").asText("");
      result.add(new PypiLink(name, href, requires, parseInstant(file.path("upload-time").asText(null))));
    }
    return result;
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  static List<PypiLink> parse(String html) {
    List<PypiLink> result = new ArrayList<>();
    Matcher matcher = LINK.matcher(html == null ? "" : html);
    while (matcher.find()) {
      String attrs = matcher.group(1);
      String href = attr(attrs, HREF);
      if (href == null) continue;
      String text = stripTags(matcher.group(2)).trim();
      String requires = attr(attrs, REQUIRES);
      String uploaded = attr(attrs, UPLOAD_TIME);
      result.add(new PypiLink(
          unescape(text), unescape(href), unescape(requires), parseInstant(uploaded)));
    }
    return result;
  }

  static String buildRoot(Collection<PypiLink> links) {
    StringBuilder out = new StringBuilder();
    out.append("<html>\n");
    out.append("<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"><title>Simple index</title></head>\n");
    out.append("<body>\n");
    links.stream()
        .sorted(Comparator.comparing(l -> l.file().toLowerCase(Locale.ENGLISH)))
        .forEach(link -> out.append("<a href=\"")
            .append(escapeAttr(link.href()))
            .append("\">")
            .append(escapeText(link.file()))
            .append("</a><br/>\n"));
    out.append("</body>\n");
    out.append("</html>\n");
    return out.toString();
  }

  static String buildProject(String projectName, Collection<PypiLink> links) {
    return buildProject(projectName, links, false);
  }

  /**
   * {@code includeUploadTimes} adds a {@code data-upload-time} attribute so the cached copy keeps the
   * publish time for later release-age filtering; it is stripped again before serving to clients.
   */
  static String buildProject(
      String projectName, Collection<PypiLink> links, boolean includeUploadTimes) {
    StringBuilder out = new StringBuilder();
    out.append("<html lang=\"en\">\n");
    out.append("<head><title>Links for ").append(escapeText(projectName)).append("</title>\n");
    out.append("  <meta name=\"api-version\" value=\"2\"/>\n");
    out.append("</head>\n");
    out.append("<body><h1>Links for ").append(escapeText(projectName)).append("</h1>\n");
    links.stream()
        .sorted(Comparator.comparing(l -> l.file().toLowerCase(Locale.ENGLISH)))
        .forEach(link -> {
          out.append("    <a href=\"").append(escapeAttr(link.href())).append("\" rel=\"internal\"");
          if (!link.requiresPython().isBlank()) {
            out.append(" data-requires-python=\"").append(escapeAttr(link.requiresPython())).append("\"");
          }
          if (includeUploadTimes && link.uploadTime() != null) {
            out.append(" data-upload-time=\"").append(link.uploadTime()).append("\"");
          }
          out.append(">").append(escapeText(link.file())).append("</a><br/>\n");
        });
    out.append("</body>\n");
    out.append("</html>\n");
    return out.toString();
  }

  static List<PypiLink> rewriteRootLinks(List<PypiLink> links) {
    List<PypiLink> result = new ArrayList<>(links.size());
    for (PypiLink link : links) {
      String href = link.href();
      int idx = href.indexOf("/simple/");
      if (idx >= 0) {
        href = href.substring(idx + "/simple/".length());
      }
      result.add(new PypiLink(link.file(), href, link.requiresPython(), link.uploadTime()));
    }
    return result;
  }

  static List<PypiLink> rewriteProjectLinks(String normalizedName, List<PypiLink> links) {
    List<PypiLink> result = new ArrayList<>(links.size());
    for (PypiLink link : links) {
      String version = PypiPaths.versionFromFilename(link.file());
      if (version.isBlank()) continue;
      String href = "../../" + PypiPaths.PACKAGES_PREFIX + normalizedName + "/" + version + "/" + link.file();
      int hashIdx = link.href().indexOf('#');
      if (hashIdx >= 0) {
        href += link.href().substring(hashIdx);
      }
      result.add(new PypiLink(link.file(), href, link.requiresPython(), link.uploadTime()));
    }
    return result;
  }

  static boolean validatesForProject(String normalizedName, List<PypiLink> links) {
    String normalized = PypiPaths.normalizeName(normalizedName);
    return links.stream()
        .map(PypiLink::file)
        .map(PypiPaths::normalizeName)
        .anyMatch(file -> file.startsWith(normalized));
  }

  private static String attr(String attrs, Pattern pattern) {
    Matcher matcher = pattern.matcher(attrs == null ? "" : attrs);
    if (!matcher.find()) return null;
    for (int i = 2; i <= matcher.groupCount(); i++) {
      if (matcher.group(i) != null) return matcher.group(i);
    }
    return "";
  }

  private static String stripTags(String html) {
    return (html == null ? "" : html).replaceAll("<[^>]*>", "");
  }

  private static String escapeText(String value) {
    return String.valueOf(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static String escapeAttr(String value) {
    return escapeText(value).replace("\"", "&quot;");
  }

  private static String unescape(String value) {
    if (value == null) return "";
    return value
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");
  }
}
