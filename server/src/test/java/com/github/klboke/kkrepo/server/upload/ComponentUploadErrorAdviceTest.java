package com.github.klboke.kkrepo.server.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.swift.SwiftExceptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ComponentUploadErrorAdviceTest {

  private final ComponentUploadErrorAdvice advice = new ComponentUploadErrorAdvice();

  @Test
  void cargoUploadBadRequestUsesUploadErrorBody() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoBadRequest(new CargoExceptions.BadRequestException("Invalid .crate upload"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Invalid .crate upload", response.getBody().get("error"));
  }

  @Test
  void cargoUploadWritePolicyDeniedUsesBadRequest() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoBadRequest(new CargoExceptions.WritePolicyDenied("Cargo asset already exists"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Cargo asset already exists", response.getBody().get("error"));
  }

  @Test
  void cargoUploadMethodNotAllowedUsesMethodNotAllowed() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoMethod(new CargoExceptions.MethodNotAllowed("Operation is only valid on hosted Cargo repositories"));

    assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    assertEquals("Operation is only valid on hosted Cargo repositories", response.getBody().get("error"));
  }

  @Test
  void terraformUploadBadRequestUsesUploadErrorBody() {
    ResponseEntity<Map<String, String>> response =
        advice.mavenBadRequest(new MavenExceptions.BadRequestException("Invalid Terraform archive"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Invalid Terraform archive", response.getBody().get("error"));
  }

  @Test
  void swiftComponentUploadPreservesProtocolFailureStatus() throws Exception {
    ComponentUploadService uploadService = mock(ComponentUploadService.class);
    doThrow(new SwiftExceptions.UnprocessableEntity("Invalid Swift source archive"))
        .when(uploadService)
        .upload(anyString(), anyMap(), any(), anyString(), anyString());
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ComponentUploadController(uploadService))
        .setControllerAdvice(advice)
        .build();

    String responseBody = mvc.perform(multipart("/service/rest/v1/components")
            .param("repository", "swift-hosted"))
        .andExpect(status().isUnprocessableEntity())
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertEquals("{\"error\":\"Invalid Swift source archive\"}", responseBody);
  }
}
