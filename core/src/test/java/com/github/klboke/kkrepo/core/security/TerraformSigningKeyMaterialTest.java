package com.github.klboke.kkrepo.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerraformSigningKeyMaterialTest {
  @Test
  void roundTripsArmoredKeyAndPassphrase() {
    var decoded = TerraformSigningKeyMaterial.decode(
        TerraformSigningKeyMaterial.encode("-----BEGIN PGP PRIVATE KEY BLOCK-----", "p@ssphrase"));

    assertEquals("-----BEGIN PGP PRIVATE KEY BLOCK-----", decoded.privateKeyArmor());
    assertEquals("p@ssphrase", decoded.passphrase());
  }

  @Test
  void decodesLegacyArmorWithEmptyPassphrase() {
    var decoded = TerraformSigningKeyMaterial.decode("legacy-private-armor");

    assertEquals("legacy-private-armor", decoded.privateKeyArmor());
    assertEquals("", decoded.passphrase());
  }
}
