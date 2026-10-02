package io.github.aalsanie.boundedorigingit.git;

public enum GitObjectType {
  BLOB("blob"),
  TREE("tree"),
  COMMIT("commit"),
  TAG("tag");

  private final String wireName;

  GitObjectType(String wireName) {
    this.wireName = wireName;
  }

  String wireName() {
    return wireName;
  }
}
