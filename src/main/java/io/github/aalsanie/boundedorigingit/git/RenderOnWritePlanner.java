package io.github.aalsanie.boundedorigingit.git;

import io.github.aalsanie.boundedorigin.api.Operation;

@FunctionalInterface
public interface RenderOnWritePlanner {
  Iterable<Operation> plan(RepositoryUpdateEvent event);
}
