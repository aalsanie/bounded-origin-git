package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CgitArtifactServiceTest {
  private static final Budget BUDGET =
      new Budget(1, 4, Duration.ofSeconds(5), 4096);

  @TempDir Path temporaryDirectory;

  @Test
  void lookupMissNeverInvokesRenderer() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    try (Fixture fixture =
        fixture(
            (snapshot, operation) -> {
              renders.incrementAndGet();
              return artifact("unexpected");
            })) {
      assertTrue(fixture.service().lookup(snapshot(1, 1), operation()).isEmpty());
      assertEquals(0, renders.get());
    }
  }

  @Test
  void rejectsUntrustedMaterializationBeforeRendering() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    try (Fixture fixture =
        fixture(
            (snapshot, operation) -> {
              renders.incrementAndGet();
              return artifact("unexpected");
            })) {
      assertThrows(
          SecurityException.class,
          () ->
              fixture
                  .service()
                  .materialize(snapshot(1, 1), operation(), TrustLevel.UNTRUSTED));
      assertEquals(0, renders.get());
      assertTrue(fixture.service().lookup(snapshot(1, 1), operation()).isEmpty());
    }
  }

  @Test
  void materializesPersistsAndReusesWithoutRenderingAgain() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    try (Fixture fixture =
        fixture(
            (snapshot, operation) -> {
              renders.incrementAndGet();
              return artifact("generation=" + snapshot.generation());
            })) {
      RefGenerationSnapshot snapshot = snapshot(1, 1);

      CgitArtifactService.Materialization first =
          fixture.service().materialize(snapshot, operation(), TrustLevel.TRUSTED);
      first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertFalse(first.alreadyStored());
      assertFalse(first.joined());
      assertEquals(1, renders.get());
      assertEquals("generation=1", read(fixture.service().lookup(snapshot, operation())));

      CgitArtifactService.Materialization second =
          fixture.service().materialize(snapshot, operation(), TrustLevel.TRUSTED);
      second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertTrue(second.alreadyStored());
      assertFalse(second.joined());
      assertEquals(1, renders.get());
    }
  }

  @Test
  void singleFlightsEquivalentMaterialization() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (Fixture fixture =
        fixture(
            (snapshot, operation) -> {
              renders.incrementAndGet();
              started.countDown();
              await(release);
              return artifact("shared");
            })) {
      RefGenerationSnapshot snapshot = snapshot(1, 1);
      CgitArtifactService.Materialization first =
          fixture.service().materialize(snapshot, operation(), TrustLevel.TRUSTED);
      assertTrue(started.await(5, TimeUnit.SECONDS));

      CgitArtifactService.Materialization second =
          fixture.service().materialize(snapshot, operation(), TrustLevel.TRUSTED);
      assertTrue(second.joined());

      release.countDown();
      first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(1, renders.get());
    }
  }

  @Test
  void namespacesArtifactsByRefSnapshot() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    try (Fixture fixture =
        fixture(
            (snapshot, operation) -> {
              renders.incrementAndGet();
              return artifact("generation=" + snapshot.generation());
            })) {
      RefGenerationSnapshot first = snapshot(1, 1);
      RefGenerationSnapshot second = snapshot(2, 2);

      fixture.service().materialize(first, operation(), TrustLevel.TRUSTED).completion()
          .toCompletableFuture().get(5, TimeUnit.SECONDS);
      fixture.service().materialize(second, operation(), TrustLevel.TRUSTED).completion()
          .toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(2, renders.get());
      assertEquals("generation=1", read(fixture.service().lookup(first, operation())));
      assertEquals("generation=2", read(fixture.service().lookup(second, operation())));
    }
  }

  @Test
  void rejectsOversizedResultBeforePersistence() throws Exception {
    byte[] bytes = new byte[4097];
    try (Fixture fixture =
        fixture(
            (snapshot, operation) ->
                new Artifact(
                    bytes.length,
                    Map.of("Content-Type", "text/plain"),
                    () -> new ByteArrayInputStream(bytes)))) {
      RefGenerationSnapshot snapshot = snapshot(1, 1);
      CgitArtifactService.Materialization materialization =
          fixture.service().materialize(snapshot, operation(), TrustLevel.TRUSTED);

      assertThrows(
          Exception.class,
          () -> materialization.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertTrue(fixture.service().lookup(snapshot, operation()).isEmpty());
    }
  }

  @Test
  void rejectsSnapshotMismatchBeforeRendering() throws Exception {
    AtomicInteger renders = new AtomicInteger();
    CgitArtifactNamespace namespace =
        new CgitArtifactNamespace("project", new GitRefName("refs/heads/main"));
    Operation pinned = namespace.pin(operation(), snapshot(1, 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> namespace.validate(pinned, snapshot(2, 2)));
    assertEquals(0, renders.get());
  }

  private Fixture fixture(CgitRenderer renderer) throws Exception {
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(BUDGET, Duration.ofMillis(10), 32);
    FileSystemArtifactStore store =
        new FileSystemArtifactStore(
            temporaryDirectory.resolve("artifacts-" + System.nanoTime()),
            1_000_000,
            100_000);
    CgitArtifactNamespace namespace =
        new CgitArtifactNamespace("project", new GitRefName("refs/heads/main"));
    CgitArtifactService service =
        new CgitArtifactService(
            namespace,
            executor,
            store,
            renderer,
            "cgit-render",
            1,
            "fixture-v1",
            BUDGET);
    return new Fixture(executor, store, service);
  }

  private static Operation operation() {
    return new Operation(
        "cgit-render",
        Map.of(
            "repository", List.of("project"),
            "page", List.of("summary"),
            "head", List.of("main")));
  }

  private static RefGenerationSnapshot snapshot(long generation, int value) {
    String hex = String.format("%040x", value);
    return new RefGenerationSnapshot(
        generation,
        Map.of(
            new GitRefName("refs/heads/main"),
            new GitObjectId(GitHashAlgorithm.SHA1, hex)));
  }

  private static Artifact artifact(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return new Artifact(
        bytes.length,
        Map.of("Content-Type", "text/plain"),
        () -> new ByteArrayInputStream(bytes));
  }

  private static String read(Optional<Artifact> value) throws Exception {
    Artifact artifact = value.orElseThrow();
    try {
      try (var input = artifact.body().openStream()) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    } finally {
      artifact.body().close();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for fixture");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture interrupted", exception);
    }
  }

  private record Fixture(
      BoundedOriginExecutor executor,
      FileSystemArtifactStore store,
      CgitArtifactService service)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      executor.close();
      store.close();
    }
  }
}
