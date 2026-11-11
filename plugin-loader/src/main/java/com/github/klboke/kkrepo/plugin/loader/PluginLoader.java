package com.github.klboke.kkrepo.plugin.loader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers, verifies and loads signed plugin JARs from a directory.
 *
 * <p>Verification happens before any plugin class is loaded. A plugin with an unsigned entry, an
 * untrusted signer, a mixed signer, an invalid signature or an incompatible API version aborts the
 * whole load.
 */
public final class PluginLoader {
  public static final String DESCRIPTOR_PATH = "META-INF/kkrepo-plugin.json";

  private final Path pluginsDirectory;
  private final SignedJarVerifier verifier;
  private final ObjectMapper mapper;

  public PluginLoader(Path pluginsDirectory, Path truststore, char[] truststorePassword)
      throws PluginException {
    this.pluginsDirectory = pluginsDirectory;
    this.verifier = new SignedJarVerifier(truststore, truststorePassword);
    this.mapper = new ObjectMapper();
  }

  public LoadedPlugins load() throws PluginException {
    if (!Files.isDirectory(pluginsDirectory)) {
      return new LoadedPlugins(List.of(), List.of(), List.of(), List.of());
    }
    List<Path> jars;
    try (Stream<Path> entries = Files.list(pluginsDirectory)) {
      jars = entries
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".jar"))
          .sorted()
          .toList();
    } catch (IOException invalid) {
      throw new PluginException("Unable to list plugin directory: " + pluginsDirectory, invalid);
    }

    List<PluginClassLoader> classLoaders = new ArrayList<>();
    DefaultPluginContext context = new DefaultPluginContext();
    List<KkRepoPlugin> plugins = new ArrayList<>();
    Set<String> pluginIds = new HashSet<>();
    try {
      for (Path jar : jars) {
        verifier.verify(jar);
        PluginDescriptor descriptor = readDescriptor(jar);
        if (!PluginApiVersion.CURRENT.equals(descriptor.apiVersion())) {
          throw new PluginException("Plugin " + descriptor.id() + " requires API "
              + descriptor.apiVersion() + ", runtime provides " + PluginApiVersion.CURRENT);
        }
        if (!pluginIds.add(descriptor.id())) {
          throw new PluginException("Duplicate plugin id: " + descriptor.id());
        }
        PluginClassLoader classLoader = new PluginClassLoader(
            jar, KkRepoPlugin.class.getClassLoader());
        classLoaders.add(classLoader);
        KkRepoPlugin plugin = instantiate(descriptor, classLoader);
        plugin.contribute(context);
        plugins.add(plugin);
      }
      return new LoadedPlugins(
          plugins,
          context.remoteTransports(),
          context.repositoryFormats(),
          context.repositoryProtocolHandlers(),
          classLoaders);
    } catch (PluginException | RuntimeException failure) {
      close(classLoaders);
      if (failure instanceof PluginException pluginFailure) {
        throw pluginFailure;
      }
      throw new PluginException("Unable to load plugins from " + pluginsDirectory, failure);
    }
  }

  private PluginDescriptor readDescriptor(Path jar) throws PluginException {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      JarEntry entry = jarFile.getJarEntry(DESCRIPTOR_PATH);
      if (entry == null) {
        throw new PluginException("Missing " + DESCRIPTOR_PATH + " in " + jar);
      }
      try (InputStream input = jarFile.getInputStream(entry)) {
        PluginDescriptor descriptor = mapper.readValue(input, PluginDescriptor.class);
        if (descriptor == null) {
          throw new PluginException("Empty plugin descriptor in " + jar);
        }
        return descriptor;
      }
    } catch (JsonProcessingException invalid) {
      throw new PluginException("Invalid plugin descriptor in " + jar, invalid);
    } catch (IOException invalid) {
      throw new PluginException("Unable to read plugin descriptor in " + jar, invalid);
    }
  }

  private static KkRepoPlugin instantiate(
      PluginDescriptor descriptor, PluginClassLoader classLoader) throws PluginException {
    ServiceLoader<KkRepoPlugin> serviceLoader =
        ServiceLoader.load(KkRepoPlugin.class, classLoader);
    for (KkRepoPlugin plugin : serviceLoader) {
      if (plugin.getClass().getName().equals(descriptor.mainClass())) {
        return plugin;
      }
    }
    throw new PluginException("Plugin main class not found in META-INF/services: "
        + descriptor.mainClass());
  }

  private static void close(List<PluginClassLoader> classLoaders) {
    for (PluginClassLoader classLoader : classLoaders) {
      try {
        classLoader.close();
      } catch (IOException ignored) {
        // Best effort: the load failure is the actionable error.
      }
    }
  }
}
