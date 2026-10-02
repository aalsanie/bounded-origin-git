package io.github.aalsanie.boundedorigingit.git;

import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RefGenerationStore {
  private static final String CURRENT_FILE = "current.refs";
  private static final String LOCK_FILE = ".publish.lock";
  private static final int FORMAT_VERSION = 1;
  private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

  private final Path directory;
  private final Path currentFile;
  private final Path lockFile;
  private final String repository;
  private final GitObjectStore objectStore;
  private final GitHashAlgorithm hashAlgorithm;
  private final int maxRefs;
  private final int maxUpdatesPerGeneration;
  private final long maxSnapshotBytes;
  private final Object jvmLock;

  private volatile RefGenerationSnapshot current;

  public RefGenerationStore(
      Path directory,
      String repository,
      GitObjectStore objectStore,
      int maxRefs,
      int maxUpdatesPerGeneration)
      throws IOException {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    this.repository = RepositoryUpdateEvent.validateRepository(repository);
    this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    this.hashAlgorithm = objectStore.hashAlgorithm();
    if (maxRefs <= 0) {
      throw new IllegalArgumentException("maxRefs must be positive");
    }
    if (maxUpdatesPerGeneration <= 0) {
      throw new IllegalArgumentException("maxUpdatesPerGeneration must be positive");
    }
    this.maxRefs = maxRefs;
    this.maxUpdatesPerGeneration = maxUpdatesPerGeneration;
    try {
      this.maxSnapshotBytes = Math.addExact(1024L, Math.multiplyExact((long) maxRefs, 1200L));
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("maxRefs is too large", exception);
    }

    Files.createDirectories(this.directory);
    if (Files.isSymbolicLink(this.directory)
        || !Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("ref generation directory must be a local directory");
    }

    this.currentFile = this.directory.resolve(CURRENT_FILE);
    this.lockFile = this.directory.resolve(LOCK_FILE);
    this.jvmLock = JVM_LOCKS.computeIfAbsent(this.directory, ignored -> new Object());
    this.current = readPublished();
  }

  public RefGenerationSnapshot current() {
    return current;
  }

  public Optional<GitObjectId> resolve(GitRefName ref) {
    return current.resolve(ref);
  }

  public RefGenerationSnapshot reload() throws IOException {
    RefGenerationSnapshot loaded = readPublished();
    current = loaded;
    return loaded;
  }

  public RefGenerationSnapshot publish(
      Collection<RepositoryUpdateEvent> updates, TrustLevel trustLevel) throws IOException {
    Objects.requireNonNull(updates, "updates");
    Objects.requireNonNull(trustLevel, "trustLevel");
    if (trustLevel != TrustLevel.TRUSTED) {
      throw new SecurityException("ref generation publication requires trusted ingress");
    }

    List<RepositoryUpdateEvent> batch = List.copyOf(updates);
    if (batch.isEmpty()) {
      throw new IllegalArgumentException("at least one ref update is required");
    }
    if (batch.size() > maxUpdatesPerGeneration) {
      throw new IllegalArgumentException("ref update batch exceeds configured limit");
    }

    Set<GitRefName> refs = new HashSet<>();
    for (RepositoryUpdateEvent update : batch) {
      Objects.requireNonNull(update, "update");
      if (!repository.equals(update.repository())) {
        throw new IllegalArgumentException("ref update targets a different repository");
      }
      if (!refs.add(update.ref())) {
        throw new IllegalArgumentException("ref update batch contains duplicate refs");
      }
      validateObject(update.before());
      validateObject(update.after());
    }

    synchronized (jvmLock) {
      rejectSymlink(lockFile, "publication lock");
      try (FileChannel channel =
              FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock lock = channel.lock()) {
        if (!lock.isValid()) {
          throw new IOException("ref generation publication lock is not valid");
        }
        RefGenerationSnapshot base = readPublished();
        RefGenerationSnapshot next = apply(base, batch);
        writePublished(next);
        current = next;
        return next;
      }
    }
  }

  private RefGenerationSnapshot apply(
      RefGenerationSnapshot base, List<RepositoryUpdateEvent> batch)
      throws RefGenerationConflictException {
    Map<GitRefName, GitObjectId> refs = new LinkedHashMap<>(base.refs());
    for (RepositoryUpdateEvent update : batch) {
      Optional<GitObjectId> actual = Optional.ofNullable(refs.get(update.ref()));
      if (!actual.equals(update.before())) {
        throw new RefGenerationConflictException(
            "published ref does not match update precondition: " + update.ref().value());
      }
    }

    for (RepositoryUpdateEvent update : batch) {
      if (update.after().isPresent()) {
        refs.put(update.ref(), update.after().orElseThrow());
      } else {
        refs.remove(update.ref());
      }
    }

    if (refs.size() > maxRefs) {
      throw new IllegalArgumentException("ref generation exceeds configured ref limit");
    }

    long generation;
    try {
      generation = Math.addExact(base.generation(), 1L);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("ref generation counter exhausted", exception);
    }
    return new RefGenerationSnapshot(generation, refs);
  }

  private void validateObject(Optional<GitObjectId> objectId) {
    objectId.ifPresent(
        id -> {
          if (id.algorithm() != hashAlgorithm) {
            throw new IllegalArgumentException("ref update object id uses the wrong hash algorithm");
          }
          if (!objectStore.contains(id)) {
            throw new IllegalStateException("ref update object has not been ingested");
          }
        });
  }

  private void writePublished(RefGenerationSnapshot snapshot) throws IOException {
    byte[] bytes = serialize(snapshot);
    if (bytes.length > maxSnapshotBytes) {
      throw new IllegalArgumentException("ref generation exceeds encoded size limit");
    }

    rejectSymlink(currentFile, "published ref generation");
    Path temporary = Files.createTempFile(directory, ".refs-", ".tmp");
    try {
      try (FileChannel channel =
          FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }

      try {
        Files.move(
            temporary,
            currentFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("atomic ref generation publication is not supported", exception);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private RefGenerationSnapshot readPublished() throws IOException {
    if (!Files.exists(currentFile, LinkOption.NOFOLLOW_LINKS)) {
      return new RefGenerationSnapshot(0, Map.of());
    }
    if (Files.isSymbolicLink(currentFile)
        || !Files.isRegularFile(currentFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new RefGenerationIntegrityException(
          "published ref generation is not a regular file");
    }

    long size = Files.size(currentFile);
    if (size <= 0 || size > maxSnapshotBytes) {
      throw new RefGenerationIntegrityException("published ref generation has invalid size");
    }
    return parse(Files.readAllBytes(currentFile));
  }

  private byte[] serialize(RefGenerationSnapshot snapshot) {
    StringBuilder body = new StringBuilder();
    body.append("version=").append(FORMAT_VERSION).append('\n');
    body.append("repository=").append(repository).append('\n');
    body.append("hash=").append(hashAlgorithm.name()).append('\n');
    body.append("generation=").append(snapshot.generation()).append('\n');
    snapshot.refs().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().value()))
        .forEach(
            entry ->
                body.append("ref=")
                    .append(entry.getKey().value())
                    .append('\t')
                    .append(entry.getValue().hexadecimal())
                    .append('\n'));

    byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
    String checksum = sha256(bodyBytes);
    return (body + "checksum=" + checksum + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private RefGenerationSnapshot parse(byte[] bytes) throws RefGenerationIntegrityException {
    String text = decodeUtf8(bytes);
    String checksumMarker = "\nchecksum=";
    int checksumBoundary = text.lastIndexOf(checksumMarker);
    if (checksumBoundary < 0
        || text.indexOf(checksumMarker) != checksumBoundary
        || !text.endsWith("\n")) {
      throw new RefGenerationIntegrityException("published ref generation checksum is malformed");
    }

    String body = text.substring(0, checksumBoundary + 1);
    String checksumLine = text.substring(checksumBoundary + 1, text.length() - 1);
    String expectedChecksum = checksumLine.substring("checksum=".length());
    if (expectedChecksum.length() != 64 || !isHex(expectedChecksum)) {
      throw new RefGenerationIntegrityException("published ref generation checksum is malformed");
    }
    String actualChecksum = sha256(body.getBytes(StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(
        expectedChecksum.getBytes(StandardCharsets.US_ASCII),
        actualChecksum.getBytes(StandardCharsets.US_ASCII))) {
      throw new RefGenerationIntegrityException("published ref generation checksum does not match");
    }

    String[] lines = body.split("\n", -1);
    if (lines.length < 5 || !lines[lines.length - 1].isEmpty()) {
      throw new RefGenerationIntegrityException("published ref generation is malformed");
    }
    if (!("version=" + FORMAT_VERSION).equals(lines[0])) {
      throw new RefGenerationIntegrityException("unsupported ref generation format");
    }
    if (!("repository=" + repository).equals(lines[1])) {
      throw new RefGenerationIntegrityException("ref generation repository does not match");
    }
    if (!("hash=" + hashAlgorithm.name()).equals(lines[2])) {
      throw new RefGenerationIntegrityException("ref generation hash algorithm does not match");
    }

    long generation = parseGeneration(lines[3]);
    Map<GitRefName, GitObjectId> refs = new LinkedHashMap<>();
    String previous = null;
    for (int index = 4; index < lines.length - 1; index++) {
      String line = lines[index];
      if (!line.startsWith("ref=")) {
        throw new RefGenerationIntegrityException("published ref generation contains unknown data");
      }
      int separator = line.indexOf('\t', 4);
      if (separator < 0 || separator != line.lastIndexOf('\t')) {
        throw new RefGenerationIntegrityException("published ref generation contains malformed ref");
      }

      GitRefName ref;
      GitObjectId objectId;
      try {
        ref = new GitRefName(line.substring(4, separator));
        objectId = new GitObjectId(hashAlgorithm, line.substring(separator + 1));
      } catch (IllegalArgumentException exception) {
        throw new RefGenerationIntegrityException(
            "published ref generation contains invalid ref data", exception);
      }

      if (previous != null && previous.compareTo(ref.value()) >= 0) {
        throw new RefGenerationIntegrityException("published refs are not strictly sorted");
      }
      if (refs.put(ref, objectId) != null) {
        throw new RefGenerationIntegrityException("published ref generation contains duplicate refs");
      }
      previous = ref.value();
      if (refs.size() > maxRefs) {
        throw new RefGenerationIntegrityException("published ref generation exceeds ref limit");
      }
    }
    return new RefGenerationSnapshot(generation, refs);
  }

  private static long parseGeneration(String line) throws RefGenerationIntegrityException {
    if (!line.startsWith("generation=")) {
      throw new RefGenerationIntegrityException("published ref generation is missing generation");
    }
    try {
      long generation = Long.parseLong(line.substring("generation=".length()));
      if (generation <= 0) {
        throw new NumberFormatException("non-positive generation");
      }
      return generation;
    } catch (NumberFormatException exception) {
      throw new RefGenerationIntegrityException(
          "published ref generation number is invalid", exception);
    }
  }

  private static String decodeUtf8(byte[] bytes) throws RefGenerationIntegrityException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new RefGenerationIntegrityException(
          "published ref generation is not valid UTF-8", exception);
    }
  }

  private static String sha256(byte[] bytes) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
    return HexFormat.of().formatHex(digest.digest(bytes));
  }

  private static boolean isHex(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (Character.digit(value.charAt(index), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  private static void rejectSymlink(Path path, String label) throws IOException {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
      throw new IOException(label + " must not be a symbolic link");
    }
  }
}
