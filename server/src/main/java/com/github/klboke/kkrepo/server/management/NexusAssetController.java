package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidAssetIdException;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetDetailView;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetNotFoundException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetPage;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.UnsupportedAssetDeleteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/service/rest/v1")
public class NexusAssetController {
  private static final Set<String> SUPPORTED_SEARCH_PARAMETERS =
      Set.of("repository", "name", "continuationToken", "stagingSession");

  private final NexusAssetManagementService service;
  private final NexusStagingManagementService staging;

  public NexusAssetController(NexusAssetManagementService service) {
    this(service, null);
  }

  @Autowired
  public NexusAssetController(
      NexusAssetManagementService service, NexusStagingManagementService staging) {
    this.service = service;
    this.staging = staging;
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
    String stagingSession = parameters.get("stagingSession");
    if (stagingSession != null && !stagingSession.isBlank()) {
      return requireStaging().searchAssets(
          parameters.get("repository"), stagingSession,
          parameters.get("continuationToken"), request);
    }
    return service.search(
        parameters.get("repository"),
        parameters.get("name"),
        parameters.get("continuationToken"),
        request);
  }

  @GetMapping("/assets")
  public AssetPage assets(
      @RequestParam Map<String, String> parameters,
      HttpServletRequest request) {
    String stagingSession = parameters.get("stagingSession");
    if (stagingSession != null && !stagingSession.isBlank()) {
      return requireStaging().searchAssets(
          parameters.get("repository"), stagingSession,
          parameters.get("continuationToken"), request);
    }
    return service.search(
        parameters.get("repository"),
        parameters.get("name"),
        parameters.get("continuationToken"),
        request);
  }

  @GetMapping("/assets/{id}")
  public AssetDetailView get(@PathVariable("id") String id, HttpServletRequest request) {
    return staging != null && staging.isStagingId(id)
        ? staging.getAsset(id, request) : service.get(id, request);
  }

  @DeleteMapping("/assets/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable("id") String id, HttpServletRequest request) {
    if (staging != null && staging.isStagingId(id)) {
      return ResponseEntity.status(staging.deleteAsset(id, request) == 0 ? 404 : 204).build();
    }
    return ResponseEntity.status(service.delete(id, request)).build();
  }

  @GetMapping("/components")
  public Object components(
      @RequestParam Map<String, String> parameters,
      HttpServletRequest request) {
    String stagingSession = parameters.get("stagingSession");
    if (stagingSession != null && !stagingSession.isBlank()) {
      return requireStaging().components(parameters.get("repository"), stagingSession, request);
    }
    return service.components(
        parameters.get("repository"), parameters.get("continuationToken"), request);
  }

  @GetMapping("/components/{id}")
  public Object component(@PathVariable("id") String id, HttpServletRequest request) {
    return staging != null && staging.isStagingId(id)
        ? staging.getComponent(id, request) : service.getComponent(id, request);
  }

  private NexusStagingManagementService requireStaging() {
    if (staging == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Staging management is unavailable");
    }
    return staging;
  }

  @ExceptionHandler(InvalidAssetIdException.class)
  public ResponseEntity<Void> invalidAssetId(InvalidAssetIdException ignored) {
    return ResponseEntity.unprocessableEntity().build();
  }

  @ExceptionHandler({NexusStagingIdCodec.InvalidStagingIdException.class,
      NexusComponentIdCodec.InvalidComponentIdException.class})
  public ResponseEntity<Void> invalidStagingId(RuntimeException ignored) {
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
