package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CgitComparisonClientCompute {
  private static final String COMPUTATION_TYPE = "bounded-origin-git.compare";
  private static final String COMPUTATION_VERSION = "1";
  private static final String POLICY_MATERIALIZER_VERSION = "client-comparison-v1";
  private static final ClientComputation COMPUTATION =
      new ClientComputation(
          COMPUTATION_TYPE,
          COMPUTATION_VERSION,
          Map.of("request", "cgit-two-sided-diff"));

  private final CgitSemanticClassifier classifier;
  private final OriginPolicy policy;
  private final PolicyRule rule;

  public CgitComparisonClientCompute(
      CgitSemanticClassifier classifier,
      String policyId,
      long policyVersion,
      int precedence) {
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    this.policy =
        OriginPolicy.clientCompute(
            policyId,
            policyVersion,
            precedence,
            POLICY_MATERIALIZER_VERSION,
            classifier.canonicalizer(),
            COMPUTATION);
    this.rule = new PolicyRule(policy, this::classify);
  }

  public OriginPolicy policy() {
    return policy;
  }

  public PolicyRule rule() {
    return rule;
  }

  private Optional<Operation> classify(RequestDescriptor request) {
    Optional<Operation> classified = classifier.classify(request);
    if (classified.isEmpty()) {
      return Optional.empty();
    }
    Operation operation = classified.orElseThrow();
    if (!single(operation, "page").filter("diff"::equals).isPresent()
        || single(operation, "oid").isEmpty()
        || single(operation, "oid2").isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(operation);
  }

  private static Optional<String> single(Operation operation, String dimension) {
    List<String> values = operation.dimensions().get(dimension);
    if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(values.getFirst());
  }
}
