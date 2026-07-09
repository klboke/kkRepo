package com.github.klboke.kkrepo.server.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Legacy Nexus internal UI upload endpoint.
 *
 * @deprecated This endpoint is kept for Nexus iframe-upload compatibility only. New clients should
 *     use {@code POST /service/rest/v1/components}.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rest/internal/ui/upload")
public class ComponentLegacyUiUploadController {
  private final ComponentUploadService uploadService;
  private final ObjectMapper objectMapper;

  public ComponentLegacyUiUploadController(ComponentUploadService uploadService, ObjectMapper objectMapper) {
    this.uploadService = uploadService;
    this.objectMapper = objectMapper;
  }

  @PostMapping(value = "/{repository}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<String> uploadFromUi(
      @PathVariable("repository") String repository,
      MultipartHttpServletRequest multipartRequest,
      HttpServletRequest request) {
    try {
      ComponentUploadService.UploadResult result = uploadService.upload(
          repository,
          multipartRequest.getParameterMap(),
          multipartRequest.getMultiFileMap(),
          ComponentUploadController.createdBy(request),
          request.getRemoteAddr());
      return htmlTextarea(Map.of("success", true, "data", result.searchTerm()));
    } catch (Exception e) {
      return htmlTextarea(Map.of("success", false, "message", message(e)));
    }
  }

  private ResponseEntity<String> htmlTextarea(Map<String, ?> packet) {
    String json;
    try {
      json = objectMapper.writeValueAsString(packet);
    } catch (JsonProcessingException e) {
      json = "{\"success\":false,\"message\":\"Failed to serialize upload response\"}";
    }
    String body = "<html><body><textarea>" + html(json) + "</textarea></body></html>";
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(body);
  }

  private static String message(Exception e) {
    if (e.getMessage() != null && !e.getMessage().isBlank()) return e.getMessage();
    Throwable cause = e.getCause();
    if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
      return cause.getMessage();
    }
    return e.getClass().getSimpleName();
  }

  private static String html(String value) {
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }
}
