package com.github.klboke.kkrepo.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ScannerAdapterApplication {
  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        SpringApplication.run(ScannerAdapterApplication.class, args);
    if (context.getEnvironment().getProperty(
        "kkrepo.scanner.database-update-only", Boolean.class, false)) {
      context.close();
    }
  }
}
