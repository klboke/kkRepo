package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class WelcomeSupportedFormatsContractTest {

  @Test
  void welcomePageStaysAlignedWithRepositoryFormatCatalog() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String formatIcons = resource("/META-INF/resources/browse/assets/format-icons.css");
    int formatCount = RepositoryFormat.values().length;

    for (RepositoryFormat format : RepositoryFormat.values()) {
      assertEquals(1, occurrences(index, "data-format=\"" + format.id() + "\""), format.id());
      assertEquals(1, occurrences(
          formatIcons,
          ".format-logo-" + iconName(format) + " {"), format.id());
    }
    assertEquals(1, occurrences(index, ">" + formatCount + " formats</span>"));
    assertEquals(formatCount, occurrences(index, "class=\"format-item\""));
  }

  private static String iconName(RepositoryFormat format) {
    return format == RepositoryFormat.MAVEN2 ? "maven" : format.id();
  }

  private static int occurrences(String value, String token) {
    return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
