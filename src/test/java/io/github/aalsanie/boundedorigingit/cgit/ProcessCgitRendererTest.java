package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitObjectType;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessCgitRendererTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executesCgiWithCanonicalQueryAndSafeConfiguration() throws Exception {
    Fixture fixture = fixture(4096);
    Operation pinned =
        fixture.namespace().pin(
            new Operation(
                "cgit-render",
                Map.of(
                    "repository", List.of("project"),
                    "page", List.of("tree"),
                    "path", List.of("src/A B.java"),
                    "head", List.of("main"),
                    "oid", List.of("abc"))),
            fixture.snapshot());

    Artifact artifact = fixture.renderer().render(fixture.snapshot(), pinned);
    try {
      assertEquals(200, artifact.statusCode());
      assertEquals(Map.of("Content-Type", "text/plain"), artifact.metadata());
      try (var input = artifact.body().openStream()) {
        String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(
            body.contains(
                "r=project&p=tree&path=src%2FA+B.java&h=main&id=abc"));
        assertTrue(body.contains("config-safe=true"));
      }
    } finally {
      artifact.body().close();
    }
  }

  @Test
  void preservesCgiStatusButDropsUnsafeMetadata() throws Exception {
    Fixture fixture = fixture(4096);
    Operation pinned =
        fixture.namespace().pin(
            new Operation(
                "cgit-render",
                Map.of(
                    "repository", List.of("project"),
                    "page", List.of("tag"),
                    "oid", List.of("abc"))),
            fixture.snapshot());

    Artifact artifact = fixture.renderer().render(fixture.snapshot(), pinned);
    try {
      assertEquals(404, artifact.statusCode());
      assertEquals(Map.of("Content-Type", "text/plain"), artifact.metadata());
    } finally {
      artifact.body().close();
    }
  }

  @Test
  void rejectsBodyBeyondConfiguredBound() throws Exception {
    Fixture fixture = fixture(128);
    Operation pinned =
        fixture.namespace().pin(
            new Operation(
                "cgit-render",
                Map.of(
                    "repository", List.of("project"),
                    "page", List.of("log"),
                    "grep", List.of("grep"),
                    "search", List.of("large"))),
            fixture.snapshot());

    assertThrows(
        MaterializationException.class,
        () -> fixture.renderer().render(fixture.snapshot(), pinned));
  }

  @Test
  void doesNotReturnFromInterruptionUntilCgitProcessHasExited() throws Exception {
    interruptAndVerify("wait:");
  }

  @Test
  void releasesPartialResponseFileAfterInterruption() throws Exception {
    interruptAndVerify("partial-wait:");
  }

  private void interruptAndVerify(String prefix) throws Exception {
    Fixture fixture = fixture(4096);
    Path pidFile = temporaryDirectory.resolve("renderer.pid").toAbsolutePath();
    Operation pinned =
        fixture
            .namespace()
            .pin(
                new Operation(
                    "cgit-render",
                    Map.of(
                        "repository", List.of("project"),
                        "page", List.of("log"),
                        "grep", List.of("grep"),
                        "search", List.of(prefix + pidFile))),
                fixture.snapshot());

    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    fixture.renderer().render(fixture.snapshot(), pinned);
                  } catch (Throwable throwable) {
                    failure.set(throwable);
                  }
                });

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!java.nio.file.Files.exists(pidFile) && System.nanoTime() - deadline < 0) {
      Thread.sleep(10);
    }
    assertTrue(java.nio.file.Files.exists(pidFile));
    long pid =
        Long.parseLong(
            java.nio.file.Files.readString(pidFile, StandardCharsets.US_ASCII).trim());

    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(5));

    assertTrue(!worker.isAlive());
    assertTrue(failure.get() instanceof MaterializationException);
    assertTrue(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
    try (var files = java.nio.file.Files.walk(temporaryDirectory)) {
      assertEquals(
          0, files.filter(path -> path.getFileName().toString().endsWith(".body")).count());
    }
  }

  @Test
  void rejectsPinnedOperationFromAnotherSnapshot() throws Exception {
    Fixture fixture = fixture(4096);
    Operation pinned =
        fixture.namespace().pin(
            new Operation(
                "cgit-render",
                Map.of(
                    "repository", List.of("project"),
                    "page", List.of("summary"))),
            fixture.snapshot());
    RefGenerationSnapshot other =
        new RefGenerationSnapshot(
            fixture.snapshot().generation() + 1,
            fixture.snapshot().refs());

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.renderer().render(other, pinned));
  }

  private Fixture fixture(long maxArtifactBytes) throws Exception {
    GitObjectStore objects =
        new GitObjectStore(
            temporaryDirectory.resolve("objects-" + System.nanoTime()),
            GitHashAlgorithm.SHA1,
            1024);
    byte[] payload = "tip\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId tip = objectId(payload);
    objects.ingest(
        tip, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));
    RefGenerationSnapshot snapshot =
        new RefGenerationSnapshot(
            1, Map.of(new GitRefName("refs/heads/main"), tip));
    CgitArtifactNamespace namespace =
        new CgitArtifactNamespace("project", new GitRefName("refs/heads/main"));
    ProcessCgitRenderer renderer =
        new ProcessCgitRenderer(
            fixtureCommand(),
            namespace,
            temporaryDirectory.resolve("work-" + System.nanoTime()),
            objects,
            maxArtifactBytes,
            8192,
            1024,
            Duration.ofSeconds(1));
    return new Fixture(namespace, renderer, snapshot);
  }

  private static List<String> fixtureCommand() throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows")
                    ? "java.exe"
                    : "java")
            .toString();
    URI location =
        CgitProcessFixture.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI();
    return List.of(
        executable,
        "-cp",
        Path.of(location).toString(),
        CgitProcessFixture.class.getName());
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
      CgitArtifactNamespace namespace,
      ProcessCgitRenderer renderer,
      RefGenerationSnapshot snapshot) {}
}
