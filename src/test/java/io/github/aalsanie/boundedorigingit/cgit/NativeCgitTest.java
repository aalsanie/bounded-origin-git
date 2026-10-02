package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitObjectType;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
final class NativeCgitTest {
  private static final GitRefName MAIN = new GitRefName("refs/heads/main");
  private static final Budget BUDGET = new Budget(1, 2, Duration.ofSeconds(10), 1_000_000);
  @TempDir Path temporaryDirectory;

  @Test
  void servesRealCgitBytesForEncodedFilenameAliasesWithoutRecomputation() throws Exception {
    GitObjectStore objects = objects();
    GitObjectId commit = commit(objects, "first payload\n", null);
    RefGenerationSnapshot snapshot = new RefGenerationSnapshot(1, Map.of(MAIN, commit));
    CgitArtifactNamespace namespace = new CgitArtifactNamespace("project", MAIN);
    ProcessCgitRenderer renderer = renderer(namespace, objects);
    AtomicInteger renders = new AtomicInteger();
    CgitSemanticClassifier classifier = new CgitSemanticClassifier(List.of("project"));
    Operation virtual =
        classify(
            classifier, "/project/plain/A%20B%2B%25%23%C3%A9.txt", "id=" + commit.hexadecimal());
    Operation legacy =
        classify(
            classifier,
            "/",
            "r=project&p=plain&path=A+B%2B%25%23%C3%A9.txt&id=" + commit.hexadecimal());
    assertEquals(virtual, legacy);

    try (CgitRenderExecutor executor = new CgitRenderExecutor(BUDGET, Duration.ofMillis(10), 16);
        FileSystemArtifactStore store =
            new FileSystemArtifactStore(
                temporaryDirectory.resolve("artifacts"), 10_000_000, 1_000_000)) {
      CgitArtifactService service =
          new CgitArtifactService(
              namespace,
              executor,
              store,
              (refs, operation) -> {
                renders.incrementAndGet();
                return renderer.render(refs, operation);
              },
              "cgit",
              1,
              "native-v1",
              BUDGET);
      assertTrue(service.lookup(snapshot, virtual).isEmpty());
      assertEquals(0, renders.get());
      service
          .materialize(snapshot, virtual, TrustLevel.TRUSTED)
          .completion()
          .toCompletableFuture()
          .get(15, TimeUnit.SECONDS);
      assertEquals("first payload\n", read(service.lookup(snapshot, legacy).orElseThrow()));
      assertTrue(service.materialize(snapshot, legacy, TrustLevel.TRUSTED).alreadyStored());
      assertEquals(1, renders.get());
    }
  }

  @Test
  void rendersCommitAndPinnedRefGenerationsUsingIngestedNativeObjects() throws Exception {
    GitObjectStore objects = objects();
    GitObjectId first = commit(objects, "before\n", null);
    GitObjectId second = commit(objects, "after\n", first);
    CgitArtifactNamespace namespace = new CgitArtifactNamespace("project", MAIN);
    ProcessCgitRenderer renderer = renderer(namespace, objects);
    CgitSemanticClassifier classifier = new CgitSemanticClassifier(List.of("project"));
    Operation plain = classify(classifier, "/project/plain/A%20B%2B%25%23%C3%A9.txt", "h=main");
    RefGenerationSnapshot oldRefs = new RefGenerationSnapshot(1, Map.of(MAIN, first));
    RefGenerationSnapshot newRefs = new RefGenerationSnapshot(2, Map.of(MAIN, second));
    assertEquals("before\n", read(renderer.render(oldRefs, namespace.pin(plain, oldRefs))));
    assertEquals("after\n", read(renderer.render(newRefs, namespace.pin(plain, newRefs))));
    Operation commitPage = classify(classifier, "/project/commit", "id=" + second.hexadecimal());
    String html = read(renderer.render(newRefs, namespace.pin(commitPage, newRefs)));
    assertTrue(html.contains("native fixture"), html);
    assertTrue(html.contains(second.hexadecimal()), html);
  }

  private GitObjectStore objects() throws Exception {
    return new GitObjectStore(
        temporaryDirectory.resolve("objects"), GitHashAlgorithm.SHA1, 1_000_000);
  }

  private ProcessCgitRenderer renderer(CgitArtifactNamespace namespace, GitObjectStore objects)
      throws Exception {
    String executable = System.getenv().getOrDefault("CGIT_EXECUTABLE", "/usr/lib/cgit/cgit.cgi");
    assertTrue(Files.isExecutable(Path.of(executable)), "install cgit or set CGIT_EXECUTABLE");
    String libraryPath = System.getenv("CGIT_LIBRARY_PATH");
    List<String> command =
        libraryPath == null
            ? List.of(executable)
            : List.of("/usr/bin/env", "LD_LIBRARY_PATH=" + libraryPath, executable);
    return new ProcessCgitRenderer(
        command,
        namespace,
        temporaryDirectory.resolve("work"),
        objects,
        1_000_000,
        8192,
        4096,
        Duration.ofSeconds(1));
  }

  private static GitObjectId commit(GitObjectStore store, String payload, GitObjectId parent)
      throws Exception {
    GitObjectId blob =
        ingest(store, GitObjectType.BLOB, "blob", payload.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream treeBytes = new ByteArrayOutputStream();
    treeBytes.writeBytes("100644 A B+%#\u00e9.txt\0".getBytes(StandardCharsets.UTF_8));
    treeBytes.writeBytes(HexFormat.of().parseHex(blob.hexadecimal()));
    GitObjectId tree = ingest(store, GitObjectType.TREE, "tree", treeBytes.toByteArray());
    String commit =
        "tree "
            + tree.hexadecimal()
            + "\n"
            + (parent == null ? "" : "parent " + parent.hexadecimal() + "\n")
            + "author Fixture <fixture@example.com> 1 +0000\n"
            + "committer Fixture <fixture@example.com> 1 +0000\n\nnative fixture\n";
    return ingest(store, GitObjectType.COMMIT, "commit", commit.getBytes(StandardCharsets.UTF_8));
  }

  private static GitObjectId ingest(
      GitObjectStore store, GitObjectType type, String name, byte[] bytes) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-1");
    hash.update((name + " " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
    GitObjectId id =
        new GitObjectId(GitHashAlgorithm.SHA1, HexFormat.of().formatHex(hash.digest(bytes)));
    store.ingest(id, type, bytes.length, new ByteArrayInputStream(bytes));
    return id;
  }

  private static Operation classify(CgitSemanticClassifier classifier, String path, String query) {
    return classifier
        .classify(
            new RequestDescriptor(
                "http.request",
                Map.of("method", List.of("GET"), "path", List.of(path), "query", List.of(query)),
                TrustLevel.UNTRUSTED))
        .orElseThrow();
  }

  private static String read(Artifact artifact) throws Exception {
    try (var body = artifact.body();
        var input = body.openStream()) {
      String result = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(200, artifact.statusCode(), result);
      return result;
    }
  }
}
