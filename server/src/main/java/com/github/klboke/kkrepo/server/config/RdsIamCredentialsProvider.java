package com.github.klboke.kkrepo.server.config;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.util.Credentials;
import software.amazon.awssdk.services.rds.RdsUtilities;

/** Each replica signs independently; no token cache, scheduler or shared mutable password is needed. */
final class RdsIamCredentialsProvider implements HikariCredentialsProvider {
  private final RdsUtilities utilities;
  private final RdsIamConnectionSettings settings;

  RdsIamCredentialsProvider(RdsUtilities utilities, RdsIamConnectionSettings settings) {
    this.utilities = utilities;
    this.settings = settings;
  }

  @Override
  public Credentials getCredentials() {
    // Hikari calls this for each new physical connection, not for a checkout of an existing one.
    // RdsUtilities resolves the current AWS credentials on every signing operation.
    String token = utilities.generateAuthenticationToken(request -> request
        .hostname(settings.hostname()).port(settings.port()).username(settings.username()));
    return Credentials.of(settings.username(), token);
  }
}
