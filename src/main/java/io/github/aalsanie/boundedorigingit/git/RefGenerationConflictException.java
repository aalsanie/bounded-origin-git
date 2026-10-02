package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;

public final class RefGenerationConflictException extends IOException {
  private static final long serialVersionUID = 1L;

  public RefGenerationConflictException(String message) {
    super(message);
  }
}
