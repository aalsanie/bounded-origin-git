package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigingit.git.RefGenerationStore;
import java.util.Objects;
import java.util.Optional;

public final class CgitRefResolver {
  private final RefGenerationStore refs;
  private final GitRefName defaultRef;

  public CgitRefResolver(RefGenerationStore refs, GitRefName defaultRef) {
    this.refs = Objects.requireNonNull(refs, "refs");
    this.defaultRef = Objects.requireNonNull(defaultRef, "defaultRef");
  }

  public Optional<GitObjectId> resolve(String name) {
    RefGenerationSnapshot snapshot = refs.current();
    if (name == null || name.isBlank()) {
      return snapshot.resolve(defaultRef);
    }
    if (name.startsWith("refs/")) {
      try {
        return snapshot.resolve(new GitRefName(name));
      } catch (IllegalArgumentException invalidRef) {
        return Optional.empty();
      }
    }
    if (!isShortRefName(name)) {
      return Optional.empty();
    }

    GitRefName branch;
    GitRefName tag;
    try {
      branch = new GitRefName("refs/heads/" + name);
      tag = new GitRefName("refs/tags/" + name);
    } catch (IllegalArgumentException invalidRef) {
      return Optional.empty();
    }

    Optional<GitObjectId> branchTarget = snapshot.resolve(branch);
    Optional<GitObjectId> tagTarget = snapshot.resolve(tag);
    if (branchTarget.isPresent() == tagTarget.isPresent()) {
      return Optional.empty();
    }
    return branchTarget.isPresent() ? branchTarget : tagTarget;
  }

  private static boolean isShortRefName(String name) {
    return !name.startsWith("/")
        && !name.endsWith("/")
        && !name.contains("//")
        && !name.contains("..")
        && !name.contains("@{")
        && name.indexOf('~') < 0
        && name.indexOf('^') < 0
        && name.indexOf(':') < 0
        && name.indexOf('?') < 0
        && name.indexOf('*') < 0
        && name.indexOf('[') < 0
        && name.indexOf('\\') < 0;
  }
}
