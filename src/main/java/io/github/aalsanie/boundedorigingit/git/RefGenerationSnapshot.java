package io.github.aalsanie.boundedorigingit.git;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RefGenerationSnapshot(long generation, Map<GitRefName, GitObjectId> refs) {
  public RefGenerationSnapshot {
    if (generation < 0) {
      throw new IllegalArgumentException("generation must be non-negative");
    }
    Objects.requireNonNull(refs, "refs");
    LinkedHashMap<GitRefName, GitObjectId> sorted = new LinkedHashMap<>();
    refs.entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().value()))
        .forEach(entry -> sorted.put(
            Objects.requireNonNull(entry.getKey(), "ref"),
            Objects.requireNonNull(entry.getValue(), "objectId")));
    refs = Collections.unmodifiableMap(sorted);
  }

  public Optional<GitObjectId> resolve(GitRefName ref) {
    return Optional.ofNullable(refs.get(Objects.requireNonNull(ref, "ref")));
  }
}
