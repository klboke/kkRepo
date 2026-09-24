package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

class RdsIamCredentialsProviderTest {
  @Test
  void signsForTheExactEndpointAndResolvesRotatedWorkloadCredentials() {
    var current = new AtomicReference<AwsCredentials>(credentials("first"));
    var provider = provider(current, 5432);
    var first = provider.getCredentials();
    assertEquals("db_user", first.getUsername());
    assertToken(first.getPassword(), "first", 5432);

    current.set(credentials("second"));
    assertToken(provider.getCredentials().getPassword(), "second", 5432);
  }

  @Test
  void hikariReusesConnectionsAndSignsAgainForReplacementConnections() throws Exception {
    var current = new AtomicReference<AwsCredentials>(credentials("first"));
    List<String> passwords = new CopyOnWriteArrayList<>();
    DataSource driver = mock(DataSource.class);
    when(driver.getConnection(anyString(), anyString())).thenAnswer(invocation -> {
      assertEquals("db_user", invocation.getArgument(0));
      passwords.add(invocation.getArgument(1));
      Connection connection = mock(Connection.class);
      when(connection.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      return connection;
    });
    try (HikariDataSource pool = new HikariDataSource()) {
      pool.setDataSource(driver);
      pool.setUsername("db_user");
      pool.setPassword("must-not-be-used");
      pool.setCredentialsProvider(provider(current, 3306));
      pool.setMaximumPoolSize(1);
      pool.setMinimumIdle(1);
      pool.setConnectionTimeout(2000);
      try (Connection ignored = pool.getConnection()) {
        assertEquals(1, passwords.size());
      }
      current.set(credentials("rotated"));
      try (Connection ignored = pool.getConnection()) {
        assertEquals(1, passwords.size());
      }
      pool.getHikariPoolMXBean().softEvictConnections();
      try (Connection ignored = pool.getConnection()) {
        assertEquals(2, passwords.size());
      }
      assertToken(passwords.getFirst(), "first", 3306);
      assertToken(passwords.getLast(), "rotated", 3306);
    }
  }

  @Test
  void credentialFailureDoesNotFallBackToAStaticPasswordOrPreviousToken() {
    var utilities = RdsUtilities.builder().region(Region.US_EAST_1)
        .credentialsProvider(() -> { throw SdkClientException.create("credentials unavailable"); })
        .build();
    var provider = new RdsIamCredentialsProvider(utilities,
        new RdsIamConnectionSettings("db.example", 5432, "db_user"));
    assertThrows(SdkClientException.class, provider::getCredentials);
  }

  private static RdsIamCredentialsProvider provider(AtomicReference<AwsCredentials> current, int port) {
    return new RdsIamCredentialsProvider(
        RdsUtilities.builder().region(Region.US_EAST_1).credentialsProvider(current::get).build(),
        new RdsIamConnectionSettings("db.example", port, "db_user"));
  }

  private static AwsSessionCredentials credentials(String name) {
    return AwsSessionCredentials.create(name + "-access", name + "-secret", name + "-session");
  }

  private static void assertToken(String token, String name, int port) {
    String decoded = URLDecoder.decode(token, StandardCharsets.UTF_8);
    assertTrue(decoded.startsWith("db.example:" + port + "/?"));
    assertTrue(decoded.contains("Action=connect"));
    assertTrue(decoded.contains("DBUser=db_user"));
    assertTrue(decoded.contains("X-Amz-Expires=900"));
    assertTrue(decoded.contains("X-Amz-Credential=" + name + "-access/"));
    assertTrue(decoded.contains("/us-east-1/rds-db/aws4_request"));
    assertTrue(decoded.contains("X-Amz-Security-Token=" + name + "-session"));
    assertTrue(decoded.contains("X-Amz-Signature="));
    assertFalse(decoded.contains(name + "-secret"));
  }
}
