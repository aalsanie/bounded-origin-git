package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Materializer;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import io.github.aalsanie.boundedorigin.core.OriginExecutorMetrics;
import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import java.time.Duration;
import java.util.Objects;

public final class CgitRenderExecutor implements AutoCloseable {
  private final Budget globalBudget;
  private final BoundedOriginExecutor executor;

  public CgitRenderExecutor(Budget globalBudget, Duration failureCooldown, int maxCooldownEntries) {
    this.globalBudget = Objects.requireNonNull(globalBudget, "globalBudget");
    this.executor = new BoundedOriginExecutor(globalBudget, failureCooldown, maxCooldownEntries);
  }

  long maxResultBytes(Budget policyBudget) {
    return Math.min(globalBudget.maxResultBytes(), policyBudget.maxResultBytes());
  }

  OriginExecution execute(OriginDecision.Selected decision, Materializer materializer) {
    return executor.execute(decision, materializer);
  }

  public OriginExecutorStats stats() {
    return OriginExecutorMetrics.snapshot(executor);
  }

  @Override
  public void close() {
    executor.close();
  }
}
