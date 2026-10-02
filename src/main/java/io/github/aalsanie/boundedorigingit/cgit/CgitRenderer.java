package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Operation;

@FunctionalInterface
public interface CgitRenderer {
  Artifact render(RefGenerationSnapshot snapshot, Operation operation)
      throws MaterializationException;
}
