package com.github.klboke.kkrepo.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.kkrepo.test.TestSignedPlugin;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLoaderTest {
  private static final String PASSWORD = "changeit";
  private static final String CLASS_ENTRY =
      TestSignedPlugin.class.getName().replace('.', '/') + ".class";
  private static final String SERVICE_ENTRY =
      "META-INF/services/" + KkRepoPlugin.class.getName();

  @Test
  void loadsSignedPlugin(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    Path jar = fixture.plugins().resolve("test-plugin.jar");
    createJar(jar, "1.0", true, fixture.keystore());

    PluginLoader loader = new PluginLoader(
        fixture.plugins(), fixture.truststore(), PASSWORD.toCharArray());
    try (LoadedPlugins loaded = loader.load()) {
      assertEquals(1, loaded.plugins().size());
      assertEquals(1, loaded.remoteTransports().size());
      assertEquals("test-signed", loaded.remoteTransports().getFirst().type());
      assertEquals(1, loaded.repositoryFormats().size());
      assertEquals("test-format", loaded.repositoryFormats().getFirst().id().value());
      assertEquals(1, loaded.repositoryProtocolHandlers().size());
    }
  }

  @Test
  void rejectsUnsignedPlugin(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    Path jar = fixture.plugins().resolve("unsigned.jar");
    createJar(jar, "1.0", false, null);

    PluginLoader loader = new PluginLoader(
        fixture.plugins(), fixture.truststore(), PASSWORD.toCharArray());
    assertThrows(PluginException.class, loader::load);
  }

  @Test
  void rejectsPluginSignedByAnotherKey(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    Path otherKeystore = fixture.keys().resolve("other.p12");
    generateKeyPair(otherKeystore, "plugin");
    Path jar = fixture.plugins().resolve("wrong-signer.jar");
    createJar(jar, "1.0", true, otherKeystore);

    PluginLoader loader = new PluginLoader(
        fixture.plugins(), fixture.truststore(), PASSWORD.toCharArray());
    assertThrows(PluginException.class, loader::load);
  }

  @Test
  void rejectsTamperedPlugin(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    Path signed = temp.resolve("signed.jar");
    createJar(signed, "1.0", true, fixture.keystore());
    Path tampered = fixture.plugins().resolve("tampered.jar");
    tamper(signed, tampered);

    PluginLoader loader = new PluginLoader(
        fixture.plugins(), fixture.truststore(), PASSWORD.toCharArray());
    assertThrows(PluginException.class, loader::load);
  }

  @Test
  void rejectsIncompatibleApiVersion(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    Path jar = fixture.plugins().resolve("future-api.jar");
    createJar(jar, "2.0", true, fixture.keystore());

    PluginLoader loader = new PluginLoader(
        fixture.plugins(), fixture.truststore(), PASSWORD.toCharArray());
    assertThrows(PluginException.class, loader::load);
  }

  private static Fixture fixture(Path temp) throws Exception {
    Path plugins = Files.createDirectories(temp.resolve("plugins"));
    Path keys = Files.createDirectories(temp.resolve("keys"));
    Path keystore = keys.resolve("plugin.p12");
    Path truststore = keys.resolve("trust.p12");
    generateKeyPair(keystore, "plugin");
    trust(keystore, "plugin", truststore);
    return new Fixture(plugins, keys, keystore, truststore);
  }

  private static void generateKeyPair(Path keystore, String alias) throws Exception {
    run(tool("keytool"),
        "-genkeypair",
        "-alias", alias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "365",
        "-dname", "CN=Test Plugin",
        "-keystore", keystore.toString(),
        "-storetype", "PKCS12",
        "-storepass", PASSWORD,
        "-keypass", PASSWORD);
  }

  private static void trust(Path keystore, String alias, Path truststore) throws Exception {
    Path certificate = keystore.resolveSibling(alias + ".cer");
    run(tool("keytool"),
        "-exportcert",
        "-alias", alias,
        "-keystore", keystore.toString(),
        "-storepass", PASSWORD,
        "-file", certificate.toString());
    run(tool("keytool"),
        "-importcert",
        "-alias", alias,
        "-file", certificate.toString(),
        "-keystore", truststore.toString(),
        "-storetype", "PKCS12",
        "-storepass", PASSWORD,
        "-noprompt");
  }

  private static void createJar(
      Path jar, String apiVersion, boolean signed, Path keystore) throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      add(output, CLASS_ENTRY, classBytes());
      add(output, SERVICE_ENTRY,
          (TestSignedPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
      add(output, PluginLoader.DESCRIPTOR_PATH, descriptor(apiVersion));
    }
    if (signed) {
      run(tool("jarsigner"),
          "-keystore", keystore.toString(),
          "-storetype", "PKCS12",
          "-storepass", PASSWORD,
          "-keypass", PASSWORD,
          jar.toString(),
          "plugin");
    }
  }

  private static byte[] descriptor(String apiVersion) {
    return """
        {"id":"test-signed","version":"1.0.0","apiVersion":"%s","mainClass":"%s",
         "capabilities":["remote.transport"]}
        """.formatted(apiVersion, TestSignedPlugin.class.getName())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] classBytes() throws Exception {
    try (InputStream input =
             TestSignedPlugin.class.getClassLoader().getResourceAsStream(CLASS_ENTRY)) {
      if (input == null) {
        throw new IllegalStateException("Missing test class resource: " + CLASS_ENTRY);
      }
      return input.readAllBytes();
    }
  }

  private static void add(JarOutputStream output, String name, byte[] bytes) throws Exception {
    output.putNextEntry(new JarEntry(name));
    output.write(bytes);
    output.closeEntry();
  }

  private static void tamper(Path signed, Path outputJar) throws Exception {
    try (JarFile input = new JarFile(signed.toFile());
         JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputJar))) {
      Enumeration<JarEntry> entries = input.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        byte[] bytes = input.getInputStream(entry).readAllBytes();
        if (CLASS_ENTRY.equals(entry.getName())) {
          bytes[bytes.length - 1] ^= 0x01;
        }
        output.putNextEntry(new JarEntry(entry.getName()));
        output.write(bytes);
        output.closeEntry();
      }
    }
  }

  private static String tool(String name) {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    String executable = windows ? name + ".exe" : name;
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private static void run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
    }
  }

  private record Fixture(
      Path plugins, Path keys, Path keystore, Path truststore) {
  }
}
