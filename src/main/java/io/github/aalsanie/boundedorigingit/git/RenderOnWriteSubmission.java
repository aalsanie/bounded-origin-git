package io.github.aalsanie.boundedorigingit.git;

import io.github.aalsanie.boundedorigin.core.OriginExecution;
import java.util.List;
import java.util.Objects;

public final class RenderOnWriteSubmission implements AutoCloseable {
  private final List<OriginExecution> executions;

  RenderOnWriteSubmission(List<OriginExecution> executions) {
    Objects.requireNonNull(executions, "executions");
    this.executions = List.copyOf(executions);
  }

  public List<OriginExecution> executions() {
    return executions;
  }

  @Override
  public void close() {
    executions.forEach(OriginExecution::close);
  }
}
