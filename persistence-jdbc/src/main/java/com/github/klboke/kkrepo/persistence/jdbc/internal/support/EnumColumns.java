package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.util.Locale;

public final class EnumColumns {
  private EnumColumns() {
  }

  public static String write(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  public static <E extends Enum<E>> E read(Class<E> enumType, String value) {
    return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
  }
}
