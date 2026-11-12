package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.protocol.helm.HelmChartUploader;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Host implementation of the extracted Helm upload plugin. The plugin owns only routing and
 * multipart validation; this adapter resolves the live repository runtime and keeps chart parsing,
 * writes, index updates and audit inside the existing service.
 */
@Component
public class ServerHelmChartUploader implements HelmChartUploader {
  private final HelmHostedService helm;
  private final RepositoryRuntimeRegistry runtimes;

  public ServerHelmChartUploader(HelmHostedService helm, RepositoryRuntimeRegistry runtimes) {
    this.helm = helm;
    this.runtimes = runtimes;
  }

  @Override
  public int upload(
      RepositoryDescriptor repository,
      MultipartPart chart,
      String createdBy,
      String createdByIp) throws IOException {
    RepositoryRuntime runtime = runtimes.resolve(repository.name())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "Repository not found: " + repository.name()));
    MavenResponse response = helm.push(
        runtime, new PartMultipartFile(chart), createdBy, createdByIp);
    return response.status();
  }

  private record PartMultipartFile(MultipartPart part) implements MultipartFile {
    @Override
    public String getName() {
      return part.name() == null ? "" : part.name();
    }

    @Override
    public String getOriginalFilename() {
      return part.filename();
    }

    @Override
    public String getContentType() {
      return part.contentType();
    }

    @Override
    public boolean isEmpty() {
      return part.content().length == 0;
    }

    @Override
    public long getSize() {
      return part.content().length;
    }

    @Override
    public byte[] getBytes() {
      return part.content();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(part.content());
    }

    @Override
    public void transferTo(File destination) throws IOException {
      Files.write(destination.toPath(), part.content());
    }
  }
}
