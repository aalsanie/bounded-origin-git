package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigingit.git.RefGenerationStore;
import io.github.aalsanie.boundedorigingit.git.RenderOnWritePlanner;
import io.github.aalsanie.boundedorigingit.git.RepositoryUpdateEvent;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CgitRenderOnWriteCoordinator {
  private final RefGenerationStore refs;
  private final RenderOnWritePlanner planner;
  private final CgitArtifactService artifacts;
  private final int maxOperationsPerPublication;

  public CgitRenderOnWriteCoordinator(
      RefGenerationStore refs,
      RenderOnWritePlanner planner,
      CgitArtifactService artifacts,
      int maxOperationsPerPublication) {
    this.refs = Objects.requireNonNull(refs, "refs");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    if (maxOperationsPerPublication <= 0) {
      throw new IllegalArgumentException("maxOperationsPerPublication must be positive");
    }
    this.maxOperationsPerPublication = maxOperationsPerPublication;
  }

  public CgitMaterializationBatch publishAndMaterialize(
      Collection<RepositoryUpdateEvent> updates, TrustLevel trustLevel)
      throws IOException {
    Objects.requireNonNull(updates, "updates");
    Objects.requireNonNull(trustLevel, "trustLevel");
    if (trustLevel != TrustLevel.TRUSTED) {
      throw new SecurityException("render-on-write publication requires trusted ingress");
    }

    List<RepositoryUpdateEvent> batch = List.copyOf(updates);
    if (batch.isEmpty()) {
      throw new IllegalArgumentException("at least one repository update is required");
    }

    Set<Operation> planned = new LinkedHashSet<>();
    int count = 0;
    for (RepositoryUpdateEvent update : batch) {
      Iterable<Operation> operations =
          Objects.requireNonNull(planner.plan(update), "planner result");
      for (Operation operation : operations) {
        count++;
        if (count > maxOperationsPerPublication) {
          throw new IllegalArgumentException("publication exceeds render operation limit");
        }
        planned.add(Objects.requireNonNull(operation, "planned operation"));
      }
    }

    RefGenerationSnapshot snapshot = refs.publish(batch, trustLevel);
    List<CgitArtifactService.Materialization> materializations =
        new ArrayList<>(planned.size());
    for (Operation operation : planned) {
      materializations.add(artifacts.materialize(snapshot, operation));
    }
    return CgitMaterializationBatch.of(snapshot, materializations);
  }
}
