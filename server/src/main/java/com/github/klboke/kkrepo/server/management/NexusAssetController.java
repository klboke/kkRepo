package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidAssetIdException;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetNotFoundException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetPage;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetView;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.UnsupportedAssetDeleteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/service/rest/v1")
public class NexusAssetController {
  private static final Set<String> SUPPORTED_SEARCH_PARAMETERS =
      Set.of("repository", "name", "continuationToken");

  private final NexusAssetManagementService service;

  public NexusAssetController(NexusAssetManagementService service) {
    this.service = service;
  }

  @GetMapping("/search/assets")
  public AssetPage search(
      @RequestParam Map<String, String> parameters,
      HttpServletRequest request) {
    String unsupported = parameters.keySet().stream()
        .filter(key -> !SUPPORTED_SEARCH_PARAMETERS.contains(key))
        .sorted()
        .findFirst()
        .orElse(null);
    if (unsupported != null) {
      throw new InvalidSearchRequestException(
          "Unsupported asset search parameter: " + unsupported);
    }
    return service.search(
        parameters.get("repository"),
        parameters.get("name"),
        parameters.get("continuationToken"),
        request);
  }

  @GetMapping("/assets/{id}")
  public AssetView get(@PathVariable("id") String id, HttpServletRequest request) {
    return service.get(id, request);
  }

  @DeleteMapping("/assets/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable("id") String id, HttpServletRequest request) {
    return ResponseEntity.status(service.delete(id, request)).build();
  }

  @ExceptionHandler(InvalidAssetIdException.class)
  public ResponseEntity<Void> invalidAssetId(InvalidAssetIdException ignored) {
    return ResponseEntity.unprocessableEntity().build();
  }

  @ExceptionHandler({InvalidContinuationTokenException.class, InvalidSearchRequestException.class})
  public ResponseEntity<Void> invalidSearch(RuntimeException ignored) {
    return ResponseEntity.badRequest().build();
  }

  @ExceptionHandler(AssetNotFoundException.class)
  public ResponseEntity<Void> notFound(AssetNotFoundException ignored) {
    return ResponseEntity.notFound().build();
  }

  @ExceptionHandler(UnsupportedAssetDeleteException.class)
  public ResponseEntity<Void> unsupportedDelete(UnsupportedAssetDeleteException ignored) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }
}
