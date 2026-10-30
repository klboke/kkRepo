package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient;
import org.junit.jupiter.api.Test;

class KkGuardConfigurationTest {
  @Test
  void invalidDependencyTrackUrlDoesNotPreventStartup() {
    KkGuardProperties properties = new KkGuardProperties();
    properties.getDependencyTrack().setUrl("not a valid url");
    properties.getDependencyTrack().setApiKey("key");

    DependencyTrackClient client =
        new KkGuardConfiguration().dependencyTrackClient(properties, new ObjectMapper());

    // A typo in the URL must degrade kkGuard, never abort the whole Spring context.
    assertNotNull(client);
    assertFalse(client.isConfigured());
  }

  @Test
  void blankConfigurationYieldsAnUnconfiguredClient() {
    DependencyTrackClient client = new KkGuardConfiguration()
        .dependencyTrackClient(new KkGuardProperties(), new ObjectMapper());

    assertFalse(client.isConfigured());
  }
}
