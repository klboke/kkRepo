package com.github.klboke.kkrepo.storage.file;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobFileRegion;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Local-disk blob storage. Object keys are immutable content keys under the configured root.
 */
public final class FileBlobStorage implements BlobStorage {
  private static final int TRANSFER_FALLBACK_BUFFER_SIZE = 1024 * 1024;

  private final FileBlobStoreConfig config;
  private final Path root;
  private final BiFunction<String, String, String> objectKeyFactory;
  private final FileBlobDirectoryCache directoryCache;

  public FileBlobStorage(FileBlobStoreConfig config) {
    this(config, FileBlobObjectKeys::immutableObjectKey);
  }

  FileBlobStorage(FileBlobStoreConfig config, BiFunction<String, String, String> objectKeyFactory) {
    this(config, objectKeyFactory, new FileBlobDirectoryCache());
  }

  FileBlobStorage(
      FileBlobStoreConfig config,
      BiFunction<String, String, String> objectKeyFactory,
      FileBlobDirectoryCache directoryCache) {
    this.config = config;
    this.root = config.root();
    this.objectKeyFactory = objectKeyFactory;
    this.directoryCache = directoryCache;
  }

  public FileBlobStoreConfig config() {
    return config;
  }

  @Override
  public BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
    return new BlobReference(config.name(), FileBlobObjectKeys.immutableObjectKey(repository, sha256), sha256, size);
  }

  @Override
  public com.github.klboke.kkrepo.core.VerifiedBlobDigests uploadVerified(
      BlobReference target, InputStream content, java.util.function.Consumer<String> multipartStarted) {
    var reader = new com.github.klboke.kkrepo.core.VerifiedBlobReader(target, content);
    Path path = resolveObjectKey(target.objectKey());
    boolean created = false;
    try {
      directoryCache.ensureExists(path.getParent());
      try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
        created = true;
        byte[] part;
        while ((part = reader.nextPart()).length > 0) output.write(part);
        return reader.finish();
      }
    } catch (IOException error) {
      if (created) deleteQuietly(path);
      throw new UncheckedIOException(error);
    } catch (RuntimeException error) {
      if (created) deleteQuietly(path);
      throw error;
    }
  }

  @Override
  public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
    Path temp = null;
    try {
      temp = stagingDirectory().orElseThrow().resolve("blob-" + UUID.randomUUID() + ".tmp");
      long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
      BlobReference reference = installTempFile(repository, sha256, temp, size >= 0 ? size : written);
      temp = null;
      return reference;
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    try {
      long size = Files.size(file);
      return installSourceFile(repository, sha256, file, size);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store file blob: " + file, e);
    }
  }

  @Override
  public Optional<Path> stagingDirectory() {
    Path stagingDir = root.resolve(".tmp").normalize();
    try {
      directoryCache.ensureExists(stagingDir);
      return Optional.of(stagingDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create file blob staging directory: " + stagingDir, e);
    }
  }

  @Override
  public Optional<InputStream> get(BlobReference reference) {
    Path path = resolveObjectKey(reference.objectKey());
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(new FileRegionInputStream(path, 0, reference.size()));
  }

  @Override
  public Optional<InputStream> getRange(BlobReference reference, long start, long length) {
    Path path = resolveObjectKey(reference.objectKey());
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(new FileRegionInputStream(path, start, length));
  }

  @Override
  public boolean exists(BlobReference reference) {
    return Files.isRegularFile(resolveObjectKey(reference.objectKey()));
  }

  @Override
  public Optional<BlobObjectMetadata> stat(BlobReference reference) {
    Path path = resolveObjectKey(reference.objectKey());
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      long size = Files.size(path);
      Instant lastModified = Files.getLastModifiedTime(path).toInstant();
      BlobReference resolved = new BlobReference(
          config.name(),
          reference.objectKey(),
          reference.sha256(),
          size);
      return Optional.of(new BlobObjectMetadata(resolved, reference.sha256(), null, lastModified));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void delete(BlobReference reference) {
    try {
      Files.deleteIfExists(resolveObjectKey(reference.objectKey()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path resolveObjectKey(String objectKey) {
    if (objectKey == null || objectKey.isBlank() || objectKey.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("object key is required");
    }
    String normalizedKey = objectKey.replace('\\', '/');
    if (normalizedKey.startsWith("/")) {
      throw new IllegalArgumentException("object key must be relative");
    }
    Path resolved = root.resolve(normalizedKey).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("object key escapes blob store root");
    }
    return resolved;
  }

  private BlobReference installTempFile(String repository, String sha256, Path temp, long size) throws IOException {
    for (int attempt = 0; attempt <= FileBlobObjectKeys.MAX_COLLISION_RETRIES; attempt++) {
      String objectKey = objectKeyFactory.apply(repository, sha256);
      Path target = resolveObjectKey(objectKey);
      Path parent = ensureObjectParent(target);
      try {
        moveIntoPlace(temp, target, false);
        return new BlobReference(config.name(), objectKey, sha256, size);
      } catch (NoSuchFileException e) {
        forgetShardDirectory(parent);
        parent = ensureObjectParent(target);
        moveIntoPlace(temp, target, false);
        return new BlobReference(config.name(), objectKey, sha256, size);
      } catch (FileAlreadyExistsException e) {
        if (attempt == FileBlobObjectKeys.MAX_COLLISION_RETRIES) {
          throw e;
        }
      }
    }
    throw new FileAlreadyExistsException("Could not find free file blob object key");
  }

  private BlobReference installSourceFile(String repository, String sha256, Path source, long size) throws IOException {
    Path temp = null;
    try {
      for (int attempt = 0; attempt <= FileBlobObjectKeys.MAX_COLLISION_RETRIES; attempt++) {
        String objectKey = objectKeyFactory.apply(repository, sha256);
        Path target = resolveObjectKey(objectKey);
        Path parent = ensureObjectParent(target);
        try {
          try {
            Files.createLink(target, source);
          } catch (FileAlreadyExistsException collision) {
            throw collision;
          } catch (UnsupportedOperationException | IOException linkFailure) {
            if (linkFailure instanceof NoSuchFileException) {
              forgetShardDirectory(parent);
              parent = ensureObjectParent(target);
            }
            temp = target.getParent().resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.copy(source, temp);
            moveIntoPlace(temp, target, false);
            temp = null;
          }
          return new BlobReference(config.name(), objectKey, sha256, size);
        } catch (FileAlreadyExistsException e) {
          deleteQuietly(temp);
          temp = null;
          if (attempt == FileBlobObjectKeys.MAX_COLLISION_RETRIES) {
            throw e;
          }
        }
      }
      throw new FileAlreadyExistsException("Could not find free file blob object key");
    } finally {
      deleteQuietly(temp);
    }
  }

  private Path ensureObjectParent(Path target) throws IOException {
    Path digestDirectory = target.getParent();
    Path shardDirectory = digestDirectory == null ? null : digestDirectory.getParent();
    if (shardDirectory == null) {
      directoryCache.ensureExists(digestDirectory);
      return digestDirectory;
    }
    directoryCache.ensureExists(shardDirectory);
    createDirectoryIfMissing(digestDirectory);
    return digestDirectory;
  }

  private void forgetShardDirectory(Path digestDirectory) {
    Path shardDirectory = digestDirectory == null ? null : digestDirectory.getParent();
    directoryCache.forget(shardDirectory == null ? digestDirectory : shardDirectory);
  }

  private static void createDirectoryIfMissing(Path directory) throws IOException {
    if (directory == null) {
      return;
    }
    try {
      Files.createDirectories(directory);
    } catch (FileAlreadyExistsException e) {
      if (!Files.isDirectory(directory)) {
        throw e;
      }
    }
  }

  private static void moveIntoPlace(Path source, Path target, boolean replaceExisting) throws IOException {
    StandardCopyOption[] options = replaceExisting
        ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
        : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
    try {
      Files.move(source, target, options);
    } catch (AtomicMoveNotSupportedException e) {
      if (replaceExisting) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(source, target);
      }
    }
  }

  private static InputStream openFileRange(Path path, long start, long length) {
    if (start < 0 || length < 0) {
      throw new IllegalArgumentException("range start and length must be non-negative");
    }
    try {
      SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
      channel.position(start);
      return new BoundedSeekableChannelInputStream(channel, length);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open file blob range: " + path, e);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static void transferFileRegion(Path path, long position, long count, OutputStream out) throws IOException {
    WritableByteChannel target = Channels.newChannel(out);
    try (FileChannel source = FileChannel.open(path, StandardOpenOption.READ)) {
      long offset = Math.max(0, position);
      long remaining = Math.max(0, count);
      while (remaining > 0) {
        long transferred = source.transferTo(offset, remaining, target);
        if (transferred <= 0) {
          transferred = transferWithDirectBuffer(source, target, offset, remaining);
          if (transferred <= 0) {
            break;
          }
        }
        offset += transferred;
        remaining -= transferred;
      }
    }
  }

  private static long transferWithDirectBuffer(
      FileChannel source,
      WritableByteChannel target,
      long offset,
      long remaining) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocateDirect((int) Math.min(TRANSFER_FALLBACK_BUFFER_SIZE, remaining));
    source.position(offset);
    int read = source.read(buffer);
    if (read <= 0) {
      return read;
    }
    buffer.flip();
    while (buffer.hasRemaining()) {
      target.write(buffer);
    }
    return read;
  }

  private static final class FileRegionInputStream extends InputStream implements BlobRangeReader, BlobFileRegion {
    private final Path path;
    private final long position;
    private final long count;
    private InputStream delegate;

    private FileRegionInputStream(Path path, long position, long count) {
      this.path = path;
      this.position = Math.max(0, position);
      this.count = Math.max(0, count);
    }

    @Override
    public Path path() {
      return path;
    }

    @Override
    public InputStream openRange(long start, long length) {
      return new FileRegionInputStream(path, start, length);
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public long count() {
      return count;
    }

    @Override
    public void transferFileRegionTo(OutputStream out) throws IOException {
      transferFileRegion(path, position, count, out);
    }

    @Override
    public int read() throws IOException {
      return delegate().read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return delegate().read(b, off, len);
    }

    @Override
    public void close() throws IOException {
      if (delegate != null) {
        delegate.close();
      }
    }

    private InputStream delegate() throws IOException {
      if (delegate == null) {
        delegate = openFileRange(path, position, count);
      }
      return delegate;
    }
  }

  private static final class BoundedSeekableChannelInputStream extends InputStream {
    private final SeekableByteChannel channel;
    private final InputStream delegate;
    private long remaining;

    private BoundedSeekableChannelInputStream(SeekableByteChannel channel, long length) {
      this.channel = channel;
      this.delegate = Channels.newInputStream(channel);
      this.remaining = length;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int value = delegate.read();
      if (value >= 0) {
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int read = delegate.read(b, off, (int) Math.min(len, remaining));
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
