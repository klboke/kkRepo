package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.server.nativeimage.ApolloRuntimeHints;
import com.github.klboke.kkrepo.server.nativeimage.CaffeineRuntimeHints;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = "com.github.klboke.kkrepo",
    excludeName = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
@EnableScheduling
@ImportRuntimeHints({ApolloRuntimeHints.class, CaffeineRuntimeHints.class})
public class KkRepoApplication {
  private static final String APOLLO_CONFIG_IMPORT = "optional:apollo://";

  public static void main(String[] args) {
    configureApolloSystemProperties(System.getenv());
    SpringApplication.run(KkRepoApplication.class, args);
  }

  static void configureApolloSystemProperties(Map<String, String> env) {
    String kkRepoApolloMeta = env.get("KKREPO_APOLLO_META");
    if (!hasText(System.getProperty("apollo.meta")) && hasText(kkRepoApolloMeta)) {
      System.setProperty("apollo.meta", kkRepoApolloMeta.trim());
    }

    if (apolloMetaConfigured(env) && !springConfigImportConfigured(env)) {
      System.setProperty("spring.config.import", APOLLO_CONFIG_IMPORT);
    }
  }

  private static boolean apolloMetaConfigured(Map<String, String> env) {
    return hasText(System.getProperty("apollo.meta"))
        || hasText(env.get("APOLLO_META"))
        || hasText(env.get("KKREPO_APOLLO_META"));
  }

  private static boolean springConfigImportConfigured(Map<String, String> env) {
    return hasText(System.getProperty("spring.config.import")) || hasText(env.get("SPRING_CONFIG_IMPORT"));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
