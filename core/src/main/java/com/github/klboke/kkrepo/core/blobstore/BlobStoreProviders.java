package com.github.klboke.kkrepo.core.blobstore;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a blob-store row to the {@link BlobStoreProvider} that owns it.
 *
 * <p>Rows that no provider claims fall back to {@link BlobStoreTypes#S3}. That preserves the
 * historical behaviour where "not a file store" implied S3, so existing rows written before
 * {@code type} was populated consistently keep resolving to the S3 adapter.
 */
public final class BlobStoreProviders {
  private final List<BlobStoreProvider> providers;
  private final Map<String, BlobStoreProvider> byType;
  private final String fallbackType;

  public BlobStoreProviders(Collection<BlobStoreProvider> providers) {
    this(providers, BlobStoreTypes.S3);
  }

  public BlobStoreProviders(Collection<BlobStoreProvider> providers, String fallbackType) {
    this.fallbackType = BlobStoreDescriptor.normalize(fallbackType);
    this.providers = matchOrder(providers == null ? List.of() : providers, this.fallbackType);
    Map<String, BlobStoreProvider> index = new LinkedHashMap<>();
    for (BlobStoreProvider provider : this.providers) {
      index.putIfAbsent(BlobStoreDescriptor.normalize(provider.type()), provider);
    }
    this.byType = Map.copyOf(index);
  }

  /**
   * Fixes the order providers are asked in, independent of Spring's bean order. A row may name one
   * backend in {@code type} and another in its legacy {@code engine} attribute, and then more than
   * one provider claims it. Specific providers are consulted by type name and the fallback
   * provider last, which keeps the historical rule that a file engine wins over an S3 type.
   */
  private static List<BlobStoreProvider> matchOrder(
      Collection<BlobStoreProvider> providers, String fallbackType) {
    return providers.stream()
        .sorted(Comparator
            .comparing((BlobStoreProvider provider) ->
                BlobStoreDescriptor.normalize(provider.type()).equals(fallbackType))
            .thenComparing(provider -> BlobStoreDescriptor.normalize(provider.type())))
        .toList();
  }

  public List<BlobStoreProvider> all() {
    return providers;
  }

  public Optional<BlobStoreProvider> byType(String type) {
    return Optional.ofNullable(byType.get(BlobStoreDescriptor.normalize(type)));
  }

  /** First provider claiming the row, otherwise the fallback provider. */
  public Optional<BlobStoreProvider> find(BlobStoreDescriptor descriptor) {
    if (descriptor == null) {
      return Optional.empty();
    }
    for (BlobStoreProvider provider : providers) {
      if (provider.supports(descriptor)) {
        return Optional.of(provider);
      }
    }
    return Optional.ofNullable(byType.get(fallbackType));
  }

  public BlobStoreProvider require(BlobStoreDescriptor descriptor) {
    return find(descriptor).orElseThrow(() -> new IllegalStateException(
        "No blob store provider is registered for type '" + (descriptor == null ? "" : descriptor.type())
            + "' (blob store '" + (descriptor == null ? "" : descriptor.name()) + "')"));
  }
}
