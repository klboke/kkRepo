package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.plugin.api.PluginException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Child-first classloader for one plugin JAR. The plugin API and the JDK are parent-first; the
 * plugin's own packages are loaded from the JAR so two plugins may ship different versions of the
 * same library.
 */
public final class PluginClassLoader extends URLClassLoader {
  private static final List<String> PARENT_FIRST = List.of(
      "java.",
      "javax.",
      "jdk.",
      "sun.",
      "com.fasterxml.",
      "com.github.klboke.kkrepo.plugin.api.");

  public PluginClassLoader(Path jar, ClassLoader parent) throws PluginException {
    super(new URL[] {url(jar)}, parent);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> type = findLoadedClass(name);
      if (type == null) {
        if (parentFirst(name)) {
          type = super.loadClass(name, false);
        } else {
          try {
            type = findClass(name);
          } catch (ClassNotFoundException missing) {
            type = super.loadClass(name, false);
          }
        }
      }
      if (resolve) {
        resolveClass(type);
      }
      return type;
    }
  }

  private static boolean parentFirst(String name) {
    return PARENT_FIRST.stream().anyMatch(name::startsWith);
  }

  private static URL url(Path jar) throws PluginException {
    try {
      return jar.toUri().toURL();
    } catch (MalformedURLException invalid) {
      throw new PluginException("Invalid plugin JAR path: " + jar, invalid);
    }
  }
}
