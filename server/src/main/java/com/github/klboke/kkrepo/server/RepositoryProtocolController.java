package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * HTTP entry point for Nexus-compatible repository URLs.
 *
 * <p>Authentication and path normalization are applied by the repository security filter. This
 * controller deliberately owns no protocol service and delegates every request through the route
 * SPI so format runtimes can evolve independently of the web entry point.
 */
@RestController
@RequestMapping("/repository/{name}")
public class RepositoryProtocolController {
  private final RepositoryProtocolDispatcher protocols;

  public RepositoryProtocolController(RepositoryProtocolDispatcher protocols) {
    this.protocols = protocols;
  }

  @GetMapping("/**")
  public ResponseEntity<StreamingResponseBody> get(
      @PathVariable("name") String name, HttpServletRequest request) {
    return cast(protocols.dispatchRead(name, request, HttpMethod.GET));
  }

  @RequestMapping(value = "/**", method = RequestMethod.HEAD)
  public ResponseEntity<Void> head(
      @PathVariable("name") String name, HttpServletRequest request) {
    return cast(protocols.dispatchRead(name, request, HttpMethod.HEAD));
  }

  @PutMapping("/**")
  public ResponseEntity<?> put(
      @PathVariable("name") String name,
      HttpServletRequest request) throws IOException, ServletException {
    return protocols.dispatch(name, request, HttpMethod.PUT, null);
  }

  ResponseEntity<?> put(
      String name, HttpServletRequest request, String contentType)
      throws IOException, ServletException {
    return protocols.dispatch(name, request, HttpMethod.PUT, contentType, null);
  }

  @DeleteMapping("/**")
  public ResponseEntity<?> delete(
      @PathVariable("name") String name,
      HttpServletRequest request) throws IOException, ServletException {
    return protocols.dispatch(name, request, HttpMethod.DELETE, null);
  }

  @PostMapping("/**")
  public ResponseEntity<?> post(
      @PathVariable("name") String name, HttpServletRequest request) {
    try {
      return protocols.dispatch(name, request, HttpMethod.POST, null);
    } catch (IOException | ServletException error) {
      throw new IllegalStateException("Repository POST handler failed", error);
    }
  }

  @PostMapping(value = "/api/charts", consumes = "multipart/form-data")
  public ResponseEntity<?> pushHelmChart(
      @PathVariable("name") String name,
      @RequestPart("chart") MultipartFile chart,
      HttpServletRequest request) throws IOException, ServletException {
    return protocols.dispatch(name, request, HttpMethod.POST, chart);
  }

  @SuppressWarnings("unchecked")
  private static <T> ResponseEntity<T> cast(ResponseEntity<?> response) {
    return (ResponseEntity<T>) response;
  }
}
