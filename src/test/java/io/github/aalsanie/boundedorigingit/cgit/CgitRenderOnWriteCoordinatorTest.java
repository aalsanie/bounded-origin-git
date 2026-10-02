package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitObjectType;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationStore;
import io.github.aalsanie.boundedorigingit.git.RepositoryUpdateEvent;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CgitRenderOnWriteCoordinatorTest {
  private static final Budget BUDGET =
      new Budget(1, 4, Duration.ofSeconds(5), 4096);

  @TempDir Path temporaryDirectory;

  @Test
  void publishesGenerationAndMaterializesPlannedArtifact() throws Exception {
    try (Fixture fixture = fixture()) {
      RepositoryUpdateEvent update = fixture.update();
      CgitMaterializationBatch batch =
          fixture.coordinator().publishAndMaterialize(
              List.of(update), TrustLevel.TRUSTED);

      batch.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(1, batch.snapshot().generation());
      assertEquals(1, fixture.renders().get());
      assertTrue(
          fixture.service().lookup(batch.snapshot(), operation()).isPresent());
    }
  }

  @Test
  void rejectsUntrustedIngressBeforePlanningOrPublication() throws Exception {
    try (Fixture fixture = fixture()) {
      AtomicInteger plans = new AtomicInteger();
      CgitRenderOnWriteCoordinator coordinator =
          new CgitRenderOnWriteCoordinator(
              fixture.refs(),
              update -> {
                plans.incrementAndGet();
                return List.of(operation());
              },
              fixture.service(),
              4);

      assertThrows(
          SecurityException.class,
          () ->
              coordinator.publishAndMaterialize(
                  List.of(fixture.update()), TrustLevel.UNTRUSTED));
      assertEquals(0, plans.get());
      assertEquals(0, fixture.refs().current().generation());
    }
  }

  @Test
  void rejectsOversizedPlanBeforePublishingRefs() throws Exception {
    try (Fixture fixture = fixture()) {
      CgitRenderOnWriteCoordinator coordinator =
          new CgitRenderOnWriteCoordinator(
              fixture.refs(),
              update ->
                  List.of(
                      operation(),
                      new Operation(
                          "cgit-render",
                          Map.of(
                              "repository", List.of("project"),
                              "page", List.of("refs")))),
              fixture.service(),
              1);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              coordinator.publishAndMaterialize(
                  List.of(fixture.update()), TrustLevel.TRUSTED));
      assertEquals(0, fixture.refs().current().generation());
      assertEquals(0, fixture.renders().get());
    }
  }

  private Fixture fixture() throws Exception {
    GitObjectStore objects =
        new GitObjectStore(
            temporaryDirectory.resolve("objects-" + System.nanoTime()),
            GitHashAlgorithm.SHA1,
            1024);
    byte[] payload = "tip\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId tip = objectId(payload);
    objects.ingest(
        tip, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));

    RefGenerationStore refs =
        new RefGenerationStore(
            temporaryDirectory.resolve("refs-" + System.nanoTime()),
            "project",
            objects,
            16,
            8);
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(BUDGET, Duration.ofMillis(10), 32);
    FileSystemArtifactStore store =
        new FileSystemArtifactStore(
            temporaryDirectory.resolve("artifacts-" + System.nanoTime()),
            1_000_000,
            100_000);
    AtomicInteger renders = new AtomicInteger();
    CgitArtifactNamespace namespace =
        new CgitArtifactNamespace("project", new GitRefName("refs/heads/main"));
    CgitArtifactService service =
        new CgitArtifactService(
            namespace,
            executor,
            store,
            (snapshot, operation) -> {
              renders.incrementAndGet();
              byte[] bytes =
                  ("generation=" + snapshot.generation())
                      .getBytes(StandardCharsets.UTF_8);
              return new Artifact(
                  bytes.length,
                  Map.of("Content-Type", "text/plain"),
                  () -> new ByteArrayInputStream(bytes));
            },
            "cgit-render",
            1,
            "fixture-v1",
            BUDGET);
    CgitRenderOnWriteCoordinator coordinator =
        new CgitRenderOnWriteCoordinator(
            refs,
            update -> List.of(operation()),
            service,
            4);
    return new Fixture(objects, refs, executor, store, service, coordinator, tip, renders);
  }

  private static Operation operation() {
    return new Operation(
        "cgit-render",
        Map.of(
            "repository", List.of("project"),
            "page", List.of("summary"),
            "head", List.of("main")));
  }

  private static GitObjectId objectId(byte[] payload) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    digest.update(
        ("blob " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII));
    digest.update(payload);
    return new GitObjectId(
        GitHashAlgorithm.SHA1, HexFormat.of().formatHex(digest.digest()));
  }

  private record Fixture(
      GitObjectStore objects,
      RefGenerationStore refs,
      BoundedOriginExecutor executor,
      FileSystemArtifactStore store,
      CgitArtifactService service,
      CgitRenderOnWriteCoordinator coordinator,
      GitObjectId tip,
      AtomicInteger renders)
      implements AutoCloseable {

    RepositoryUpdateEvent update() {
      return new RepositoryUpdateEvent(
          "project",
          new GitRefName("refs/heads/main"),
          Optional.empty(),
          Optional.of(tip));
    }

    @Override
    public void close() throws IOException {
      executor.close();
      store.close();
    }
  }
}
