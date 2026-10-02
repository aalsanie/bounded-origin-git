package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class GitObjectStore {
  private static final int BUFFER_SIZE = 16 * 1024;
  private static final int LOCK_STRIPES = 256;

  private final Path objectDirectory;
  private final GitHashAlgorithm hashAlgorithm;
  private final long maxObjectBytes;
  private final Object[] locks = new Object[LOCK_STRIPES];

  public GitObjectStore(
      Path objectDirectory, GitHashAlgorithm hashAlgorithm, long maxObjectBytes) throws IOException {
    this.objectDirectory = Objects.requireNonNull(objectDirectory, "objectDirectory").toAbsolutePath().normalize();
    this.hashAlgorithm = Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
    if (maxObjectBytes < 0) {
      throw new IllegalArgumentException("maxObjectBytes must be non-negative");
    }
    this.maxObjectBytes = maxObjectBytes;
    Files.createDirectories(this.objectDirectory);
    if (Files.isSymbolicLink(this.objectDirectory)
        || !Files.isDirectory(this.objectDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("object directory must be a local directory");
    }
    for (int index = 0; index < locks.length; index++) {
      locks[index] = new Object();
    }
  }

  public Result ingest(
      GitObjectId expectedId, GitObjectType type, long size, InputStream content) throws IOException {
    Objects.requireNonNull(expectedId, "expectedId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(content, "content");
    if (expectedId.algorithm() != hashAlgorithm) {
      throw new IllegalArgumentException("object id hash algorithm does not match the store");
    }
    if (size < 0) {
      throw new IllegalArgumentException("size must be non-negative");
    }
    if (size > maxObjectBytes) {
      throw new IllegalArgumentException("object exceeds configured size limit");
    }

    Path target = objectPath(expectedId);
    synchronized (locks[lockIndex(expectedId)]) {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        validateExisting(target, expectedId, type, size);
        return new Result(expectedId, type, size, false, target);
      }
      return publish(target, expectedId, type, size, content);
    }
  }

  GitHashAlgorithm hashAlgorithm() {
    return hashAlgorithm;
  }

  public boolean contains(GitObjectId objectId) {
    Path target = objectPath(objectId);
    return !Files.isSymbolicLink(target)
        && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
  }

  public Path objectPath(GitObjectId objectId) {
    Objects.requireNonNull(objectId, "objectId");
    if (objectId.algorithm() != hashAlgorithm) {
      throw new IllegalArgumentException("object id hash algorithm does not match the store");
    }
    return objectDirectory.resolve(objectId.fanout()).resolve(objectId.filename());
  }

  private Result publish(
      Path target, GitObjectId expectedId, GitObjectType type, long size, InputStream content)
      throws IOException {
    Path fanout = target.getParent();
    createFanout(fanout);

    Path temporary = Files.createTempFile(fanout, ".ingest-", ".tmp");
    boolean published = false;
    try {
      writeObject(temporary, expectedId, type, size, content);
      try {
        Files.createLink(target, temporary);
        published = true;
        return new Result(expectedId, type, size, true, target);
      } catch (FileAlreadyExistsException race) {
        validateExisting(target, expectedId, type, size);
        return new Result(expectedId, type, size, false, target);
      }
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        if (!published) {
          throw cleanupFailure;
        }
      }
    }
  }

  private void createFanout(Path fanout) throws IOException {
    Files.createDirectories(fanout);
    if (Files.isSymbolicLink(fanout) || !Files.isDirectory(fanout, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("object fanout must be a local directory");
    }
  }

  private void writeObject(
      Path temporary, GitObjectId expectedId, GitObjectType type, long size, InputStream content)
      throws IOException {
    byte[] header = header(type, size);
    MessageDigest digest = hashAlgorithm.newDigest();
    digest.update(header);

    try (FileChannel channel =
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        OutputStream raw = Channels.newOutputStream(channel);
        DeflaterOutputStream compressed = new DeflaterOutputStream(raw)) {
      compressed.write(header);
      copyExact(content, compressed, digest, size);
      if (content.read() != -1) {
        throw new GitObjectIntegrityException("object payload exceeds declared size");
      }
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!expectedId.hexadecimal().equals(actual)) {
        throw new GitObjectIntegrityException("object payload does not match expected object id");
      }
      compressed.finish();
      compressed.flush();
      channel.force(true);
    } catch (IOException | RuntimeException exception) {
      Files.deleteIfExists(temporary);
      throw exception;
    }
  }

  private void validateExisting(
      Path target, GitObjectId expectedId, GitObjectType type, long size) throws IOException {
    if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new GitObjectIntegrityException("existing object path is not a regular file");
    }

    byte[] header = header(type, size);
    MessageDigest digest = hashAlgorithm.newDigest();
    digest.update(header);

    try (InputStream raw = Files.newInputStream(target);
        InflaterInputStream inflated = new InflaterInputStream(raw)) {
      byte[] existingHeader = inflated.readNBytes(header.length);
      if (existingHeader.length != header.length || !MessageDigest.isEqual(header, existingHeader)) {
        throw new GitObjectIntegrityException("existing object header does not match expected object");
      }

      byte[] buffer = new byte[BUFFER_SIZE];
      long remaining = size;
      while (remaining > 0) {
        int requested = (int) Math.min(buffer.length, remaining);
        int read = inflated.read(buffer, 0, requested);
        if (read < 0) {
          throw new GitObjectIntegrityException("existing object payload is truncated");
        }
        if (read == 0) {
          continue;
        }
        digest.update(buffer, 0, read);
        remaining -= read;
      }
      if (inflated.read() != -1) {
        throw new GitObjectIntegrityException("existing object payload exceeds expected size");
      }
    } catch (GitObjectIntegrityException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new GitObjectIntegrityException("existing object is unreadable or corrupt", exception);
    }

    String actual = HexFormat.of().formatHex(digest.digest());
    if (!expectedId.hexadecimal().equals(actual)) {
      throw new GitObjectIntegrityException("existing object content does not match its object id");
    }
  }

  private static void copyExact(
      InputStream content, OutputStream destination, MessageDigest digest, long size)
      throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long remaining = size;
    while (remaining > 0) {
      int requested = (int) Math.min(buffer.length, remaining);
      int read = content.read(buffer, 0, requested);
      if (read < 0) {
        throw new GitObjectIntegrityException("object payload is shorter than declared size");
      }
      if (read == 0) {
        continue;
      }
      digest.update(buffer, 0, read);
      destination.write(buffer, 0, read);
      remaining -= read;
    }
  }

  private static byte[] header(GitObjectType type, long size) {
    return (type.wireName() + " " + size + "\0").getBytes(StandardCharsets.US_ASCII);
  }

  private static int lockIndex(GitObjectId objectId) {
    return Integer.parseInt(objectId.fanout(), 16);
  }

  public record Result(
      GitObjectId objectId, GitObjectType type, long size, boolean created, Path path) {
    public Result {
      Objects.requireNonNull(objectId, "objectId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(path, "path");
      if (size < 0) {
        throw new IllegalArgumentException("size must be non-negative");
      }
    }
  }
}
