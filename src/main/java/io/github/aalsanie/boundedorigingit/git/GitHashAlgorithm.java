package io.github.aalsanie.boundedorigingit.git;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum GitHashAlgorithm {
  SHA1("SHA-1", 40),
  SHA256("SHA-256", 64);

  private final String messageDigestName;
  private final int hexadecimalLength;

  GitHashAlgorithm(String messageDigestName, int hexadecimalLength) {
    this.messageDigestName = messageDigestName;
    this.hexadecimalLength = hexadecimalLength;
  }

  int hexadecimalLength() {
    return hexadecimalLength;
  }

  MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(messageDigestName);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("missing message digest " + messageDigestName, exception);
    }
  }
}
