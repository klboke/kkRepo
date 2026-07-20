package com.github.klboke.kkrepo.server.nativeimage;

import java.lang.reflect.Type;
import java.util.List;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.util.ClassUtils;

/** Jackson binding metadata for DTOs only reachable through dynamic JSON values. */
public final class JacksonRuntimeHints implements RuntimeHintsRegistrar {
  static final List<String> BINDING_TYPES =
      List.of(
          "com.github.klboke.kkrepo.server.security.AuthenticatedSubject",
          "com.github.klboke.kkrepo.auth.PermissionSubject",
          "com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache$AuthorizationSnapshot",
          "com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord",
          "com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord",
          "com.github.klboke.kkrepo.server.cache.CachedAssetMetadata",
          "com.github.klboke.kkrepo.server.cache.CachedAssetMetadata$CachedBlob",
          "com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache$Entry",
          "com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo",
          "com.github.klboke.kkrepo.server.swift.SwiftResponseCache$Snapshot",
          "com.github.klboke.kkrepo.server.terraform.TerraformMetadataCache$ProviderSnapshot",
          "com.github.klboke.kkrepo.server.terraform.TerraformMetadataCache$GroupSnapshot",
          "com.github.klboke.kkrepo.server.maven.RepositoryRuntime",
          "com.github.klboke.kkrepo.server.composer.ComposerProxyService$Route",
          "com.github.klboke.kkrepo.protocol.swift.SwiftPublishResponse",
          "com.github.klboke.kkrepo.protocol.swift.SwiftIdentifiers",
          "com.github.klboke.kkrepo.protocol.swift.SwiftReleaseList",
          "com.github.klboke.kkrepo.protocol.swift.SwiftReleaseMetadata",
          "com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao$ProxyTag",
          "com.github.klboke.kkrepo.server.docker.DockerConnectorManager$Snapshot",
          "com.github.klboke.kkrepo.server.docker.DockerConnectorManager$ConnectorStatus",
          "com.github.klboke.kkrepo.server.docker.DockerConnectorManager$ConnectorTuning",
          "com.github.klboke.kkrepo.server.docker.DockerTransferLimiter$Snapshot");

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    Type[] bindingTypes =
        BINDING_TYPES.stream()
            .map(type -> (Type) ClassUtils.resolveClassName(type, classLoader))
            .toArray(Type[]::new);
    new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), bindingTypes);
  }
}
