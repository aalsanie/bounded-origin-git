package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public record CgitMaterializationBatch(
    RefGenerationSnapshot snapshot,
    List<CgitArtifactService.Materialization> materializations,
    CompletionStage<Void> completion) {

  public CgitMaterializationBatch {
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
    materializations = List.copyOf(materializations);
    completion = Objects.requireNonNull(completion, "completion");
  }

  static CgitMaterializationBatch of(
      RefGenerationSnapshot snapshot,
      List<CgitArtifactService.Materialization> materializations) {
    CompletableFuture<?>[] futures =
        materializations.stream()
            .map(value -> value.completion().toCompletableFuture())
            .toArray(CompletableFuture[]::new);
    CompletionStage<Void> completion =
        CompletableFuture.allOf(futures).minimalCompletionStage();
    return new CgitMaterializationBatch(snapshot, materializations, completion);
  }
}
