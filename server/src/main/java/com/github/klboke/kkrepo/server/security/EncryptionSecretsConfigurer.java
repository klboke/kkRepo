package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges the encryption secrets declared in the application config file
 * ({@code kkrepo.security.encryption.*}) into the Spring-agnostic {@link EncryptionSecrets}
 * holder. The credential cipher is an explicit dependency of database initialization and the
 * repository DAO, so both use the bound secret even during the earliest startup reads.
 */
@Configuration
public class EncryptionSecretsConfigurer {
  public EncryptionSecretsConfigurer(
      @Value("${kkrepo.security.encryption.credential-secret:}") String credentialSecret,
      @Value("${kkrepo.security.encryption.api-key-payload-secret:}") String apiKeyPayloadSecret) {
    EncryptionSecrets.configure(credentialSecret, apiKeyPayloadSecret);
  }

  @Bean
  SecretCipher repositoryCredentialCipher(EncryptionSecretsVerifier verifier) {
    verifier.verify();
    return new SecretCipher(EncryptionSecrets.credentialSecret());
  }
}
