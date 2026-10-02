package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    Optional<String> page = single(operation, "page");
    Optional<String> newer = single(operation, "oid");
    Optional<String> older = single(operation, "oid2");
    if (page.filter("diff"::equals).isEmpty()
        || newer.isEmpty()
        || older.isEmpty()
        || operation.dimensions().containsKey("follow")
        || !fullObjectId(newer.orElseThrow())
        || !fullObjectId(older.orElseThrow())
        || newer.orElseThrow().length() != older.orElseThrow().length()) {
      return Optional.empty();
    }

    Map<String, List<String>> dimensions = new LinkedHashMap<>(operation.dimensions());
    dimensions.remove("head");
    dimensions.put("oid", List.of(newer.orElseThrow().toLowerCase(Locale.ROOT)));
    dimensions.put("oid2", List.of(older.orElseThrow().toLowerCase(Locale.ROOT)));
    return Optional.of(new Operation(operation.type(), dimensions));
  }

  private static boolean fullObjectId(String value) {
    if (value.length() != 40 && value.length() != 64) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.digit(value.charAt(index), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  private static Optional<String> single(Operation operation, String dimension) {
    List<String> values = operation.dimensions().get(dimension);
    if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(values.getFirst());
  }
}
