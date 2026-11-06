package com.github.klboke.kkrepo.core.blobstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlobStoreProvidersTest {

  @Test
  void resolvesProviderByType() {
    StubProvider file = new StubProvider(BlobStoreTypes.FILE);
    StubProvider s3 = new StubProvider(BlobStoreTypes.S3);
    StubProvider azure = new StubProvider(BlobStoreTypes.AZURE);
    BlobStoreProviders providers = new BlobStoreProviders(List.of(file, s3, azure));

    assertSame(azure, providers.require(descriptor("azure", Map.of())));
    assertSame(file, providers.require(descriptor("file", Map.of())));
    assertSame(s3, providers.require(descriptor("s3", Map.of())));
  }

  @Test
  void matchesLegacyRowsThatCarryTheDiscriminatorInTheEngineAttribute() {
    StubProvider file = new StubProvider(BlobStoreTypes.FILE);
    StubProvider s3 = new StubProvider(BlobStoreTypes.S3);
    BlobStoreProviders providers = new BlobStoreProviders(List.of(file, s3));

    assertSame(file, providers.require(descriptor("", Map.of("engine", "file"))));
  }

  @Test
  void ambiguousRowsResolveTheSameWayWhateverTheRegistrationOrder() {
    StubProvider file = new StubProvider(BlobStoreTypes.FILE);
    StubProvider s3 = new StubProvider(BlobStoreTypes.S3);
    StubProvider azure = new StubProvider(BlobStoreTypes.AZURE);
    BlobStoreDescriptor s3TypeFileEngine = descriptor("s3", Map.of("engine", "file"));
    BlobStoreDescriptor s3TypeAzureEngine = descriptor("s3", Map.of("engine", "azure"));

    for (List<BlobStoreProvider> order : List.of(
        List.<BlobStoreProvider>of(file, s3, azure),
        List.<BlobStoreProvider>of(s3, azure, file),
        List.<BlobStoreProvider>of(azure, s3, file))) {
      BlobStoreProviders providers = new BlobStoreProviders(order);
      // The S3 fallback is consulted last, as when "not a file store" used to imply S3.
      assertSame(file, providers.require(s3TypeFileEngine));
      assertSame(azure, providers.require(s3TypeAzureEngine));
      assertSame(s3, providers.all().get(providers.all().size() - 1));
    }
  }

  @Test
  void unknownTypesFallBackToS3SoPreExistingRowsKeepResolving() {
    StubProvider file = new StubProvider(BlobStoreTypes.FILE);
    StubProvider s3 = new StubProvider(BlobStoreTypes.S3);
    BlobStoreProviders providers = new BlobStoreProviders(List.of(file, s3));

    assertSame(s3, providers.require(descriptor("", Map.of())));
    assertSame(s3, providers.require(descriptor("something-else", Map.of())));
  }

  @Test
  void requireFailsWithAUsefulMessageWhenNoProviderMatches() {
    BlobStoreProviders providers = new BlobStoreProviders(List.of());

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> providers.require(descriptor("azure", Map.of())));
    assertTrue(error.getMessage().contains("azure"), error.getMessage());
  }

  @Test
  void descriptorReadsTypedAttributesWithFallbacks() {
    BlobStoreDescriptor descriptor = descriptor("azure", Map.of(
        "blockSizeBytes", 1024,
        "uploadConcurrency", "8",
        "enabled", true,
        "blank", ""));

    assertEquals(1024L, descriptor.longAttr("blockSizeBytes", 1));
    assertEquals(8, descriptor.intAttr("uploadConcurrency", 1));
    assertTrue(descriptor.boolAttr("enabled", false));
    assertEquals("fallback", descriptor.stringAttr("blank", "fallback"));
    assertEquals("fallback", descriptor.stringAttr("absent", "fallback"));
  }

  @Test
  void presentAttributeDistinguishesAnExplicitBlankFromAnAbsentKey() {
    BlobStoreDescriptor explicit = descriptor("azure", Map.of("accountKey", ""));
    BlobStoreDescriptor absent = descriptor("azure", Map.of());

    assertEquals("", explicit.presentAttr("accountKey", "inherited"));
    assertEquals("inherited", absent.presentAttr("accountKey", "inherited"));
  }

  private static BlobStoreDescriptor descriptor(String type, Map<String, Object> attributes) {
    return new BlobStoreDescriptor(1, "store", type, "", "", "bucket", "", attributes);
  }

  private static final class StubProvider implements BlobStoreProvider {
    private final String type;

    private StubProvider(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public boolean supports(BlobStoreDescriptor descriptor) {
      return type.equals(descriptor.normalizedType()) || type.equals(descriptor.normalizedEngine());
    }

    @Override
    public String signature(BlobStoreDescriptor descriptor) {
      return type + "-signature";
    }

    @Override
    public BlobStorage create(BlobStoreDescriptor descriptor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeStoreIds) {
      return Set.of();
    }
  }
}
