package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcCondaRegistryDaoTransactionTest {
  @Test
  void leaseLifecycleKeepsVisibilityAndCallerTransactionFencing() throws Exception {
    Transactional acquire = JdbcCondaRegistryDao.class
        .getMethod("tryAcquireLease", String.class, String.class, Instant.class)
        .getAnnotation(Transactional.class);
    Transactional release = JdbcCondaRegistryDao.class
        .getMethod("releaseLease", String.class, String.class, long.class)
        .getAnnotation(Transactional.class);
    Transactional renew = JdbcCondaRegistryDao.class
        .getMethod("renewLease", String.class, String.class, long.class, Instant.class)
        .getAnnotation(Transactional.class);

    assertEquals(Propagation.REQUIRES_NEW, acquire.propagation());
    assertEquals(Propagation.REQUIRES_NEW, release.propagation());
    assertNull(renew, "renewal must join the caller transaction to fence its commit");
  }
}
