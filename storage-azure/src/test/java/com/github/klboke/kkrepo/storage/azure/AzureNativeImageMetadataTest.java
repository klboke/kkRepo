package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.http.HttpClientProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Azure SDK ships its own GraalVM reachability metadata, so {@code storage-azure} deliberately
 * registers no {@code RuntimeHintsRegistrar} of its own. These checks fail if a dependency bump
 * drops that metadata, which would otherwise only surface as a broken native image at runtime.
 */
class AzureNativeImageMetadataTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "META-INF/native-image/com.azure/azure-core/reflect-config.json",
      "META-INF/native-image/com.azure/azure-core/resource-config.json",
      "META-INF/native-image/com.azure/azure-core/native-image.properties",
      "META-INF/native-image/com.azure/azure-core-http-netty/reflect-config.json",
      "META-INF/native-image/com.azure/azure-core-http-netty/native-image.properties",
      "META-INF/native-image/com.azure/azure-identity/reflect-config.json",
      "META-INF/native-image/com.azure/azure-storage-blob/reflect-config.json",
      "META-INF/native-image/com.azure/azure-storage-blob/resource-config.json"
  })
  void azureArtifactsShipReachabilityMetadata(String resource) {
    assertNotNull(AzureNativeImageMetadataTest.class.getClassLoader().getResource(resource),
        () -> "Missing GraalVM metadata " + resource
            + "; storage-azure relies on the SDK's bundled reachability metadata");
  }

  @Test
  void theHttpClientProviderResolvedAtRuntimeIsCoveredByBundledMetadata() throws IOException {
    List<String> providers = new ArrayList<>();
    ServiceLoader.load(HttpClientProvider.class)
        .forEach(provider -> providers.add(provider.getClass().getName()));
    assertFalse(providers.isEmpty(), "No Azure HttpClientProvider is on the classpath");

    String reflectConfig = read("META-INF/native-image/com.azure/azure-core-http-netty/reflect-config.json");
    for (String provider : providers) {
      // GraalVM instantiates the provider reflectively through ServiceLoader.
      assertTrue(reflectConfig.contains(provider),
          () -> "HttpClientProvider " + provider + " is not registered for reflection");
    }
  }

  @Test
  void jacksonBuildTimeInitializationIsDeclaredForAzureCore() throws IOException {
    String properties = read("META-INF/native-image/com.azure/azure-core/native-image.properties");

    assertTrue(properties.contains("--initialize-at-build-time="), properties);
    assertTrue(properties.contains("com.fasterxml.jackson.core.JsonFactory"), properties);
  }

  private static String read(String resource) throws IOException {
    try (InputStream stream =
        AzureNativeImageMetadataTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream, () -> "Missing GraalVM metadata " + resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
