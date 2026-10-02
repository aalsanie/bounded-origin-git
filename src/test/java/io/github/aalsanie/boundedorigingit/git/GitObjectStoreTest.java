package io.github.aalsanie.boundedorigingit.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitObjectStoreTest {
  private static final byte[] HELLO = "hello\n".getBytes(StandardCharsets.UTF_8);
  private static final GitObjectId HELLO_SHA1 =
      new GitObjectId(GitHashAlgorithm.SHA1, "ce013625030ba8dba906f756967f9e9ca394464a");

  @TempDir Path temporaryDirectory;

  @Test
  void publishesNativeLooseObjectFormat() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);

    GitObjectStore.Result result =
        store.ingest(
            HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));

    assertTrue(result.created());
    assertEquals(store.objectPath(HELLO_SHA1), result.path());
    assertArrayEquals(
        concatenate("blob 6\0".getBytes(StandardCharsets.US_ASCII), HELLO),
        inflate(result.path()));
  }

  @Test
  void isIdempotentForExistingValidObject() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);

    GitObjectStore.Result first =
        store.ingest(
            HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));
    GitObjectStore.Result second =
        store.ingest(
            HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));

    assertTrue(first.created());
    assertFalse(second.created());
    assertEquals(first.path(), second.path());
  }

  @Test
  void supportsSha256Repositories() throws Exception {
    byte[] payload = "sha256 object\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId id = objectId(GitHashAlgorithm.SHA256, GitObjectType.BLOB, payload);
    GitObjectStore store = store(GitHashAlgorithm.SHA256, 1024);

    GitObjectStore.Result result =
        store.ingest(id, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));

    assertTrue(result.created());
    assertArrayEquals(
        concatenate(
            ("blob " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII), payload),
        inflate(result.path()));
  }

  @Test
  void rejectsMismatchedObjectIdWithoutPublishing() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);
    GitObjectId wrong =
        new GitObjectId(GitHashAlgorithm.SHA1, "0000000000000000000000000000000000000000");

    assertThrows(
        GitObjectIntegrityException.class,
        () ->
            store.ingest(
                wrong, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO)));

    assertFalse(Files.exists(store.objectPath(wrong)));
  }

  @Test
  void rejectsPayloadLengthMismatchWithoutPublishing() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);

    assertThrows(
        GitObjectIntegrityException.class,
        () ->
            store.ingest(
                HELLO_SHA1,
                GitObjectType.BLOB,
                HELLO.length + 1L,
                new ByteArrayInputStream(HELLO)));
    assertThrows(
        GitObjectIntegrityException.class,
        () ->
            store.ingest(
                HELLO_SHA1,
                GitObjectType.BLOB,
                HELLO.length - 1L,
                new ByteArrayInputStream(HELLO)));

    assertFalse(Files.exists(store.objectPath(HELLO_SHA1)));
  }

  @Test
  void refusesToRepairCorruptPublishedObject() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);
    store.ingest(
        HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));
    Path path = store.objectPath(HELLO_SHA1);
    byte[] corrupt = {1, 2, 3, 4};
    Files.write(path, corrupt);

    assertThrows(
        GitObjectIntegrityException.class,
        () ->
            store.ingest(
                HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO)));

    assertArrayEquals(corrupt, Files.readAllBytes(path));
  }

  @Test
  void serializesConcurrentPublicationOfTheSameObject() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 1024);
    CountDownLatch start = new CountDownLatch(1);
    Callable<GitObjectStore.Result> task =
        () -> {
          start.await();
          return store.ingest(
              HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<GitObjectStore.Result> first = executor.submit(task);
      Future<GitObjectStore.Result> second = executor.submit(task);
      start.countDown();

      List<GitObjectStore.Result> results = List.of(first.get(), second.get());
      assertEquals(1, results.stream().filter(GitObjectStore.Result::created).count());
      assertEquals(1, results.stream().filter(result -> !result.created()).count());
    }
  }

  @Test
  void enforcesHashAlgorithmAndSizeBoundaries() throws Exception {
    GitObjectStore store = store(GitHashAlgorithm.SHA1, 5);
    GitObjectId sha256 = objectId(GitHashAlgorithm.SHA256, GitObjectType.BLOB, HELLO);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.ingest(
                sha256, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.ingest(
                HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GitObjectId(GitHashAlgorithm.SHA1, "not-an-object-id"));
  }

  @Test
  void publishesObjectsReadableByGit() throws Exception {
    Path repository = temporaryDirectory.resolve("bare.git");
    runGit("init", "--bare", repository.toString());
    GitObjectStore store =
        new GitObjectStore(repository.resolve("objects"), GitHashAlgorithm.SHA1, 1024);

    store.ingest(
        HELLO_SHA1, GitObjectType.BLOB, HELLO.length, new ByteArrayInputStream(HELLO));

    assertEquals("blob", runGit("--git-dir=" + repository, "cat-file", "-t", HELLO_SHA1.hexadecimal()).trim());
    assertArrayEquals(
        HELLO,
        runGitBytes("--git-dir=" + repository, "cat-file", "-p", HELLO_SHA1.hexadecimal()));
  }

  private GitObjectStore store(GitHashAlgorithm algorithm, long maxObjectBytes) throws IOException {
    return new GitObjectStore(
        temporaryDirectory.resolve(algorithm.name().toLowerCase()).resolve("objects"),
        algorithm,
        maxObjectBytes);
  }

  private static GitObjectId objectId(
      GitHashAlgorithm algorithm, GitObjectType type, byte[] payload) {
    MessageDigest digest = algorithm.newDigest();
    digest.update(
        (type.wireName() + " " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII));
    digest.update(payload);
    return new GitObjectId(algorithm, HexFormat.of().formatHex(digest.digest()));
  }

  private static byte[] inflate(Path path) throws IOException {
    try (InflaterInputStream input = new InflaterInputStream(Files.newInputStream(path))) {
      return input.readAllBytes();
    }
  }

  private static byte[] concatenate(byte[] left, byte[] right) {
    byte[] result = new byte[left.length + right.length];
    System.arraycopy(left, 0, result, 0, left.length);
    System.arraycopy(right, 0, result, left.length, right.length);
    return result;
  }

  private static String runGit(String... arguments) throws Exception {
    return new String(runGitBytes(arguments), StandardCharsets.UTF_8);
  }

  private static byte[] runGitBytes(String... arguments) throws Exception {
    Process process =
        new ProcessBuilder(concatenateArguments("git", arguments))
            .redirectErrorStream(true)
            .start();
    byte[] output = process.getInputStream().readAllBytes();
    int exit = process.waitFor();
    if (exit != 0) {
      throw new AssertionError(
          "git exited with " + exit + ": " + new String(output, StandardCharsets.UTF_8));
    }
    return output;
  }

  private static String[] concatenateArguments(String command, String[] arguments) {
    String[] result = new String[arguments.length + 1];
    result[0] = command;
    System.arraycopy(arguments, 0, result, 1, arguments.length);
    return result;
  }
}
