package com.github.klboke.kkrepo.server.metrics;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import io.micrometer.core.instrument.Timer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class InstrumentedBlobStorage implements BlobStorage {
  private final BlobStorage delegate;
  private final KkRepoMetrics metrics;
  private final String store;
  private final String type;
  private final String engine;

  public InstrumentedBlobStorage(
      BlobStorage delegate,
      KkRepoMetrics metrics,
      String store,
      String type,
      String engine) {
    this.delegate = delegate;
    this.metrics = metrics;
    this.store = store;
    this.type = type;
    this.engine = engine;
  }

  @Override
  public BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
    return delegate.prepareVerifiedUpload(repository, sha256, size);
  }

  @Override
  public com.github.klboke.kkrepo.core.VerifiedBlobDigests uploadVerified(
      BlobReference target, InputStream content, java.util.function.Consumer<String> multipartStarted) {
    Timer.Sample sample = metrics.startTimer();
    try {
      var result = delegate.uploadVerified(target, content, multipartStarted);
      metrics.recordBlobOperation(store, type, engine, "put_verified", "success", sample);
      metrics.recordBlobBytes(store, type, engine, "put_verified", result.size());
      return result;
    } catch (RuntimeException error) {
      metrics.recordBlobOperation(store, type, engine, "put_verified", "error", sample);
      throw error;
    }
  }

  @Override
  public void discardVerifiedUpload(BlobReference target, String multipartId) {
    delegate.discardVerifiedUpload(target, multipartId);
  }

  @Override
  public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
    Timer.Sample sample = metrics.startTimer();
    try {
      BlobReference reference = delegate.put(repository, logicalPath, content, size, sha256);
      metrics.recordBlobOperation(store, type, engine, "put", "success", sample);
      metrics.recordBlobBytes(store, type, engine, "put", size);
      return reference;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "put", "error", sample);
      throw e;
    }
  }

  @Override
  public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    Timer.Sample sample = metrics.startTimer();
    long size = size(file);
    try {
      BlobReference reference = delegate.putFile(repository, logicalPath, file, sha256);
      metrics.recordBlobOperation(store, type, engine, "put_file", "success", sample);
      metrics.recordBlobBytes(store, type, engine, "put_file", size);
      return reference;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "put_file", "error", sample);
      throw e;
    }
  }

  @Override
  public Optional<InputStream> get(BlobReference reference) {
    Timer.Sample sample = metrics.startTimer();
    try {
      Optional<InputStream> result = delegate.get(reference);
      metrics.recordBlobOperation(store, type, engine, "get", result.isPresent() ? "success" : "miss", sample);
      if (result.isPresent() && reference != null) {
        metrics.recordBlobBytes(store, type, engine, "get", reference.size());
      }
      return result;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "get", "error", sample);
      throw e;
    }
  }

  @Override
  public Optional<InputStream> getRange(BlobReference reference, long start, long length) {
    Timer.Sample sample = metrics.startTimer();
    try {
      Optional<InputStream> result = delegate.getRange(reference, start, length);
      metrics.recordBlobOperation(store, type, engine, "get_range",
          result.isPresent() ? "success" : "miss", sample);
      if (result.isPresent()) {
        metrics.recordBlobBytes(store, type, engine, "get_range", length);
      }
      return result;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "get_range", "error", sample);
      throw e;
    }
  }

  @Override
  public boolean exists(BlobReference reference) {
    Timer.Sample sample = metrics.startTimer();
    try {
      boolean result = delegate.exists(reference);
      metrics.recordBlobOperation(store, type, engine, "exists", result ? "success" : "miss", sample);
      return result;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "exists", "error", sample);
      throw e;
    }
  }

  @Override
  public Optional<BlobObjectMetadata> stat(BlobReference reference) {
    Timer.Sample sample = metrics.startTimer();
    try {
      Optional<BlobObjectMetadata> result = delegate.stat(reference);
      metrics.recordBlobOperation(store, type, engine, "stat", result.isPresent() ? "success" : "miss", sample);
      return result;
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "stat", "error", sample);
      throw e;
    }
  }

  @Override
  public void delete(BlobReference reference) {
    Timer.Sample sample = metrics.startTimer();
    try {
      delegate.delete(reference);
      metrics.recordBlobOperation(store, type, engine, "delete", "success", sample);
      if (reference != null) {
        metrics.recordBlobBytes(store, type, engine, "delete", reference.size());
      }
    } catch (RuntimeException e) {
      metrics.recordBlobOperation(store, type, engine, "delete", "error", sample);
      throw e;
    }
  }

  @Override
  public Optional<Path> stagingDirectory() {
    return delegate.stagingDirectory();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private static long size(Path file) {
    if (file == null) return 0;
    try {
      return Files.size(file);
    } catch (Exception ignored) {
      return 0;
    }
  }
}
