package io.github.aalsanie.boundedorigingit.git;

import java.io.IOException;

public final class GitObjectIntegrityException extends IOException {
  private static final long serialVersionUID = 1L;

  public GitObjectIntegrityException(String message) {
    super(message);
  }

  public GitObjectIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
