package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeyTokenCandidateTest {

  @Test
  void extractsNexusDomainPrefixAndRawToken() {
    List<ApiKeyTokenCandidate> candidates = ApiKeyTokenCandidate.fromPresentedToken("NpmToken.raw-value");

    assertEquals(2, candidates.size());
    assertEquals(new ApiKeyTokenCandidate("NpmToken", "raw-value"), candidates.get(0));
    assertEquals(new ApiKeyTokenCandidate(null, "NpmToken.raw-value"), candidates.get(1));
  }

  @Test
  void preservesExistingFullTokenHashFallback() {
    List<ApiKeyTokenCandidate> candidates = ApiKeyTokenCandidate.fromPresentedToken("kkrepo-generated-token");

    assertEquals(4, candidates.size());
    assertEquals(new ApiKeyTokenCandidate("NpmToken", "kkrepo-generated-token"), candidates.get(0));
    assertEquals(new ApiKeyTokenCandidate("CargoToken", "kkrepo-generated-token"), candidates.get(1));
    assertEquals(new ApiKeyTokenCandidate("NuGetApiKey", "kkrepo-generated-token"), candidates.get(2));
    assertEquals(new ApiKeyTokenCandidate(null, "kkrepo-generated-token"), candidates.get(3));
  }

  @Test
  void rubygemsBareTokensPreferRubygemsDomain() {
    List<ApiKeyTokenCandidate> candidates =
        ApiKeyTokenCandidate.fromPresentedRubygemsToken("kkrepo-generated-token");

    assertEquals(5, candidates.size());
    assertEquals(new ApiKeyTokenCandidate("RubyGemsApiKey", "kkrepo-generated-token"), candidates.get(0));
    assertEquals(new ApiKeyTokenCandidate("NpmToken", "kkrepo-generated-token"), candidates.get(1));
    assertEquals(new ApiKeyTokenCandidate("CargoToken", "kkrepo-generated-token"), candidates.get(2));
    assertEquals(new ApiKeyTokenCandidate("NuGetApiKey", "kkrepo-generated-token"), candidates.get(3));
    assertEquals(new ApiKeyTokenCandidate(null, "kkrepo-generated-token"), candidates.get(4));
  }

  @Test
  void cargoBareTokensPreferCargoDomainBeforeNpmDomain() {
    List<ApiKeyTokenCandidate> candidates = ApiKeyTokenCandidate.fromPresentedCargoToken("kkrepo-generated-token");

    assertEquals(4, candidates.size());
    assertEquals(new ApiKeyTokenCandidate("CargoToken", "kkrepo-generated-token"), candidates.get(0));
    assertEquals(new ApiKeyTokenCandidate("NpmToken", "kkrepo-generated-token"), candidates.get(1));
    assertEquals(new ApiKeyTokenCandidate("NuGetApiKey", "kkrepo-generated-token"), candidates.get(2));
    assertEquals(new ApiKeyTokenCandidate(null, "kkrepo-generated-token"), candidates.get(3));
  }

  @Test
  void extractsCargoDomainPrefixAndRawToken() {
    List<ApiKeyTokenCandidate> candidates = ApiKeyTokenCandidate.fromPresentedToken("CargoToken.raw-value");

    assertEquals(2, candidates.size());
    assertEquals(new ApiKeyTokenCandidate("CargoToken", "raw-value"), candidates.get(0));
    assertEquals(new ApiKeyTokenCandidate(null, "CargoToken.raw-value"), candidates.get(1));
  }

  @Test
  void ignoresBlankTokens() {
    assertEquals(List.of(), ApiKeyTokenCandidate.fromPresentedToken("  "));
  }
}
