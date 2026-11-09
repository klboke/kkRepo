package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AzureCredentialTypeTest {

  @ParameterizedTest
  @ValueSource(strings = {"managed-identity", "managedidentity", "msi", "managed", "MANAGED-IDENTITY"})
  void resolvesManagedIdentityAliases(String value) {
    assertEquals(AzureCredentialType.MANAGED_IDENTITY, AzureCredentialType.fromValue(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"workload-identity", "workloadidentity", "workload", "federated"})
  void resolvesWorkloadIdentityAliases(String value) {
    assertEquals(AzureCredentialType.WORKLOAD_IDENTITY, AzureCredentialType.fromValue(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"account-key", "accountkey", "shared-key", "sharedkey", "key"})
  void resolvesAccountKeyAliases(String value) {
    assertEquals(AzureCredentialType.ACCOUNT_KEY, AzureCredentialType.fromValue(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sas", "sas-token", "sastoken"})
  void resolvesSasAliases(String value) {
    assertEquals(AzureCredentialType.SAS, AzureCredentialType.fromValue(value));
  }

  @Test
  void unknownAndBlankValuesFallBackToTheSecretlessChain() {
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.fromValue(null));
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.fromValue(""));
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.fromValue("   "));
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.fromValue("nonsense"));
  }

  @Test
  void onlyTokenBasedModesAvoidStoringASecret() {
    assertTrue(AzureCredentialType.DEFAULT.isTokenBased());
    assertTrue(AzureCredentialType.MANAGED_IDENTITY.isTokenBased());
    assertTrue(AzureCredentialType.WORKLOAD_IDENTITY.isTokenBased());
    assertFalse(AzureCredentialType.ACCOUNT_KEY.isTokenBased());
    assertFalse(AzureCredentialType.SAS.isTokenBased());
  }

  @Test
  void idsRoundTripThroughFromValue() {
    for (AzureCredentialType type : AzureCredentialType.values()) {
      assertEquals(type, AzureCredentialType.fromValue(type.id()));
      assertEquals(type, AzureCredentialType.parse(type.id()));
    }
  }

  @Test
  void strictParsingRejectsUnknownModesButTreatsBlankAsDefault() {
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.parse(null));
    assertEquals(AzureCredentialType.DEFAULT, AzureCredentialType.parse(" "));
    assertEquals(AzureCredentialType.ACCOUNT_KEY, AzureCredentialType.parse("Shared-Key"));
    assertThrows(IllegalArgumentException.class, () -> AzureCredentialType.parse("acount-key"));
  }

  @Test
  void onlyTheModesThatReadAnIdentitySettingKeepIt() {
    assertTrue(AzureCredentialType.DEFAULT.usesClientId());
    assertTrue(AzureCredentialType.DEFAULT.usesTenant());
    // DefaultAzureCredential reads the federated token file only from the environment.
    assertFalse(AzureCredentialType.DEFAULT.usesTokenFilePath());
    assertTrue(AzureCredentialType.WORKLOAD_IDENTITY.usesTokenFilePath());
    assertTrue(AzureCredentialType.MANAGED_IDENTITY.usesClientId());
    assertFalse(AzureCredentialType.MANAGED_IDENTITY.usesTenant());
    assertFalse(AzureCredentialType.ACCOUNT_KEY.usesClientId());
    assertFalse(AzureCredentialType.SAS.usesTenant());
  }
}
