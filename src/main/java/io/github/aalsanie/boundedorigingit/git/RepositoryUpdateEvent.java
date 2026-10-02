package io.github.aalsanie.boundedorigingit.git;

import java.util.Objects;
import java.util.Optional;

public record RepositoryUpdateEvent(
    String repository,
    GitRefName ref,
    Optional<GitObjectId> before,
    Optional<GitObjectId> after) {

  public RepositoryUpdateEvent {
    repository = validateRepository(repository);
    Objects.requireNonNull(ref, "ref");
    before = Objects.requireNonNull(before, "before");
    after = Objects.requireNonNull(after, "after");

    if (before.isEmpty() && after.isEmpty()) {
      throw new IllegalArgumentException("repository update must contain a before or after object");
    }
    if (before.isPresent() && after.isPresent()) {
      GitObjectId previous = before.orElseThrow();
      GitObjectId current = after.orElseThrow();
      if (previous.algorithm() != current.algorithm()) {
        throw new IllegalArgumentException("repository update object ids must use one hash algorithm");
      }
      if (previous.equals(current)) {
        throw new IllegalArgumentException("repository update must change the ref target");
      }
    }
  }

  static String validateRepository(String repository) {
    Objects.requireNonNull(repository, "repository");
    if (repository.isBlank()
        || repository.length() > 2048
        || repository.startsWith("/")
        || repository.endsWith("/")
        || repository.indexOf('?') >= 0
        || repository.indexOf('&') >= 0
        || repository.indexOf('=') >= 0) {
      throw new IllegalArgumentException("invalid repository");
    }
    for (int index = 0; index < repository.length(); index++) {
      char character = repository.charAt(index);
      if (character < 0x21 || character == 0x7f) {
        throw new IllegalArgumentException("invalid repository");
      }
    }
    return repository;
  }
}
