package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;

public final class RefGenerationIntegrityException extends IOException {
  private static final long serialVersionUID = 1L;

  public RefGenerationIntegrityException(String message) {
    super(message);
  }

  public RefGenerationIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
