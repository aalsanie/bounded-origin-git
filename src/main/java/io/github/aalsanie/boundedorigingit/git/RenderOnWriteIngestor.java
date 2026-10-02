package io.github.aalsanie.boundedorigingit.git;

import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.Materializer;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RenderOnWriteIngestor {
  private final String repository;
  private final GitObjectStore objectStore;
  private final BoundedOriginExecutor executor;
  private final OriginPolicy policy;
  private final RenderOnWritePlanner planner;
  private final Materializer materializer;
  private final int maxOperationsPerEvent;

  public RenderOnWriteIngestor(
      String repository,
      GitObjectStore objectStore,
      BoundedOriginExecutor executor,
      OriginPolicy policy,
      RenderOnWritePlanner planner,
      Materializer materializer,
      int maxOperationsPerEvent) {
    this.repository = RepositoryUpdateEvent.validateRepository(repository);
    this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.materializer = Objects.requireNonNull(materializer, "materializer");
    if (policy.strategy() != ExecutionStrategy.MATERIALIZE) {
      throw new IllegalArgumentException("render-on-write policy must use MATERIALIZE");
    }
    if (maxOperationsPerEvent <= 0) {
      throw new IllegalArgumentException("maxOperationsPerEvent must be positive");
    }
    this.maxOperationsPerEvent = maxOperationsPerEvent;
  }

  public RenderOnWriteSubmission ingest(RepositoryUpdateEvent event, TrustLevel trustLevel) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(trustLevel, "trustLevel");
    if (trustLevel != TrustLevel.TRUSTED) {
      throw new SecurityException("repository update events require trusted ingress");
    }
    if (!repository.equals(event.repository())) {
      throw new IllegalArgumentException("repository update targets a different repository");
    }
    event.before().ifPresent(this::requireIngestedObject);
    event.after().ifPresent(this::requireIngestedObject);

    List<OriginDecision.Selected> decisions = decisions(event);
    List<OriginExecution> executions = new ArrayList<>(decisions.size());
    try {
      for (OriginDecision.Selected decision : decisions) {
        executions.add(executor.execute(decision, materializer));
      }
      return new RenderOnWriteSubmission(executions);
    } catch (RuntimeException | Error failure) {
      executions.forEach(OriginExecution::close);
      throw failure;
    }
  }

  private List<OriginDecision.Selected> decisions(RepositoryUpdateEvent event) {
    Iterable<Operation> planned = Objects.requireNonNull(planner.plan(event), "planner result");
    Map<OperationKey, OriginDecision.Selected> unique = new LinkedHashMap<>();
    int count = 0;

    for (Operation operation : planned) {
      count++;
      if (count > maxOperationsPerEvent) {
        throw new IllegalArgumentException("repository update exceeds render operation limit");
      }
      Objects.requireNonNull(operation, "planned operation");

      String semanticIdentity =
          policy.canonicalizer().orElseThrow().canonicalize(operation);
      OperationKey key =
          new OperationKey(
              policy.id(),
              policy.version(),
              semanticIdentity,
              policy.materializerVersion().orElseThrow());
      unique.putIfAbsent(key, new OriginDecision.Selected(policy, operation, key));
    }
    return List.copyOf(unique.values());
  }

  private void requireIngestedObject(GitObjectId objectId) {
    if (!objectStore.contains(objectId)) {
      throw new IllegalStateException("updated object has not been ingested");
    }
  }
}
