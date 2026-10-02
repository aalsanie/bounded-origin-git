package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CgitArtifactNamespace {
  private static final String OPERATION_TYPE = "cgit-render";
  private static final String GENERATION = "generation";
  private static final String SNAPSHOT = "refSnapshot";
  private static final String DEFAULT_REF = "defaultRef";

  private static final List<String> DIMENSIONS =
      List.of(
          "repository",
          "page",
          "path",
          "head",
          "oid",
          "oid2",
          "grep",
          "search",
          "offset",
          "showMessage",
          "period",
          "diffType",
          "showAll",
          "context",
          "ignoreWhitespace",
          "follow",
          GENERATION,
          SNAPSHOT,
          DEFAULT_REF);

  private static final Canonicalizer BASE_CANONICALIZER =
      Canonicalizers.byDimensions(DIMENSIONS);

  private final String repository;
  private final GitRefName defaultRef;
  private final Canonicalizer canonicalizer = this::canonicalize;

  public CgitArtifactNamespace(String repository, GitRefName defaultRef) {
    this.repository = requireRepository(repository);
    this.defaultRef = Objects.requireNonNull(defaultRef, "defaultRef");
  }

  public Canonicalizer canonicalizer() {
    return canonicalizer;
  }

  public Operation pin(Operation operation, RefGenerationSnapshot snapshot) {
    validateOperation(operation);
    Objects.requireNonNull(snapshot, "snapshot");

    Map<String, List<String>> dimensions = new LinkedHashMap<>(operation.dimensions());
    rejectReserved(dimensions, GENERATION);
    rejectReserved(dimensions, SNAPSHOT);
    rejectReserved(dimensions, DEFAULT_REF);
    dimensions.put(GENERATION, List.of(Long.toString(snapshot.generation())));
    dimensions.put(SNAPSHOT, List.of(snapshotFingerprint(snapshot)));
    dimensions.put(DEFAULT_REF, List.of(defaultRef.value()));
    return new Operation(OPERATION_TYPE, dimensions);
  }

  public long generation(Operation operation) {
    validatePinned(operation);
    try {
      return Long.parseLong(single(operation, GENERATION));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("invalid pinned generation", exception);
    }
  }

  public String snapshotFingerprint(Operation operation) {
    validatePinned(operation);
    return single(operation, SNAPSHOT);
  }

  public GitRefName defaultRef() {
    return defaultRef;
  }

  private String canonicalize(Operation operation) {
    validatePinned(operation);
    return BASE_CANONICALIZER.canonicalize(operation);
  }

  private void validatePinned(Operation operation) {
    validateOperation(operation);
    long generation;
    try {
      generation = Long.parseLong(single(operation, GENERATION));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("invalid pinned generation", exception);
    }
    if (generation < 0) {
      throw new IllegalArgumentException("invalid pinned generation");
    }
    String fingerprint = single(operation, SNAPSHOT);
    if (fingerprint.length() != 64 || !isLowerHex(fingerprint)) {
      throw new IllegalArgumentException("invalid ref snapshot fingerprint");
    }
    if (!defaultRef.value().equals(single(operation, DEFAULT_REF))) {
      throw new IllegalArgumentException("pinned default ref does not match namespace");
    }
  }

  private void validateOperation(Operation operation) {
    Objects.requireNonNull(operation, "operation");
    if (!OPERATION_TYPE.equals(operation.type())) {
      throw new IllegalArgumentException("unexpected operation type " + operation.type());
    }
    if (!repository.equals(single(operation, "repository"))) {
      throw new IllegalArgumentException("operation targets a different repository");
    }
  }

  private static String single(Operation operation, String dimension) {
    List<String> values = operation.dimensions().get(dimension);
    if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
      throw new IllegalArgumentException(
          dimension + " must contain exactly one non-blank value");
    }
    return values.getFirst();
  }

  private static void rejectReserved(Map<String, List<String>> dimensions, String name) {
    if (dimensions.containsKey(name)) {
      throw new IllegalArgumentException("operation already contains reserved dimension " + name);
    }
  }

  private static String snapshotFingerprint(RefGenerationSnapshot snapshot) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }

    updateLong(digest, snapshot.refs().size());
    for (Map.Entry<io.github.aalsanie.boundedorigingit.git.GitRefName, GitObjectId> entry :
        snapshot.refs().entrySet()) {
      updateString(digest, entry.getKey().value());
      updateString(digest, entry.getValue().algorithm().name());
      updateString(digest, entry.getValue().hexadecimal());
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static void updateLong(MessageDigest digest, long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      digest.update((byte) (value >>> shift));
    }
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    updateLong(digest, bytes.length);
    digest.update(bytes);
  }

  private static boolean isLowerHex(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean digit = character >= '0' && character <= '9';
      boolean lower = character >= 'a' && character <= 'f';
      if (!digit && !lower) {
        return false;
      }
    }
    return true;
  }

  private static String requireRepository(String repository) {
    Objects.requireNonNull(repository, "repository");
    if (repository.isBlank()) {
      throw new IllegalArgumentException("repository must not be blank");
    }
    return repository;
  }
}
