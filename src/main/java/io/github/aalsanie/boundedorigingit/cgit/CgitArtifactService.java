package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class CgitArtifactService {
  private final CgitArtifactNamespace namespace;
  private final BoundedOriginExecutor executor;
  private final ArtifactStore artifactStore;
  private final CgitRenderer renderer;
  private final OriginPolicy policy;

  public CgitArtifactService(
      CgitArtifactNamespace namespace,
      BoundedOriginExecutor executor,
      ArtifactStore artifactStore,
      CgitRenderer renderer,
      String policyId,
      long policyVersion,
      String materializerVersion,
      Budget budget) {
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.policy =
        OriginPolicy.materialize(
            policyId,
            policyVersion,
            0,
            materializerVersion,
            namespace.canonicalizer(),
            Objects.requireNonNull(budget, "budget"));
  }

  public OriginPolicy policy() {
    return policy;
  }

  public Optional<Artifact> lookup(RefGenerationSnapshot snapshot, Operation operation)
      throws IOException {
    Operation pinned = namespace.pin(operation, snapshot);
    return artifactStore.get(key(pinned));
  }

  public Materialization materialize(
      RefGenerationSnapshot snapshot, Operation operation, TrustLevel trustLevel)
      throws IOException {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(trustLevel, "trustLevel");
    if (trustLevel != TrustLevel.TRUSTED) {
      throw new SecurityException("cgit materialization requires trusted ingress");
    }
    Operation pinned = namespace.pin(operation, snapshot);
    OperationKey key = key(pinned);

    Optional<Artifact> stored = artifactStore.get(key);
    if (stored.isPresent()) {
      stored.orElseThrow().body().close();
      return new Materialization(
          CompletableFuture.completedFuture(null), true, false);
    }

    OriginDecision.Selected selected = new OriginDecision.Selected(policy, pinned, key);
    OriginExecution execution =
        executor.execute(
            selected,
            materialized -> persist(snapshot, materialized, key));

    CompletableFuture<Void> completion = new CompletableFuture<>();
    execution
        .result()
        .whenComplete(
            (artifact, failure) -> {
              execution.close();
              if (failure == null) {
                completion.complete(null);
              } else {
                completion.completeExceptionally(unwrap(failure));
              }
            });
    return new Materialization(completion.minimalCompletionStage(), false, execution.joined());
  }

  private Artifact persist(
      RefGenerationSnapshot snapshot, Operation operation, OperationKey key)
      throws MaterializationException {
    Artifact generated = renderer.render(snapshot, operation);
    MaterializationException failure = null;
    try {
      long policyLimit = policy.budget().orElseThrow().maxResultBytes();
      if (generated.contentLength() > policyLimit) {
        throw new MaterializationException("cgit artifact exceeds policy result limit");
      }
      try {
        artifactStore.put(key, generated);
      } catch (IOException exception) {
        throw new MaterializationException("failed to persist cgit artifact", exception);
      }
    } catch (MaterializationException exception) {
      failure = exception;
      throw exception;
    } finally {
      try {
        generated.body().close();
      } catch (IOException exception) {
        if (failure != null) {
          failure.addSuppressed(exception);
        } else {
          throw new MaterializationException(
              "failed to release generated cgit artifact", exception);
        }
      }
    }

    try {
      return artifactStore
          .get(key)
          .orElseThrow(
              () -> new MaterializationException("persisted cgit artifact is not readable"));
    } catch (IOException exception) {
      throw new MaterializationException("failed to read persisted cgit artifact", exception);
    }
  }

  private OperationKey key(Operation pinned) {
    String identity = policy.canonicalizer().orElseThrow().canonicalize(pinned);
    return new OperationKey(
        policy.id(),
        policy.version(),
        identity,
        policy.materializerVersion().orElseThrow());
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return failure;
  }

  public record Materialization(
      CompletionStage<Void> completion, boolean alreadyStored, boolean joined) {
    public Materialization {
      completion = Objects.requireNonNull(completion, "completion");
      if (alreadyStored && joined) {
        throw new IllegalArgumentException("stored materialization cannot be joined");
      }
    }
  }
}
