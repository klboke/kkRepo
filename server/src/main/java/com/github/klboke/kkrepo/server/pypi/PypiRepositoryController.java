package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenHtmlListingService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/repository/{name}")
public class PypiRepositoryController {
  private final RepositoryRuntimeRegistry registry;
  private final PypiHostedService hosted;
  private final PypiProxyService proxy;
  private final PypiGroupService group;
  private final MavenHtmlListingService htmlListing;
  private final PypiPartialFetchSupport partialFetch = new PypiPartialFetchSupport();

  public PypiRepositoryController(
      RepositoryRuntimeRegistry registry,
      PypiHostedService hosted,
      PypiProxyService proxy,
      PypiGroupService group,
      MavenHtmlListingService htmlListing) {
    this.registry = registry;
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.htmlListing = htmlListing;
  }

  @GetMapping({"/simple", "/simple/"})
  public ResponseEntity<StreamingResponseBody> getRootIndex(
      @PathVariable("name") String name,
      HttpServletRequest request) {
    return toBodyResponse(dispatchRoot(resolve(name), false), request);
  }

  @RequestMapping(value = {"/simple", "/simple/"}, method = RequestMethod.HEAD)
  public ResponseEntity<Void> headRootIndex(
      @PathVariable("name") String name,
      HttpServletRequest request) {
    return toHeadResponse(dispatchRoot(resolve(name), true), request);
  }

  @GetMapping({"/simple/{project}", "/simple/{project}/"})
  public ResponseEntity<StreamingResponseBody> getIndex(
      @PathVariable("name") String name,
      @PathVariable("project") String project,
      HttpServletRequest request) {
    return toBodyResponse(dispatchIndex(resolve(name), project, false), request);
  }

  @RequestMapping(value = {"/simple/{project}", "/simple/{project}/"}, method = RequestMethod.HEAD)
  public ResponseEntity<Void> headIndex(
      @PathVariable("name") String name,
      @PathVariable("project") String project,
      HttpServletRequest request) {
    return toHeadResponse(dispatchIndex(resolve(name), project, true), request);
  }

  private PypiResponse dispatchRoot(RepositoryRuntime runtime, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.getRootIndex(runtime, headOnly);
      case PROXY -> proxy.getRootIndex(runtime, headOnly);
      case GROUP -> group.getRootIndex(runtime, headOnly);
    };
  }

  private PypiResponse dispatchIndex(RepositoryRuntime runtime, String project, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.getIndex(runtime, project, headOnly);
      case PROXY -> proxy.getIndex(runtime, project, headOnly);
      case GROUP -> group.getIndex(runtime, project, headOnly);
    };
  }

  private RepositoryRuntime resolveHosted(String name) {
    RepositoryRuntime runtime = resolve(name);
    if (!runtime.isHosted()) {
      throw new PypiExceptions.MethodNotAllowed("PyPI uploads are only valid on hosted repositories");
    }
    return runtime;
  }

  private RepositoryRuntime resolve(String name) {
    RepositoryRuntime runtime = registry.resolve(name)
        .orElseThrow(() -> new PypiExceptions.PypiNotFoundException("Repository not found: " + name));
    if (runtime.format() != RepositoryFormat.PYPI) {
      throw new PypiExceptions.PypiNotFoundException("Repository is not PyPI format: " + name);
    }
    return runtime;
  }

  private ResponseEntity<StreamingResponseBody> toBodyResponse(PypiResponse resp, HttpServletRequest request) {
    return toBodyResponse(resp, request, false);
  }

  private ResponseEntity<StreamingResponseBody> toBodyResponse(
      PypiResponse resp, HttpServletRequest request, boolean partialFetchAllowed) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, resp.status(), resp.etag(), resp.lastModified())) {
      resp.closeBodyIfOpen();
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .headers(notModifiedHeaders(resp))
          .body(null);
    }
    if (partialFetchAllowed) {
      resp = partialFetch.apply(request, resp);
    }
    HttpHeaders headers = headers(resp, true);
    InputStream responseBody = resp.body();
    if (TempBlobFiles.tryUseTomcatSendfile(request, responseBody)) {
      return ResponseEntity.status(resp.status()).headers(headers).body(null);
    }
    long contentLength = resp.contentLength();
    StreamingResponseBody body = output ->
        TempBlobFiles.copyResponse(responseBody, output, request, contentLength);
    return ResponseEntity.status(resp.status()).headers(headers).body(body);
  }

  private ResponseEntity<Void> toHeadResponse(PypiResponse resp, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, resp.status(), resp.etag(), resp.lastModified())) {
      resp.closeBodyIfOpen();
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(notModifiedHeaders(resp)).build();
    }
    return ResponseEntity.status(resp.status()).headers(headers(resp, true)).build();
  }

  private HttpHeaders headers(PypiResponse resp, boolean includeEntityHeaders) {
    HttpHeaders headers = new HttpHeaders();
    if (includeEntityHeaders && resp.contentType() != null) {
      try {
        headers.setContentType(MediaType.parseMediaType(resp.contentType()));
      } catch (RuntimeException ignored) {
        headers.add(HttpHeaders.CONTENT_TYPE, resp.contentType());
      }
    }
    if (includeEntityHeaders && resp.contentLength() > 0) {
      headers.setContentLength(resp.contentLength());
    }
    ConditionalResponses.addValidators(headers, resp.etag(), resp.lastModified());
    resp.headers().forEach(headers::add);
    return headers;
  }

  private HttpHeaders notModifiedHeaders(PypiResponse resp) {
    return headers(resp, false);
  }

}
