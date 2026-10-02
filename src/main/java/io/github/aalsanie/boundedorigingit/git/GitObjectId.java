package io.github.aalsanie.boundedorigingit.git;

import java.util.Locale;
import java.util.Objects;

public record GitObjectId(GitHashAlgorithm algorithm, String hexadecimal) {
  public GitObjectId {
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(hexadecimal, "hexadecimal");
    if (hexadecimal.length() != algorithm.hexadecimalLength()) {
      throw new IllegalArgumentException(
          "object id must contain " + algorithm.hexadecimalLength() + " hexadecimal characters");
    }
    for (int index = 0; index < hexadecimal.length(); index++) {
      char value = hexadecimal.charAt(index);
      if (!(value >= '0' && value <= '9'
          || value >= 'a' && value <= 'f'
          || value >= 'A' && value <= 'F')) {
        throw new IllegalArgumentException("object id must contain only hexadecimal characters");
      }
    }
    hexadecimal = hexadecimal.toLowerCase(Locale.ROOT);
  }

  String fanout() {
    return hexadecimal.substring(0, 2);
  }

  String filename() {
    return hexadecimal.substring(2);
  }
}
