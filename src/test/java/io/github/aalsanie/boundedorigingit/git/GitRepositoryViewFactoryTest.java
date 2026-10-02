package io.github.aalsanie.boundedorigingit.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitRepositoryViewFactoryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exposesPublishedRefsAndImmutableObjectsToNativeGit() throws Exception {
    GitObjectStore objects =
        new GitObjectStore(
            temporaryDirectory.resolve("objects"), GitHashAlgorithm.SHA1, 1024);
    byte[] payload = "view object\n".getBytes(StandardCharsets.UTF_8);
    GitObjectId object = objectId(payload);
    objects.ingest(
        object, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));

    RefGenerationSnapshot snapshot =
        new RefGenerationSnapshot(
            7, Map.of(new GitRefName("refs/heads/main"), object));
    GitRepositoryViewFactory factory =
        new GitRepositoryViewFactory(
            temporaryDirectory.resolve("views"),
            objects,
            new GitRefName("refs/heads/main"));

    Path path;
    try (GitRepositoryView view = factory.create(snapshot)) {
      path = view.path();
      assertEquals(
          object.hexadecimal(),
          runGit("--git-dir=" + path, "rev-parse", "refs/heads/main").trim());
      assertEquals(
          "view object\n",
          runGit("--git-dir=" + path, "cat-file", "-p", object.hexadecimal()));
      assertEquals(
          "ref: refs/heads/main\n",
          Files.readString(path.resolve("HEAD"), StandardCharsets.US_ASCII));
    }

    assertFalse(Files.exists(path));
  }

  private static GitObjectId objectId(byte[] payload) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    digest.update(
        ("blob " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII));
    digest.update(payload);
    return new GitObjectId(
        GitHashAlgorithm.SHA1, HexFormat.of().formatHex(digest.digest()));
  }

  private static String runGit(String... arguments) throws Exception {
    String[] command = new String[arguments.length + 1];
    command[0] = "git";
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] output = process.getInputStream().readAllBytes();
    int exit = process.waitFor();
    if (exit != 0) {
      throw new AssertionError(
          "git exited with " + exit + ": " + new String(output, StandardCharsets.UTF_8));
    }
    return new String(output, StandardCharsets.UTF_8);
  }
}
