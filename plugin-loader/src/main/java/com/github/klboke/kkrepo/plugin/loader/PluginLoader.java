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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers built-in plugins on the host classpath and loads signed plugin JARs from a directory.
 *
 * <p>Built-in plugins ship with the host and are discovered through {@link ServiceLoader}; they are
 * registered first. External JARs are verified before any of their classes is loaded. A plugin with
 * an unsigned entry, an untrusted signer, a mixed signer, an invalid signature or an incompatible
 * API version aborts the whole load. Built-in and external plugins share one registration context:
 * a duplicate plugin id, format or route fails the load instead of replacing an implementation.
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

  /** Loads only the built-in plugins visible to {@code classLoader}. */
  public static LoadedPlugins loadBuiltins(ClassLoader classLoader) throws PluginException {
    return load(classLoader, null);
  }

  /** Loads only the signed external plugins. */
  public LoadedPlugins load() throws PluginException {
    return load(null, this);
  }

  /** Loads the built-in plugins visible to {@code builtinClassLoader}, then the signed plugins. */
  public LoadedPlugins load(ClassLoader builtinClassLoader) throws PluginException {
    return load(Objects.requireNonNull(builtinClassLoader, "builtinClassLoader"), this);
  }

  private static LoadedPlugins load(ClassLoader builtinClassLoader, PluginLoader external)
      throws PluginException {
    List<PluginClassLoader> classLoaders = new ArrayList<>();
    DefaultPluginContext context = new DefaultPluginContext();
    List<KkRepoPlugin> plugins = new ArrayList<>();
    Set<String> pluginIds = new HashSet<>();
    try {
      if (builtinClassLoader != null) {
        loadBuiltins(builtinClassLoader, context, plugins, pluginIds);
      }
      if (external != null) {
        external.loadExternal(context, plugins, pluginIds, classLoaders);
      }
      return new LoadedPlugins(
          plugins,
          context.remoteTransports(),
          context.repositoryFormats(),
          context.repositoryProtocolHandlers(),
          context.blobStoreProviders(),
          classLoaders);
    } catch (PluginException | RuntimeException failure) {
      close(classLoaders);
      if (failure instanceof PluginException pluginFailure) {
        throw pluginFailure;
      }
      throw new PluginException("Unable to load plugins: " + failure.getMessage(), failure);
    }
  }

  private static void loadBuiltins(
      ClassLoader classLoader,
      DefaultPluginContext context,
      List<KkRepoPlugin> plugins,
      Set<String> pluginIds) throws PluginException {
    List<ServiceLoader.Provider<KkRepoPlugin>> providers = ServiceLoader
        .load(KkRepoPlugin.class, classLoader)
        .stream()
        .sorted(Comparator.comparing(provider -> provider.type().getName()))
        .toList();
    for (ServiceLoader.Provider<KkRepoPlugin> provider : providers) {
      KkRepoPlugin plugin = provider.get();
      PluginDescriptor descriptor = plugin.descriptor();
      if (descriptor == null) {
        throw new PluginException("Built-in plugin has no descriptor: " + provider.type().getName());
      }
      if (!provider.type().getName().equals(descriptor.mainClass())) {
        throw new PluginException("Built-in plugin " + descriptor.id() + " declares main class "
            + descriptor.mainClass() + " but is provided by " + provider.type().getName());
      }
      register(descriptor, plugin, context, plugins, pluginIds);
    }
  }

  private void loadExternal(
      DefaultPluginContext context,
      List<KkRepoPlugin> plugins,
      Set<String> pluginIds,
      List<PluginClassLoader> classLoaders) throws PluginException {
    if (!Files.isDirectory(pluginsDirectory)) {
      return;
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

    for (Path jar : jars) {
      verifier.verify(jar);
      PluginDescriptor descriptor = readDescriptor(jar);
      PluginClassLoader classLoader = new PluginClassLoader(
          jar, KkRepoPlugin.class.getClassLoader());
      classLoaders.add(classLoader);
      requireCompatible(descriptor);
      if (pluginIds.contains(descriptor.id())) {
        throw new PluginException("Duplicate plugin id: " + descriptor.id());
      }
      register(descriptor, instantiate(descriptor, classLoader), context, plugins, pluginIds);
    }
  }

  private static void register(
      PluginDescriptor descriptor,
      KkRepoPlugin plugin,
      DefaultPluginContext context,
      List<KkRepoPlugin> plugins,
      Set<String> pluginIds) throws PluginException {
    requireCompatible(descriptor);
    if (!pluginIds.add(descriptor.id())) {
      throw new PluginException("Duplicate plugin id: " + descriptor.id());
    }
    try {
      plugin.contribute(context);
    } catch (IllegalArgumentException rejected) {
      throw new PluginException(
          "Plugin " + descriptor.id() + " registration rejected: " + rejected.getMessage(), rejected);
    }
    plugins.add(plugin);
  }

  private static void requireCompatible(PluginDescriptor descriptor) throws PluginException {
    if (!PluginApiVersion.CURRENT.equals(descriptor.apiVersion())) {
      throw new PluginException("Plugin " + descriptor.id() + " requires API "
          + descriptor.apiVersion() + ", runtime provides " + PluginApiVersion.CURRENT);
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
    // The JAR sees the host's built-in providers through its parent; only the declared main class
    // is instantiated, and it must come from the JAR itself so a JAR cannot re-register a built-in.
    for (ServiceLoader.Provider<KkRepoPlugin> provider
        : ServiceLoader.load(KkRepoPlugin.class, classLoader).stream().toList()) {
      if (provider.type().getName().equals(descriptor.mainClass())) {
        if (provider.type().getClassLoader() != classLoader) {
          throw new PluginException("Plugin main class " + descriptor.mainClass()
              + " is not defined by the plugin JAR");
        }
        return provider.get();
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
