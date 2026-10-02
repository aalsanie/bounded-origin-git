package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class GitRepositoryView implements AutoCloseable {
  private final Path path;
  private final AtomicBoolean closed = new AtomicBoolean();

  GitRepositoryView(Path path) {
    this.path = Objects.requireNonNull(path, "path");
  }

  public Path path() {
    if (closed.get()) {
      throw new IllegalStateException("repository view is closed");
    }
    return path;
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(path)) {
      for (Path value : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(value);
      }
    }
  }
}
