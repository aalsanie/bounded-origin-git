package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

public final class GitRepositoryViewFactory {
  private final Path root;
  private final GitObjectStore objectStore;
  private final GitRefName defaultRef;

  public GitRepositoryViewFactory(
      Path root, GitObjectStore objectStore, GitRefName defaultRef) throws IOException {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    this.defaultRef = Objects.requireNonNull(defaultRef, "defaultRef");
    Files.createDirectories(this.root);
    if (Files.isSymbolicLink(this.root)
        || !Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("repository view root must be a local directory");
    }
  }

  public GitRepositoryView create(RefGenerationSnapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot, "snapshot");
    for (GitObjectId objectId : snapshot.refs().values()) {
      if (!objectStore.contains(objectId)) {
        throw new IOException("ref generation references an unavailable Git object");
      }
    }

    Path view = Files.createTempDirectory(root, "view-");
    boolean completed = false;
    try {
      Files.writeString(
          view.resolve("HEAD"),
          "ref: " + defaultRef.value() + "\n",
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW);
      Files.writeString(
          view.resolve("config"),
          config(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);

      Path alternates = view.resolve("objects").resolve("info");
      Files.createDirectories(alternates);
      Files.writeString(
          alternates.resolve("alternates"),
          objectStore.objectDirectory().toString().replace('\\\\', '/') + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);

      for (Map.Entry<GitRefName, GitObjectId> entry : snapshot.refs().entrySet()) {
        Path target = view.resolve(entry.getKey().value()).normalize();
        if (!target.startsWith(view)) {
          throw new IOException("ref path escapes repository view");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(
            target,
            entry.getValue().hexadecimal() + "\n",
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW);
      }

      completed = true;
      return new GitRepositoryView(view);
    } finally {
      if (!completed) {
        new GitRepositoryView(view).close();
      }
    }
  }

  private String config() {
    if (objectStore.hashAlgorithm() == GitHashAlgorithm.SHA256) {
      return "[core]\n"
          + "\trepositoryformatversion = 1\n"
          + "\tbare = true\n"
          + "[extensions]\n"
          + "\tobjectFormat = sha256\n";
    }
    return "[core]\n"
        + "\trepositoryformatversion = 0\n"
        + "\tbare = true\n";
  }
}
