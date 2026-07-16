package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.junit.jupiter.api.Test;

class TerraformSigningServiceTest {
  @Test
  void generatedDetachedSignatureVerifiesWithPublishedPublicKey() throws Exception {
    TerraformRegistryDao dao = mock(TerraformRegistryDao.class);
    AtomicReference<TerraformRegistryDao.SigningKey> stored = new AtomicReference<>();
    when(dao.findActiveSigningKey(7L)).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(dao.tryAcquirePublishLease(anyString(), anyString(), any(Instant.class))).thenReturn(true);
    doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return null; })
        .when(dao).insertSigningKey(any());
    TerraformSigningService service = new TerraformSigningService(dao);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED,
        "terraform-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
        null, null, null, List.of());
    byte[] contents = "abcd  terraform-provider-demo_1.0.0_linux_amd64.zip\n"
        .getBytes(StandardCharsets.UTF_8);
    TerraformSigningService.SigningMaterial material = service.active(runtime);
    byte[] detached = service.sign(contents, material);

    new TerraformSignatureVerifier().verify(contents, detached, List.of(material.publicArmor()));

    PGPPublicKeyRingCollection publicKeys = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(material.publicArmor().getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(detached)), new JcaKeyFingerprintCalculator());
    var signature = ((PGPSignatureList) objects.nextObject()).get(0);
    assertEquals(material.keyId(), OpenPgpKeyIds.format(signature.getKeyID()));
    var primary = publicKeys.getPublicKey(signature.getKeyID());
    var identities = primary.getSignaturesForID(primary.getUserIDs().next());
    var certification = identities.next();
    assertTrue(certification.getHashedSubPackets().getKeyFlags() != 0);
    signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"),
        primary);
    signature.update(contents);
    assertTrue(signature.verify());

    TerraformSignatureVerifier verifier = new TerraformSignatureVerifier();
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify("tampered".getBytes(StandardCharsets.UTF_8), detached,
            List.of(material.publicArmor())));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify(contents, detached, List.of()));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify(contents, "not a signature".getBytes(StandardCharsets.UTF_8),
            List.of(material.publicArmor())));
  }
}
