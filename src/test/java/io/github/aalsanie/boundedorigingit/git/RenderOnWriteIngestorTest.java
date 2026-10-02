package io.github.aalsanie.boundedorigingit.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Materializer;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import io.github.aalsanie.boundedorigin.core.OriginExecutorMetrics;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RenderOnWriteIngestorTest {
  private static final Budget BUDGET = new Budget(1, 4, Duration.ofSeconds(5), 1024);
  private static final Canonicalizer CANONICALIZER =
      Canonicalizers.byDimensions("repository", "page", "oid");

  @TempDir Path temporaryDirectory;
  private final List<BoundedOriginExecutor> executors = new ArrayList<>();

  @AfterEach
  void closeExecutors() {
    executors.forEach(BoundedOriginExecutor::close);
  }

  @Test
  void rejectsUntrustedEventsBeforePlanning() throws Exception {
    Fixture fixture = fixture(event -> List.of(operation("summary", event)));
    AtomicInteger materializations = new AtomicInteger();
    RenderOnWriteIngestor ingestor =
        fixture.ingestor(
            event -> {
              throw new AssertionError("planner must not run for untrusted events");
            },
            operation -> {
              materializations.incrementAndGet();
              return emptyArtifact();
            },
            4);

    assertThrows(
        SecurityException.class,
        () -> ingestor.ingest(fixture.update(), TrustLevel.UNTRUSTED));
    assertEquals(0, materializations.get());
  }

  @Test
  void requiresNewTipToExistInImmutableObjectStore() throws Exception {
    Fixture fixture = fixture(event -> List.of(operation("summary", event)));
    byte[] unknownPayload = "unknown\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId unknown = objectId(GitObjectType.BLOB, unknownPayload);
    RepositoryUpdateEvent event =
        new RepositoryUpdateEvent(
            "project",
            new GitRefName("refs/heads/main"),
            Optional.empty(),
            Optional.of(unknown));

    RenderOnWriteIngestor ingestor =
        fixture.ingestor(
            update -> {
              throw new AssertionError("planner must not run before object validation");
            },
            operation -> emptyArtifact(),
            4);

    assertThrows(
        IllegalStateException.class,
        () -> ingestor.ingest(event, TrustLevel.TRUSTED));

    RepositoryUpdateEvent deletion =
        new RepositoryUpdateEvent(
            "project",
            new GitRefName("refs/heads/main"),
            Optional.of(unknown),
            Optional.empty());
    assertThrows(
        IllegalStateException.class,
        () -> ingestor.ingest(deletion, TrustLevel.TRUSTED));
  }

  @Test
  void drivesMaterializationThroughBoundedExecutor() throws Exception {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger materializations = new AtomicInteger();

    Fixture fixture =
        fixture(
            event ->
                List.of(
                    operation("summary", event),
                    operation("log", event)));
    Materializer materializer =
        operation -> {
          int invocation = materializations.incrementAndGet();
          if (invocation == 1) {
            firstStarted.countDown();
            await(releaseFirst);
          }
          return emptyArtifact();
        };
    RenderOnWriteIngestor ingestor = fixture.ingestor(fixture.planner(), materializer, 4);

    try (RenderOnWriteSubmission submission =
        ingestor.ingest(fixture.update(), TrustLevel.TRUSTED)) {
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      assertEquals(1, OriginExecutorMetrics.snapshot(fixture.executor()).activeJobs());
      assertEquals(1, OriginExecutorMetrics.snapshot(fixture.executor()).queuedJobs());

      releaseFirst.countDown();
      await(submission.executions());
    }

    assertEquals(2, materializations.get());
  }

  @Test
  void singleFlightsEquivalentRenderWorkAcrossUpdateDeliveries() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger materializations = new AtomicInteger();
    Fixture fixture = fixture(event -> List.of(operation("summary", event)));

    RenderOnWriteIngestor ingestor =
        fixture.ingestor(
            fixture.planner(),
            operation -> {
              materializations.incrementAndGet();
              started.countDown();
              await(release);
              return emptyArtifact();
            },
            4);

    try (RenderOnWriteSubmission first =
            ingestor.ingest(fixture.update(), TrustLevel.TRUSTED)) {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      try (RenderOnWriteSubmission second =
          ingestor.ingest(fixture.update(), TrustLevel.TRUSTED)) {
        assertFalse(first.executions().getFirst().joined());
        assertTrue(second.executions().getFirst().joined());
        release.countDown();
        await(first.executions());
        await(second.executions());
      }
    }

    assertEquals(1, materializations.get());
  }

  @Test
  void collapsesEquivalentOperationsWithinOneEvent() throws Exception {
    Fixture fixture =
        fixture(
            event ->
                List.of(
                    operation("summary", event),
                    operationWithNoise("summary", event)));
    AtomicInteger materializations = new AtomicInteger();

    RenderOnWriteIngestor ingestor =
        fixture.ingestor(
            fixture.planner(),
            operation -> {
              materializations.incrementAndGet();
              return emptyArtifact();
            },
            4);

    try (RenderOnWriteSubmission submission =
        ingestor.ingest(fixture.update(), TrustLevel.TRUSTED)) {
      assertEquals(1, submission.executions().size());
      await(submission.executions());
    }

    assertEquals(1, materializations.get());
  }

  @Test
  void boundsPlannedWorkBeforeSubmittingAnything() throws Exception {
    Fixture fixture =
        fixture(
            event ->
                List.of(
                    operation("summary", event),
                    operation("log", event),
                    operation("tree", event)));
    AtomicInteger materializations = new AtomicInteger();

    RenderOnWriteIngestor ingestor =
        fixture.ingestor(
            fixture.planner(),
            operation -> {
              materializations.incrementAndGet();
              return emptyArtifact();
            },
            2);

    assertThrows(
        IllegalArgumentException.class,
        () -> ingestor.ingest(fixture.update(), TrustLevel.TRUSTED));
    assertEquals(0, materializations.get());
    assertEquals(0, OriginExecutorMetrics.snapshot(fixture.executor()).inFlightJobs());
  }

  @Test
  void acceptsDeletionEventsWithoutRequiringAnAfterObject() throws Exception {
    Fixture fixture = fixture(event -> List.of(operation("summary", event)));
    RepositoryUpdateEvent deletion =
        new RepositoryUpdateEvent(
            "project",
            new GitRefName("refs/heads/main"),
            Optional.of(fixture.tip()),
            Optional.empty());

    try (RenderOnWriteSubmission submission =
        fixture.ingestor(fixture.planner(), operation -> emptyArtifact(), 4)
            .ingest(deletion, TrustLevel.TRUSTED)) {
      await(submission.executions());
      assertEquals(1, submission.executions().size());
    }
  }

  @Test
  void rejectsWrongRepositoryAndNonMaterializePolicy() throws Exception {
    Fixture fixture = fixture(event -> List.of(operation("summary", event)));
    RepositoryUpdateEvent otherRepository =
        new RepositoryUpdateEvent(
            "other",
            new GitRefName("refs/heads/main"),
            Optional.empty(),
            Optional.of(fixture.tip()));

    RenderOnWriteIngestor ingestor =
        fixture.ingestor(fixture.planner(), operation -> emptyArtifact(), 4);
    assertThrows(
        IllegalArgumentException.class,
        () -> ingestor.ingest(otherRepository, TrustLevel.TRUSTED));

    OriginPolicy compute =
        OriginPolicy.boundedCompute(
            "render",
            1,
            0,
            "renderer-v1",
            CANONICALIZER,
            BUDGET);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RenderOnWriteIngestor(
                "project",
                fixture.objectStore(),
                fixture.executor(),
                compute,
                fixture.planner(),
                operation -> emptyArtifact(),
                4));
  }

  @Test
  void validatesRefAndUpdateShape() {
    assertThrows(IllegalArgumentException.class, () -> new GitRefName("main"));
    assertThrows(IllegalArgumentException.class, () -> new GitRefName("refs/heads/../main"));
    assertThrows(IllegalArgumentException.class, () -> new GitRefName("refs/heads/main.lock"));
    assertThrows(IllegalArgumentException.class, () -> new GitRefName("refs/heads/feature~1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryUpdateEvent(
                "project",
                new GitRefName("refs/heads/main"),
                Optional.empty(),
                Optional.empty()));
  }

  private Fixture fixture(RenderOnWritePlanner planner) throws Exception {
    GitObjectStore objectStore =
        new GitObjectStore(
            temporaryDirectory.resolve("objects-" + System.nanoTime()),
            GitHashAlgorithm.SHA1,
            1024);
    byte[] payload = "tip\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId tip = objectId(GitObjectType.BLOB, payload);
    objectStore.ingest(
        tip, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));

    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(BUDGET, Duration.ofMillis(10), 16);
    executors.add(executor);
    OriginPolicy policy =
        OriginPolicy.materialize(
            "render",
            1,
            0,
            "renderer-v1",
            CANONICALIZER,
            BUDGET);
    return new Fixture(objectStore, executor, policy, planner, tip);
  }

  private static Operation operation(String page, RepositoryUpdateEvent event) {
    return new Operation(
        "cgit-render",
        Map.of(
            "repository", List.of(event.repository()),
            "page", List.of(page),
            "oid", List.of(event.after().orElse(event.before().orElseThrow()).hexadecimal())));
  }

  private static Operation operationWithNoise(String page, RepositoryUpdateEvent event) {
    return new Operation(
        "cgit-render",
        Map.of(
            "repository", List.of(event.repository()),
            "page", List.of(page),
            "oid", List.of(event.after().orElse(event.before().orElseThrow()).hexadecimal()),
            "noise", List.of("ignored")));
  }

  private static GitObjectId objectId(GitObjectType type, byte[] payload) {
    MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
    digest.update(
        (type.wireName() + " " + payload.length + "\0")
            .getBytes(StandardCharsets.US_ASCII));
    digest.update(payload);
    return new GitObjectId(
        GitHashAlgorithm.SHA1, HexFormat.of().formatHex(digest.digest()));
  }

  private static Artifact emptyArtifact() {
    return new Artifact(0, Map.of(), () -> InputStream.nullInputStream());
  }

  private static void await(List<OriginExecution> executions) throws Exception {
    for (OriginExecution execution : executions) {
      execution.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for test latch");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", exception);
    }
  }

  private record Fixture(
      GitObjectStore objectStore,
      BoundedOriginExecutor executor,
      OriginPolicy policy,
      RenderOnWritePlanner planner,
      GitObjectId tip) {

    RepositoryUpdateEvent update() {
      return new RepositoryUpdateEvent(
          "project",
          new GitRefName("refs/heads/main"),
          Optional.empty(),
          Optional.of(tip));
    }

    RenderOnWriteIngestor ingestor(
        RenderOnWritePlanner selectedPlanner,
        Materializer materializer,
        int maxOperationsPerEvent) {
      return new RenderOnWriteIngestor(
          "project",
          objectStore,
          executor,
          policy,
          selectedPlanner,
          materializer,
          maxOperationsPerEvent);
    }
  }
}
