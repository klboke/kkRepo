package com.github.klboke.kkrepo.server.winget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetVersionSummary;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class WingetProtocolHandlerTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WingetService service = mock(WingetService.class);
  private final WingetProtocolHandler handler = new WingetProtocolHandler(service, mapper);

  @Test
  void servesInformationWithSupportedVersions() throws Exception {
    var response = handler.handle(request("api/information", HttpMethod.GET, null, null));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    JsonNode body = mapper.valueToTree(response.getBody());
    assertEquals("kkrepo-winget", body.path("Data").path("SourceIdentifier").asText());
    assertTrue(body.path("Data").path("ServerSupportedVersions").isArray());
    assertTrue(body.path("Data").path("ServerSupportedVersions").toString().contains("1.10.0"));
  }

  @Test
  void servesManifestSearchAndContinuationToken() throws Exception {
    WingetVersionSummary summary = new WingetVersionSummary(
        "1.2.3", "", List.of("Contoso.Tool_8wekyb3d8bbwe"),
        List.of("{PRODUCT-CODE}"), List.of(), List.of());
    com.github.klboke.kkrepo.protocol.winget.WingetPackage pkg =
        new com.github.klboke.kkrepo.protocol.winget.WingetPackage(
            "Contoso.Tool", "Contoso Tool", "Contoso", List.of(summary));
    when(service.search(any(), any(), any(), isNull(), eq(0), eq(20)))
        .thenReturn(new WingetSearchPage(List.of(pkg), 40, null));

    var response = handler.handle(request("api/manifestSearch", HttpMethod.POST,
        "{\"Query\":{\"KeyWord\":\"contoso\",\"MatchType\":\"SubstringIgnoreCase\"}}",
        "application/json"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    JsonNode body = mapper.valueToTree(response.getBody());
    assertEquals("Contoso.Tool", body.path("Data").get(0).path("PackageIdentifier").asText());
    assertNotNull(response.getHeaders().getFirst("ContinuationToken"));
    assertEquals(response.getHeaders().getFirst("ContinuationToken"),
        body.path("ContinuationToken").asText());
  }

  @Test
  void publishesManifestWithCreatedAndLocation() throws Exception {
    WingetManifest manifest = manifest();
    when(service.publish(any(), any())).thenReturn(manifest);

    var response = handler.handle(request("api/packageManifests", HttpMethod.POST,
        manifestBody(), "application/json"));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertTrue(response.getHeaders().getFirst("Location").endsWith(
        "/repository/winget/api/packageManifests/Contoso.Tool"));
  }

  @Test
  void servesAndFiltersManifest() throws Exception {
    when(service.manifest(any(), eq("Contoso.Tool"), eq("1.2.3"), isNull()))
        .thenReturn(manifest());
    MockHttpServletRequest servlet = new MockHttpServletRequest(HttpMethod.GET.name(),
        "/api/packageManifests/Contoso.Tool");
    servlet.addParameter("PackageVersion", "1.2.3");

    var response = handler.handle(new RepositoryProtocolRequest(
        runtime(), "winget", "api/packageManifests/Contoso.Tool", HttpMethod.GET, servlet, null, null));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    JsonNode body = mapper.valueToTree(response.getBody());
    assertEquals("Contoso.Tool", body.path("Data").path("PackageIdentifier").asText());
    assertEquals(1, body.path("Data").path("Versions").size());
  }

  @Test
  void rejectsNonJsonPublish() {
    var response = handler.handle(request("api/packageManifests", HttpMethod.POST, "{}",
        "text/plain"));
    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
  }

  @Test
  void rejectsWrongMethodWithAllowHeader() {
    var response = handler.handle(request("api/manifestSearch", HttpMethod.GET, null, null));
    assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    assertFalse(response.getHeaders().getAllow().isEmpty());
  }

  @Test
  void unknownRouteIsNotFound() {
    var response = handler.handle(request("api/nope", HttpMethod.GET, null, null));
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  private RepositoryProtocolRequest request(
      String path, HttpMethod method, String body, String contentType) {
    MockHttpServletRequest servlet = new MockHttpServletRequest(method.name(), "/" + path);
    if (body != null) {
      servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
    }
    if (contentType != null) {
      servlet.setContentType(contentType);
    }
    return new RepositoryProtocolRequest(
        runtime(), "winget", path, method, servlet, contentType, null);
  }

  private WingetManifest manifest() {
    try {
      return new WingetManifestParser().parse(mapper.readTree(manifestBody()));
    } catch (Exception invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private String manifestBody() {
    return """
        {"PackageIdentifier":"Contoso.Tool","Versions":[
          {"PackageIdentifier":"Contoso.Tool","PackageVersion":"1.2.3",
           "DefaultLocale":{"PackageLocale":"en-US","Publisher":"Contoso","PackageName":"Contoso Tool",
             "Moniker":"contoso"},
           "Installers":[{"InstallerType":"msi","PackageFamilyName":"Contoso.Tool_8wekyb3d8bbwe"}]}
        ]}
        """;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "winget", RepositoryFormat.WINGET, RepositoryType.HOSTED, "winget-hosted",
        true, 1L, "ALLOW", null, null, true, null, 60, 30, true, null, List.of());
  }
}
