package com.github.klboke.kkrepo.server.upload;

import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/service/rest")
public class ComponentUploadController {
  private final ComponentUploadService uploadService;

  public ComponentUploadController(ComponentUploadService uploadService) {
    this.uploadService = uploadService;
  }

  @GetMapping("/v1/formats/upload-specs")
  public List<UploadDefinition> uploadSpecs() {
    return uploadService.definitions();
  }

  @GetMapping("/v1/formats/upload-specs/{format}")
  public UploadDefinition uploadSpec(@PathVariable("format") String format) {
    return uploadService.definition(format);
  }

  @PostMapping(value = "/v1/components", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> uploadComponent(
      @RequestParam("repository") String repository,
      MultipartHttpServletRequest multipartRequest,
      HttpServletRequest request) throws IOException {
    uploadService.upload(repository, multipartRequest.getParameterMap(), multipartRequest.getMultiFileMap(),
        createdBy(request), request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  static String createdBy(HttpServletRequest request) {
    Object attribute = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (attribute instanceof AuthenticatedSubject subject) {
      return subject.userId();
    }
    return "anonymous";
  }
}
