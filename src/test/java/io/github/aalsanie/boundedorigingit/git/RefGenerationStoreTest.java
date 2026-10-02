package io.github.aalsanie.boundedorigingit.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RefGenerationStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void publishesAndResolvesCompleteGenerationWithoutGit() throws Exception {
    Fixture fixture = fixture("one");
    GitObjectId main = fixture.object("main");
    GitObjectId tag = fixture.object("tag");

    RefGenerationSnapshot generation =
        fixture.refs().publish(
            List.of(
                create("refs/heads/main", main),
                create("refs/tags/v1", tag)),
            TrustLevel.TRUSTED);

    assertEquals(1, generation.generation());
    assertEquals(Optional.of(main), fixture.refs().resolve(new GitRefName("refs/heads/main")));
    assertEquals(Optional.of(tag), fixture.refs().resolve(new GitRefName("refs/tags/v1")));
  }

  @Test
  void publishesMultiRefUpdateAsOneAtomicGeneration() throws Exception {
    Fixture fixture = fixture("atomic");
    GitObjectId firstA = fixture.object("first-a");
    GitObjectId firstB = fixture.object("first-b");
    GitObjectId secondA = fixture.object("second-a");
    GitObjectId secondB = fixture.object("second-b");

    fixture.refs().publish(
        List.of(create("refs/heads/a", firstA), create("refs/heads/b", firstB)),
        TrustLevel.TRUSTED);

    AtomicBoolean running = new AtomicBoolean(true);
    List<String> invalidStates = java.util.Collections.synchronizedList(new ArrayList<>());
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (running.get()) {
                    RefGenerationSnapshot snapshot = fixture.refs().current();
                    GitObjectId a = snapshot.refs().get(new GitRefName("refs/heads/a"));
                    GitObjectId b = snapshot.refs().get(new GitRefName("refs/heads/b"));
                    boolean first = firstA.equals(a) && firstB.equals(b);
                    boolean second = secondA.equals(a) && secondB.equals(b);
                    if (!first && !second) {
                      invalidStates.add(String.valueOf(snapshot.refs()));
                    }
                  }
                });

    fixture.refs().publish(
        List.of(
            update("refs/heads/a", firstA, secondA),
            update("refs/heads/b", firstB, secondB)),
        TrustLevel.TRUSTED);
    running.set(false);
    reader.join();

    assertTrue(invalidStates.isEmpty(), () -> "observed partial generation " + invalidStates);
    assertEquals(2, fixture.refs().current().generation());
  }

  @Test
  void rejectsStalePreconditionWithoutPublishing() throws Exception {
    Fixture fixture = fixture("conflict");
    GitObjectId first = fixture.object("first");
    GitObjectId second = fixture.object("second");
    GitObjectId stale = fixture.object("stale");
    fixture.refs().publish(List.of(create("refs/heads/main", first)), TrustLevel.TRUSTED);

    assertThrows(
        RefGenerationConflictException.class,
        () ->
            fixture.refs().publish(
                List.of(update("refs/heads/main", stale, second)),
                TrustLevel.TRUSTED));

    assertEquals(1, fixture.refs().current().generation());
    assertEquals(
        Optional.of(first),
        fixture.refs().resolve(new GitRefName("refs/heads/main")));
  }

  @Test
  void serializesConcurrentPublishersWithoutLostUpdates() throws Exception {
    Path root = temporaryDirectory.resolve("concurrent");
    GitObjectStore objects =
        new GitObjectStore(root.resolve("objects"), GitHashAlgorithm.SHA1, 1024);
    GitObjectId a = ingest(objects, "a");
    GitObjectId b = ingest(objects, "b");
    RefGenerationStore first =
        new RefGenerationStore(root.resolve("refs"), "project", objects, 16, 8);
    RefGenerationStore second =
        new RefGenerationStore(root.resolve("refs"), "project", objects, 16, 8);
    CountDownLatch start = new CountDownLatch(1);

    Callable<RefGenerationSnapshot> publishA =
        () -> {
          start.await();
          return first.publish(List.of(create("refs/heads/a", a)), TrustLevel.TRUSTED);
        };
    Callable<RefGenerationSnapshot> publishB =
        () -> {
          start.await();
          return second.publish(List.of(create("refs/heads/b", b)), TrustLevel.TRUSTED);
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<RefGenerationSnapshot> resultA = executor.submit(publishA);
      Future<RefGenerationSnapshot> resultB = executor.submit(publishB);
      start.countDown();
      resultA.get();
      resultB.get();
    }

    RefGenerationSnapshot current = first.reload();
    assertEquals(2, current.generation());
    assertEquals(Optional.of(a), current.resolve(new GitRefName("refs/heads/a")));
    assertEquals(Optional.of(b), current.resolve(new GitRefName("refs/heads/b")));
  }

  @Test
  void reloadsPublishedGenerationAfterRestart() throws Exception {
    Fixture fixture = fixture("restart");
    GitObjectId main = fixture.object("main");
    fixture.refs().publish(List.of(create("refs/heads/main", main)), TrustLevel.TRUSTED);

    RefGenerationStore restarted =
        new RefGenerationStore(
            fixture.root().resolve("refs"), "project", fixture.objects(), 16, 8);

    assertEquals(1, restarted.current().generation());
    assertEquals(
        Optional.of(main), restarted.resolve(new GitRefName("refs/heads/main")));
  }

  @Test
  void rejectsCorruptPublishedGenerationOnRestart() throws Exception {
    Fixture fixture = fixture("corrupt");
    GitObjectId main = fixture.object("main");
    fixture.refs().publish(List.of(create("refs/heads/main", main)), TrustLevel.TRUSTED);
    Path current = fixture.root().resolve("refs").resolve("current.refs");
    byte[] bytes = Files.readAllBytes(current);
    bytes[bytes.length / 2] ^= 1;
    Files.write(current, bytes);

    assertThrows(
        RefGenerationIntegrityException.class,
        () ->
            new RefGenerationStore(
                fixture.root().resolve("refs"), "project", fixture.objects(), 16, 8));
  }

  @Test
  void rejectsUntrustedMissingAndWrongRepositoryUpdates() throws Exception {
    Fixture fixture = fixture("validation");
    GitObjectId main = fixture.object("main");
    GitObjectId missing = objectId("missing");

    assertThrows(
        SecurityException.class,
        () ->
            fixture.refs().publish(
                List.of(create("refs/heads/main", main)), TrustLevel.UNTRUSTED));
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.refs().publish(
                List.of(create("refs/heads/main", missing)), TrustLevel.TRUSTED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.refs().publish(
                List.of(
                    new RepositoryUpdateEvent(
                        "other",
                        new GitRefName("refs/heads/main"),
                        Optional.empty(),
                        Optional.of(main))),
                TrustLevel.TRUSTED));

    assertEquals(0, fixture.refs().current().generation());
  }

  @Test
  void boundsBatchAndSnapshotSizeBeforePublication() throws Exception {
    Path root = temporaryDirectory.resolve("bounds");
    GitObjectStore objects =
        new GitObjectStore(root.resolve("objects"), GitHashAlgorithm.SHA1, 1024);
    GitObjectId a = ingest(objects, "a");
    GitObjectId b = ingest(objects, "b");
    RefGenerationStore refs =
        new RefGenerationStore(root.resolve("refs"), "project", objects, 1, 1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            refs.publish(
                List.of(create("refs/heads/a", a), create("refs/heads/b", b)),
                TrustLevel.TRUSTED));

    refs.publish(List.of(create("refs/heads/a", a)), TrustLevel.TRUSTED);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            refs.publish(List.of(create("refs/heads/b", b)), TrustLevel.TRUSTED));

    assertEquals(1, refs.current().generation());
    assertFalse(refs.resolve(new GitRefName("refs/heads/b")).isPresent());
  }

  @Test
  void supportsRefDeletionInNewGeneration() throws Exception {
    Fixture fixture = fixture("delete");
    GitObjectId main = fixture.object("main");
    fixture.refs().publish(List.of(create("refs/heads/main", main)), TrustLevel.TRUSTED);

    RefGenerationSnapshot deleted =
        fixture.refs().publish(
            List.of(
                new RepositoryUpdateEvent(
                    "project",
                    new GitRefName("refs/heads/main"),
                    Optional.of(main),
                    Optional.empty())),
            TrustLevel.TRUSTED);

    assertEquals(2, deleted.generation());
    assertTrue(deleted.refs().isEmpty());
  }

  private Fixture fixture(String name) throws Exception {
    Path root = temporaryDirectory.resolve(name);
    GitObjectStore objects =
        new GitObjectStore(root.resolve("objects"), GitHashAlgorithm.SHA1, 1024);
    RefGenerationStore refs =
        new RefGenerationStore(root.resolve("refs"), "project", objects, 16, 8);
    return new Fixture(root, objects, refs);
  }

  private static RepositoryUpdateEvent create(String ref, GitObjectId after) {
    return new RepositoryUpdateEvent(
        "project", new GitRefName(ref), Optional.empty(), Optional.of(after));
  }

  private static RepositoryUpdateEvent update(
      String ref, GitObjectId before, GitObjectId after) {
    return new RepositoryUpdateEvent(
        "project", new GitRefName(ref), Optional.of(before), Optional.of(after));
  }

  private static GitObjectId ingest(GitObjectStore store, String content) throws Exception {
    byte[] bytes = (content + "\n").getBytes(StandardCharsets.UTF_8);
    GitObjectId id = objectId(content);
    store.ingest(id, GitObjectType.BLOB, bytes.length, new ByteArrayInputStream(bytes));
    return id;
  }

  private static GitObjectId objectId(String content) {
    byte[] bytes = (content + "\n").getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
    digest.update(
        ("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
    digest.update(bytes);
    return new GitObjectId(
        GitHashAlgorithm.SHA1, HexFormat.of().formatHex(digest.digest()));
  }

  private record Fixture(Path root, GitObjectStore objects, RefGenerationStore refs) {
    GitObjectId object(String content) throws Exception {
      return ingest(objects, content);
    }
  }
}
