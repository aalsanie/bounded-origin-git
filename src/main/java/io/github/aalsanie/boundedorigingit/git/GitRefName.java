package io.github.aalsanie.boundedorigingit.git;

import java.util.Objects;

public record GitRefName(String value) {
  private static final int MAX_LENGTH = 1024;

  public GitRefName {
    Objects.requireNonNull(value, "value");
    if (!value.startsWith("refs/")
        || value.length() > MAX_LENGTH
        || value.endsWith("/")
        || value.endsWith(".")
        || value.contains("//")
        || value.contains("..")
        || value.contains("@{")) {
      throw new IllegalArgumentException("invalid Git ref name");
    }

    String[] components = value.split("/", -1);
    for (String component : components) {
      if (component.isEmpty()
          || component.startsWith(".")
          || component.endsWith(".lock")
          || containsForbiddenCharacter(component)) {
        throw new IllegalArgumentException("invalid Git ref name");
      }
    }
  }

  private static boolean containsForbiddenCharacter(String component) {
    for (int index = 0; index < component.length(); index++) {
      char character = component.charAt(index);
      if (character <= 0x20
          || character == 0x7f
          || character == '~'
          || character == '^'
          || character == ':'
          || character == '?'
          || character == '*'
          || character == '['
          || character == '\\') {
        return true;
      }
    }
    return false;
  }
}
