package com.github.klboke.kkrepo.server.support;

import java.lang.reflect.Field;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;

/**
 * Creates tar entries without Commons Compress normalizing the entry name.
 *
 * <p>The archive inspectors must reject raw names such as {@code C:/escape} and
 * {@code dir\escape}. The public {@link TarArchiveEntry} constructors strip absolute paths and
 * convert backslashes to slashes before the archive is written, so unsafe-path tests need to restore
 * the exact bytes that a real archive would contain.
 */
public final class TarTestEntries {
  private static final Field NAME = nameField();

  private TarTestEntries() {
  }

  public static TarArchiveEntry entry(String name, byte linkFlag) {
    TarArchiveEntry entry = new TarArchiveEntry(name, linkFlag, true);
    try {
      NAME.set(entry, name);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException("Cannot preserve tar entry name", error);
    }
    return entry;
  }

  private static Field nameField() {
    try {
      Field field = TarArchiveEntry.class.getDeclaredField("name");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Cannot access tar entry name", error);
    }
  }
}
