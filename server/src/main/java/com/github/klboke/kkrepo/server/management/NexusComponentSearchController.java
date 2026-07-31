package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import com.github.klboke.kkrepo.server.management.NexusComponentSearchService.ComponentPage;
import com.github.klboke.kkrepo.server.management.NexusComponentSearchService.SearchRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/service/rest/v1")
public class NexusComponentSearchController {
  private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
      "q", "repository", "format", "group", "name", "version", "continuationToken");

  private final NexusComponentSearchService service;

  public NexusComponentSearchController(NexusComponentSearchService service) {
    this.service = service;
  }

  @GetMapping("/search")
  public ComponentPage search(
      @RequestParam Map<String, String> parameters,
      HttpServletRequest request) {
    String unsupported = parameters.keySet().stream()
        .filter(key -> !SUPPORTED_PARAMETERS.contains(key))
        .sorted()
        .findFirst()
        .orElse(null);
    if (unsupported != null) {
      throw new InvalidSearchRequestException(
          "Unsupported component search parameter: " + unsupported);
    }
    return service.search(new SearchRequest(
        parameters.get("q"),
        parameters.get("repository"),
        parameters.get("format"),
        parameters.get("group"),
        parameters.get("name"),
        parameters.get("version"),
        parameters.get("continuationToken")), request);
  }

  @ExceptionHandler({InvalidContinuationTokenException.class, InvalidSearchRequestException.class})
  public ResponseEntity<Void> invalidSearch(RuntimeException ignored) {
    return ResponseEntity.badRequest().build();
  }
}
