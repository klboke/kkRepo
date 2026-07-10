package com.github.klboke.kkrepo.server.security;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class SecurityPayloads {
  private SecurityPayloads() {
  }

  public record AdminBootstrapCommand(
      String password,
      String passwordConfirm,
      Boolean anonymousAccessEnabled) {
  }

  public record AdminBootstrapStatus(
      boolean required,
      String source,
      String userId,
      String roleId,
      int minPasswordLength,
      boolean anonymousAccessEnabled) {
  }

  public record UserCommand(
      String source,
      String userId,
      String firstName,
      String lastName,
      String email,
      String password,
      String passwordHash,
      String status,
      String externalId,
      List<String> roles,
      Map<String, Object> attributes) {
  }

  public record UserView(
      Long id,
      String source,
      String userId,
      String firstName,
      String lastName,
      String email,
      String status,
      String externalId,
      boolean external,
      List<String> roles,
      Map<String, Object> attributes) {
  }

  public record RoleCommand(
      String roleId,
      String source,
      String name,
      String description,
      Boolean readOnly,
      List<String> privileges,
      List<String> roles,
      Map<String, Object> attributes) {
  }

  public record RoleView(
      String roleId,
      String source,
      String name,
      String description,
      boolean readOnly,
      List<String> privileges,
      List<String> roles,
      Map<String, Object> attributes) {
  }

  public record PrivilegeCommand(
      String privilegeId,
      String name,
      String description,
      String type,
      Boolean readOnly,
      Map<String, Object> properties) {
  }

  public record PrivilegeView(
      String privilegeId,
      String name,
      String description,
      String type,
      boolean readOnly,
      Map<String, Object> properties,
      String permission) {
  }

  public record RealmCommand(
      String realmId,
      String type,
      String name,
      Boolean enabled,
      Integer priority,
      Map<String, Object> attributes) {
  }

  public record RealmView(
      Long id,
      String realmId,
      String type,
      String name,
      boolean enabled,
      int priority,
      Map<String, Object> attributes) {
  }

  public record RealmReference(
      String id,
      String name) {
  }

  public record OidcSettingsCommand(
      Boolean enabled,
      Integer priority,
      String source,
      String issuer,
      String issuerUri,
      String jwksUri,
      String audience,
      String clientId,
      String clientSecret,
      String authorizationEndpoint,
      String tokenEndpoint,
      String redirectUri,
      String scopes,
      String userIdClaim,
      String firstNameClaim,
      String lastNameClaim,
      String emailClaim,
      String groupsClaim,
      String rolesClaim,
      Integer clockSkewSeconds,
      Integer jwksCacheSeconds,
      Map<String, Object> attributes) {
  }

  public record OidcSettingsView(
      String realmId,
      String type,
      String name,
      boolean enabled,
      int priority,
      String source,
      String issuer,
      String issuerUri,
      String jwksUri,
      String audience,
      String clientId,
      String clientSecret,
      String authorizationEndpoint,
      String tokenEndpoint,
      String redirectUri,
      String scopes,
      String userIdClaim,
      String firstNameClaim,
      String lastNameClaim,
      String emailClaim,
      String groupsClaim,
      String rolesClaim,
      Integer clockSkewSeconds,
      Integer jwksCacheSeconds,
      Map<String, Object> attributes) {
  }

  public record LdapSettingsCommand(
      Boolean enabled,
      Integer priority,
      String source,
      String name,
      String url,
      String protocol,
      String host,
      Integer port,
      Boolean useTrustStore,
      String searchBase,
      String authScheme,
      String authRealm,
      String authUsername,
      String authPassword,
      Integer connectionTimeout,
      Integer connectionRetryDelay,
      Integer maxIncidentsCount,
      String userBaseDn,
      Boolean userSubtree,
      String userObjectClass,
      String userLdapFilter,
      String userIdAttribute,
      String userRealNameAttribute,
      String userMemberOfAttribute,
      String userEmailAddressAttribute,
      String userPasswordAttribute,
      Boolean ldapGroupsAsRoles,
      String groupType,
      String groupBaseDn,
      Boolean groupSubtree,
      String groupIdAttribute,
      String groupMemberAttribute,
      String groupMemberFormat,
      String groupObjectClass,
      Map<String, Object> attributes) {
  }

  public record LdapSettingsView(
      String realmId,
      String type,
      String name,
      boolean enabled,
      int priority,
      String source,
      String url,
      String protocol,
      String host,
      Integer port,
      Boolean useTrustStore,
      String searchBase,
      String authScheme,
      String authRealm,
      String authUsername,
      String authPassword,
      Integer connectionTimeout,
      Integer connectionRetryDelay,
      Integer maxIncidentsCount,
      String userBaseDn,
      Boolean userSubtree,
      String userObjectClass,
      String userLdapFilter,
      String userIdAttribute,
      String userRealNameAttribute,
      String userMemberOfAttribute,
      String userEmailAddressAttribute,
      String userPasswordAttribute,
      Boolean ldapGroupsAsRoles,
      String groupType,
      String groupBaseDn,
      Boolean groupSubtree,
      String groupIdAttribute,
      String groupMemberAttribute,
      String groupMemberFormat,
      String groupObjectClass,
      Map<String, Object> attributes) {
  }

  public record RepositoryTargetCommand(
      String targetId,
      String name,
      String format,
      String contentExpression,
      List<String> pathPatterns,
      Map<String, Object> attributes) {
  }

  public record RepositoryTargetView(
      Long id,
      String targetId,
      String name,
      String format,
      String contentExpression,
      List<String> pathPatterns,
      Map<String, Object> attributes) {
  }

  public record AnonymousSettingsCommand(
      Boolean enabled,
      String userSource,
      String userId,
      String realmName) {
  }

  public record AnonymousSettingsView(
      boolean enabled,
      String userSource,
      String userId,
      String realmName) {
  }

  public record ApiKeyCommand(
      String domain,
      String ownerSource,
      String ownerUserId,
      String displayName,
      String status,
      List<String> scopes,
      LocalDateTime expiresAt,
      String encryptedPayload,
      String apiKeyHash) {
  }

  public record ApiKeyView(
      Long id,
      String domain,
      String ownerSource,
      String ownerUserId,
      String displayName,
      String status,
      String tokenPrefix,
      List<String> scopes,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime expiresAt,
      LocalDateTime lastUsedAt) {
  }

  public record CreatedApiKeyView(
      ApiKeyView apiKey,
      String token) {
  }

  public record SessionView(
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      List<String> roles) {
  }

  public record SecuritySummary(
      int users,
      int roles,
      int privileges,
      int activeRealms,
      int apiKeys) {
  }
}
