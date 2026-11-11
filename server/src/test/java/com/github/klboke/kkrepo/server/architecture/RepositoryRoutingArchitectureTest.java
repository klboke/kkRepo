package com.github.klboke.kkrepo.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.routing.LegacyRepositoryProtocolHandler;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class RepositoryRoutingArchitectureTest {
  private static final String ROOT = "com.github.klboke.kkrepo";
  private static final JavaClasses ALL_PROJECT_CLASSES = new ClassFileImporter()
      .withImportOption(new ImportOption.DoNotIncludeTests())
      .importPackages(ROOT);

  @Test
  void repositoryProtocolControllerDependsOnlyOnTheRoutingBoundary() {
    ArchRule rule = classes()
        .that().haveFullyQualifiedName(RepositoryProtocolController.class.getName())
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "jakarta.servlet..",
            "org.springframework..",
            "com.github.klboke.kkrepo.server",
            "com.github.klboke.kkrepo.server.routing");

    rule.check(ALL_PROJECT_CLASSES);
  }

  @Test
  void protocolRuntimeServicesDoNotReferenceTheRoutingLayer() {
    ArchRule rule = noClasses()
        .that().resideInAnyPackage(
            "..server.alpine..",
            "..server.ansible..",
            "..server.apt..",
            "..server.cargo..",
            "..server.composer..",
            "..server.conda..",
            "..server.conan..",
            "..server.docker..",
            "..server.gitlfs..",
            "..server.goartifact..",
            "..server.helm..",
            "..server.huggingface..",
            "..server.maven..",
            "..server.npm..",
            "..server.nuget..",
            "..server.pub..",
            "..server.pypi..",
            "..server.r..",
            "..server.raw..",
            "..server.rubygems..",
            "..server.swift..",
            "..server.terraform..",
            "..server.yum..")
        .should().dependOnClassesThat().resideInAnyPackage("..server.routing..");

    rule.check(ALL_PROJECT_CLASSES);
  }

  @Test
  void protocolModulesDoNotReferenceTheServerModule() {
    ArchRule rule = noClasses()
        .that().resideInAnyPackage("..protocol..")
        .should().dependOnClassesThat().resideInAnyPackage("..server..");

    rule.check(ALL_PROJECT_CLASSES);
  }

  @Test
  void routeHandlersStayInsideTheRoutingLayer() {
    ArchRule rule = classes()
        .that().implement(LegacyRepositoryProtocolHandler.class)
        .should().resideInAPackage("..server.routing..");

    rule.check(ALL_PROJECT_CLASSES);
  }

  @Test
  void pluginApiStaysFrameworkAgnostic() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("com.github.klboke.kkrepo.plugin.api..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "jakarta.servlet..",
            "javax.servlet..",
            "java.sql..",
            "jakarta.persistence..",
            "org.quartz..",
            "com.github.klboke.kkrepo.server..",
            "com.github.klboke.kkrepo.plugin.loader..");

    rule.check(ALL_PROJECT_CLASSES);
  }
}
