package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcBlobStoreDaoSensitiveAttributesTest {

  @Test
  void everyBlobStoreCredentialAttributeIsEncryptedAtRest() {
    // S3/OSS key pair, plus the Azure shared key and SAS token written by the blob-store API.
    for (String attribute : new String[] {"accessKey", "secretKey", "accountKey", "sasToken"}) {
      assertTrue(JdbcBlobStoreDao.SENSITIVE_ATTRIBUTES.contains(attribute), attribute);
    }
  }
}
