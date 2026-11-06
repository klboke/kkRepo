package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Item;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Job;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Source;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ManagementAuditFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/ohpm/imports")
public class OhpmImportController {
  private final OhpmImportService imports;
  private final SecurityAuthenticationService authentication;
  private final SecurityManagementService security;

  public OhpmImportController(OhpmImportService imports, SecurityAuthenticationService authentication,
      SecurityManagementService security) {
    this.imports = imports;
    this.authentication = authentication;
    this.security = security;
  }

  @PostMapping
  public Job create(@RequestBody Create command, HttpServletRequest request) {
    var subject = requireAdmin(request);
    return imports.create(command.repository(), command.source(), command.sourceRepository(), subject.userId());
  }

  @GetMapping
  public List<Job> list(@RequestParam("repository") String repository, HttpServletRequest request) {
    requireAdmin(request);
    return imports.jobs(repository);
  }

  @GetMapping("/{id}")
  public Report report(@PathVariable("id") String id, HttpServletRequest request) {
    requireAdmin(request);
    return new Report(imports.job(id), imports.report(id), List.of(new ManualAction("MANUAL",
        "Source users, tokens, permissions and storage-plugin settings are not imported; configure target access separately")));
  }

  @PostMapping("/{id}/state")
  public Job transition(@PathVariable("id") String id, @RequestBody State command, HttpServletRequest request) {
    requireAdmin(request);
    return imports.transition(id, command.state(), () -> requireAdmin(request));
  }

  @GetMapping("/{id}/source-versions")
  public List<String> versions(@PathVariable("id") String id, @RequestParam("name") String name, HttpServletRequest request)
      throws IOException {
    requireAdmin(request);
    return imports.sourceVersions(id, name);
  }

  @PostMapping("/{id}/registry")
  public Item planRegistry(@PathVariable("id") String id, @RequestBody Coordinate coordinate, HttpServletRequest request)
      throws IOException {
    requireAdmin(request);
    return imports.planRegistry(id, coordinate.name(), coordinate.version(), () -> requireAdmin(request));
  }

  @PostMapping("/{id}/items/{key}/registry")
  public Item applyRegistry(@PathVariable("id") String id, @PathVariable("key") String key, HttpServletRequest request)
      throws IOException {
    var subject = requireAdmin(request);
    return imports.applyRegistry(id, key, subject.userId(), request.getRemoteAddr(), () -> requireAdmin(request));
  }

  @PostMapping(path = "/{id}/directory", consumes = "multipart/form-data")
  public Item planDirectory(@PathVariable("id") String id, @RequestParam("sourceKey") String sourceKey,
      @RequestParam("asset") MultipartFile asset, @RequestParam(value = "hsp", required = false) MultipartFile hsp,
      @RequestParam(value = "metadata", required = false) MultipartFile metadata,
      @RequestParam(value = "packageName", required = false) String packageName,
      HttpServletRequest request) throws IOException {
    requireAdmin(request);
    boolean bundle = bundle(asset, hsp);
    OhpmPackageMetadata source = metadata(metadata, packageName);
    try (InputStream harInput = asset.getInputStream();
         InputStream hspInput = hsp == null ? null : hsp.getInputStream()) {
      return imports.planDirectory(id, sourceKey, harInput, hspInput, bundle, source, () -> requireAdmin(request));
    }
  }

  @PostMapping(path = "/{id}/items/{key}/directory", consumes = "multipart/form-data")
  public Item applyDirectory(@PathVariable("id") String id, @PathVariable("key") String key,
      @RequestParam("asset") MultipartFile asset, @RequestParam(value = "hsp", required = false) MultipartFile hsp,
      @RequestParam(value = "metadata", required = false) MultipartFile metadata,
      @RequestParam(value = "packageName", required = false) String packageName,
      HttpServletRequest request) throws IOException {
    var subject = requireAdmin(request);
    boolean bundle = bundle(asset, hsp);
    OhpmPackageMetadata source = metadata(metadata, packageName);
    try (InputStream harInput = asset.getInputStream();
         InputStream hspInput = hsp == null ? null : hsp.getInputStream()) {
      return imports.applyDirectory(id, key, harInput, hspInput, bundle, source,
          subject.userId(), request.getRemoteAddr(), () -> requireAdmin(request));
    }
  }

  private static OhpmPackageMetadata metadata(MultipartFile metadata, String packageName) throws IOException {
    if (metadata == null) return null;
    try (var input = metadata.getInputStream()) {
      return OhpmPackageMetadata.read(OhpmPackageId.parse(packageName), input);
    }
  }

  private static boolean bundle(MultipartFile asset, MultipartFile hsp) {
    String filename = asset.getOriginalFilename();
    boolean bundle = filename != null && (filename.endsWith(".tgz") || filename.endsWith(".tar.gz"));
    if (bundle && hsp != null || !bundle && (filename == null || !filename.endsWith(".har"))
        || hsp != null && (hsp.getOriginalFilename() == null || !hsp.getOriginalFilename().endsWith(".hsp"))) {
      throw new OhpmException(400, "Provide a HAR with optional HSP, or a complete HSP tgz bundle");
    }
    return bundle;
  }

  private AuthenticatedSubject requireAdmin(HttpServletRequest request) {
    var subject = authentication.authenticateFresh(request)
        .orElseThrow(() -> new OhpmException(401, "Authentication required"));
    if (!security.decideFresh(subject.permissionSubject(), "nexus:*").allowed()) {
      throw new OhpmException(403, "Administrator permission required");
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    request.setAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE, Map.of("format", "ohpm", "operation", "import"));
    return subject;
  }

  @ExceptionHandler(OhpmException.class)
  ResponseEntity<Map<String, Object>> protocolError(OhpmException error) {
    return ResponseEntity.status(error.status()).body(error.response());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid OHPM import input"));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
    return ResponseEntity.status(409).body(Map.of("error", "Import state or source changed; refresh the report"));
  }

  public record Create(String repository, Source source, String sourceRepository) {}
  public record Coordinate(String name, String version) {}
  public record State(JobState state) {}
  public record ManualAction(String state, String detail) {}
  public record Report(Job job, List<Item> items, List<ManualAction> manualActions) {}
}
