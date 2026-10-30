package com.github.klboke.kkrepo.protocol.maven.metadata;

import static com.github.klboke.kkrepo.protocol.maven.MavenConstants.METADATA_DOTLESS_TIMESTAMP;
import static com.github.klboke.kkrepo.protocol.maven.MavenConstants.METADATA_DOTTED_TIMESTAMP;

import com.github.klboke.kkrepo.protocol.maven.metadata.Maven2Metadata.Plugin;
import com.github.klboke.kkrepo.protocol.maven.metadata.Maven2Metadata.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * Serializes {@link Maven2Metadata} to the Maven 2 {@code maven-metadata.xml} wire format and
 * reads enough of that XML back to merge across group members. Uses plain StAX — no joda, no
 * jdom, no extra deps.
 */
public final class MavenMetadataXml {
  private static final XMLOutputFactory OUT = XMLOutputFactory.newInstance();
  private static final XMLInputFactory IN;
  static {
    IN = XMLInputFactory.newInstance();
    IN.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    IN.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
  }

  private MavenMetadataXml() {}

  public static byte[] write(Maven2Metadata meta) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
      XMLStreamWriter w = OUT.createXMLStreamWriter(baos, "UTF-8");
      w.writeStartDocument("UTF-8", "1.0");
      w.writeCharacters("\n");
      w.writeStartElement("metadata");
      switch (meta.level()) {
        case ARTIFACT -> writeArtifactLevel(w, meta);
        case BASE_VERSION -> writeBaseVersionLevel(w, meta);
        case GROUP -> writeGroupLevel(w, meta);
      }
      w.writeEndElement();
      w.writeEndDocument();
      w.flush();
      w.close();
      return baos.toByteArray();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("Failed to serialize maven-metadata.xml", e);
    }
  }

  private static void writeArtifactLevel(XMLStreamWriter w, Maven2Metadata meta) throws XMLStreamException {
    el(w, "groupId", meta.groupId());
    el(w, "artifactId", meta.artifactId());
    w.writeStartElement("versioning");
    var bv = meta.baseVersions();
    el(w, "latest", bv.latest());
    if (bv.release() != null) el(w, "release", bv.release());
    w.writeStartElement("versions");
    for (String v : bv.versions()) {
      el(w, "version", v);
    }
    w.writeEndElement();
    if (meta.lastUpdated() != null) el(w, "lastUpdated", METADATA_DOTLESS_TIMESTAMP.format(meta.lastUpdated()));
    w.writeEndElement();
  }

  private static void writeBaseVersionLevel(XMLStreamWriter w, Maven2Metadata meta) throws XMLStreamException {
    el(w, "groupId", meta.groupId());
    el(w, "artifactId", meta.artifactId());
    el(w, "version", meta.version());
    w.writeStartElement("versioning");
    var snaps = meta.snapshots();
    if (snaps.timestamp() != null) {
      w.writeStartElement("snapshot");
      el(w, "timestamp", METADATA_DOTTED_TIMESTAMP.format(java.time.Instant.ofEpochMilli(snaps.timestamp())));
      el(w, "buildNumber", Integer.toString(snaps.buildNumber()));
      w.writeEndElement();
    }
    if (meta.lastUpdated() != null) el(w, "lastUpdated", METADATA_DOTLESS_TIMESTAMP.format(meta.lastUpdated()));
    if (!snaps.snapshots().isEmpty()) {
      w.writeStartElement("snapshotVersions");
      for (Snapshot s : snaps.snapshots()) {
        w.writeStartElement("snapshotVersion");
        if (s.classifier() != null) el(w, "classifier", s.classifier());
        el(w, "extension", s.extension());
        el(w, "value", s.version());
        el(w, "updated", METADATA_DOTLESS_TIMESTAMP.format(s.lastUpdated()));
        w.writeEndElement();
      }
      w.writeEndElement();
    }
    w.writeEndElement();
  }

  private static void writeGroupLevel(XMLStreamWriter w, Maven2Metadata meta) throws XMLStreamException {
    if (meta.plugins() == null || meta.plugins().isEmpty()) return;
    w.writeStartElement("plugins");
    for (Plugin p : meta.plugins()) {
      w.writeStartElement("plugin");
      el(w, "name", p.name());
      el(w, "prefix", p.prefix());
      el(w, "artifactId", p.artifactId());
      w.writeEndElement();
    }
    w.writeEndElement();
  }

  private static void el(XMLStreamWriter w, String name, String value) throws XMLStreamException {
    w.writeStartElement(name);
    if (value != null) w.writeCharacters(value);
    w.writeEndElement();
  }

  /**
   * Very small reader used by the group-merge pipeline. Returns a {@link Parsed} struct that the
   * merger can fold into a single output.
   */
  public static Parsed read(byte[] xml) throws IOException {
    Parsed result = new Parsed();
    try (InputStream in = new ByteArrayInputStream(xml)) {
      XMLStreamReader r = IN.createXMLStreamReader(in);
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamReader.START_ELEMENT) {
          switch (r.getLocalName()) {
            case "groupId" -> result.groupId = r.getElementText();
            case "artifactId" -> result.artifactId = r.getElementText();
            case "version" -> result.version = r.getElementText();
            case "lastUpdated" -> result.lastUpdated = r.getElementText();
            case "release" -> result.release = r.getElementText();
            case "latest" -> result.latest = r.getElementText();
            case "versions" -> readVersions(r, result);
            case "snapshot" -> readSnapshot(r, result);
            case "snapshotVersions" -> readSnapshotVersions(r, result);
            case "plugins" -> readPlugins(r, result);
            default -> {}
          }
        }
      }
    } catch (XMLStreamException e) {
      throw new IOException("Failed to parse maven-metadata.xml", e);
    }
    return result;
  }

  private static void readVersions(XMLStreamReader r, Parsed result) throws XMLStreamException {
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamReader.START_ELEMENT && "version".equals(r.getLocalName())) {
        result.versions.add(r.getElementText());
      } else if (e == XMLStreamReader.END_ELEMENT && "versions".equals(r.getLocalName())) {
        return;
      }
    }
  }

  private static void readSnapshot(XMLStreamReader r, Parsed result) throws XMLStreamException {
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamReader.START_ELEMENT) {
        switch (r.getLocalName()) {
          case "timestamp" -> result.snapshotTimestamp = r.getElementText();
          case "buildNumber" -> {
            try {
              result.snapshotBuildNumber = Integer.parseInt(r.getElementText());
            } catch (NumberFormatException ignored) {}
          }
          default -> {}
        }
      } else if (e == XMLStreamReader.END_ELEMENT && "snapshot".equals(r.getLocalName())) {
        return;
      }
    }
  }

  private static void readSnapshotVersions(XMLStreamReader r, Parsed result) throws XMLStreamException {
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamReader.START_ELEMENT && "snapshotVersion".equals(r.getLocalName())) {
        ParsedSnapshot s = new ParsedSnapshot();
        while (r.hasNext()) {
          int ee = r.next();
          if (ee == XMLStreamReader.START_ELEMENT) {
            switch (r.getLocalName()) {
              case "classifier" -> s.classifier = r.getElementText();
              case "extension" -> s.extension = r.getElementText();
              case "value" -> s.value = r.getElementText();
              case "updated" -> s.updated = r.getElementText();
              default -> {}
            }
          } else if (ee == XMLStreamReader.END_ELEMENT && "snapshotVersion".equals(r.getLocalName())) {
            break;
          }
        }
        result.snapshotVersions.add(s);
      } else if (e == XMLStreamReader.END_ELEMENT && "snapshotVersions".equals(r.getLocalName())) {
        return;
      }
    }
  }

  private static void readPlugins(XMLStreamReader r, Parsed result) throws XMLStreamException {
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamReader.START_ELEMENT && "plugin".equals(r.getLocalName())) {
        ParsedPlugin p = new ParsedPlugin();
        while (r.hasNext()) {
          int ee = r.next();
          if (ee == XMLStreamReader.START_ELEMENT) {
            switch (r.getLocalName()) {
              case "name" -> p.name = r.getElementText();
              case "prefix" -> p.prefix = r.getElementText();
              case "artifactId" -> p.artifactId = r.getElementText();
              default -> {}
            }
          } else if (ee == XMLStreamReader.END_ELEMENT && "plugin".equals(r.getLocalName())) {
            break;
          }
        }
        result.plugins.add(p);
      } else if (e == XMLStreamReader.END_ELEMENT && "plugins".equals(r.getLocalName())) {
        return;
      }
    }
  }

  public static final class Parsed {
    public String groupId;
    public String artifactId;
    public String version;
    public String latest;
    public String release;
    public String lastUpdated;
    public String snapshotTimestamp;
    public int snapshotBuildNumber;
    public final java.util.List<String> versions = new java.util.ArrayList<>();
    public final java.util.List<ParsedSnapshot> snapshotVersions = new java.util.ArrayList<>();
    public final java.util.List<ParsedPlugin> plugins = new java.util.ArrayList<>();
  }

  public static final class ParsedSnapshot {
    public String classifier;
    public String extension;
    public String value;
    public String updated;
  }

  public static final class ParsedPlugin {
    public String name;
    public String prefix;
    public String artifactId;
  }

  // unused but kept handy for future utilities (avoid silencing UTF-8 imports)
  static byte[] noopEncode(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
