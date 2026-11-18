package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Drives the real progressive body so adapter tests can assert validation/publication ordering. */
public final class ProgressiveResponseFixture implements AutoCloseable {
  private final Path file;
  private final MavenResponse response;

  public ProgressiveResponseFixture(Path directory, byte[] content, Runnable persist) throws IOException {
    file = Files.createTempFile(directory, "progressive-adapter-", ".tmp");
    var hooks = new ProgressiveProxyService.CompletionHooks();
    var body = new ProgressiveProxyService.ProgressiveBody(new ByteArrayInputStream(content),
        content.length, file, null,
        staged -> { for (var validator : hooks.validators) validator.validate(staged); },
        () -> { persist.run(); hooks.afterCache.forEach(Runnable::run); }, () -> {});
    response = MavenResponse.ok(body, content.length, "application/octet-stream", null, null)
        .withInternalAttribute(ProgressiveProxyService.class.getName() + ".hooks", hooks);
  }

  public MavenResponse response() { return response; }
  public Path file() { return file; }

  @Override public void close() throws IOException {
    response.closeBodyIfOpen();
    Files.deleteIfExists(file);
  }
}
