package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CgitSemanticClassifierTest {
  private final CgitSemanticClassifier classifier =
      new CgitSemanticClassifier(List.of("project", "group/repo"));

  @Test
  void canonicalizesVirtualUrlAndLegacyRoutesToOneOperation() {
    Operation virtual =
        classify("/group/repo/tree/src/main/App.java", "h=main&id=abc123");
    Operation url =
        classify(
            null,
            "url=group%2Frepo%2Ftree%2Fsrc%2Fmain%2FApp.java&h=main&id=abc123");
    Operation legacy =
        classify(
            null,
            "r=group%2Frepo&p=tree&path=src%2Fmain%2FApp.java%2F&h=main&id=abc123");

    assertEquals(virtual, url);
    assertEquals(virtual, legacy);
    assertEquals(classifier.canonicalizer().canonicalize(virtual), classifier.canonicalizer().canonicalize(url));
    assertEquals(classifier.canonicalizer().canonicalize(virtual), classifier.canonicalizer().canonicalize(legacy));
  }

  @Test
  void selectsLongestConfiguredRepositoryPrefix() {
    Operation operation = classify("/group/repo/tree/src/App.java", null);

    assertEquals(List.of("group/repo"), operation.dimensions().get("repository"));
    assertEquals(List.of("tree"), operation.dimensions().get("page"));
    assertEquals(List.of("src/App.java"), operation.dimensions().get("path"));
  }

  @Test
  void canonicalizesRepositoryRootToSummary() {
    Operation virtual = classify("/project/", null);
    Operation url = classify(null, "url=%2Fproject");
    Operation legacy = classify(null, "r=project");

    assertEquals(virtual, url);
    assertEquals(virtual, legacy);
    assertEquals(
        Map.of("repository", List.of("project"), "page", List.of("summary")),
        virtual.dimensions());
  }

  @Test
  void canonicalizesPathEncodingAndQueryOrdering() {
    Operation encodedPath = classify("/project/tree/src/%7Ename", "h=main&id=abc");
    Operation plainPath = classify("/project/tree/src/~name", "h=main&id=abc");
    Operation first =
        classify("/project/log/src", "h=main&qt=grep&q=fix+bug&ofs=20&showmsg=1");
    Operation second =
        classify(
            "/project/log/src",
            "showmsg=1&q=fix%20bug&ofs=00020&qt=grep&h=main");

    assertEquals(encodedPath, plainPath);
    assertEquals(first, second);
    assertEquals(
        classifier.canonicalizer().canonicalize(first),
        classifier.canonicalizer().canonicalize(second));
  }

  @Test
  void canonicalizesCgitAliasesAndDefaults() {
    Operation sideBySide = classify("/project/diff/src", "id=new&id2=old&ss=1");
    Operation explicitType = classify("/project/diff/src/", "id=new&id2=old&dt=1&context=3");
    Operation weekly = classify("/project/stats", "period=week&ofs=10");
    Operation defaultStats = classify("/project/stats", null);

    assertEquals(sideBySide, explicitType);
    assertEquals(weekly, defaultStats);
  }

  @Test
  void irrelevantRecognizedParametersDoNotCreateDistinctWork() {
    Operation canonical = classify("/project/tree/src", "h=main&id=abc");
    Operation noisy =
        classify(
            "/project/tree/src",
            "h=main&id=abc&period=year&showmsg=1&dt=2&q=anything&qt=grep&ignorews=1");
    Operation commit = classify("/project/commit", "id=abc");
    Operation commitWithIrrelevantParent = classify("/project/commit", "id=abc&id2=parent");

    assertEquals(canonical, noisy);
    assertEquals(commit, commitWithIrrelevantParent);
  }

  @Test
  void semanticInputsRemainDistinct() {
    Operation treeA = classify("/project/tree/src/A.java", "h=main&id=abc");
    Operation treeB = classify("/project/tree/src/B.java", "h=main&id=abc");
    Operation searchA = classify("/project/log", "h=main&qt=grep&q=alpha");
    Operation searchB = classify("/project/log", "h=main&qt=grep&q=beta");
    Operation diffA = classify("/project/diff", "id=new&id2=old&context=5");
    Operation diffB = classify("/project/diff", "id=new&id2=old&context=10");

    assertNotEquals(classifier.canonicalizer().canonicalize(treeA), classifier.canonicalizer().canonicalize(treeB));
    assertNotEquals(classifier.canonicalizer().canonicalize(searchA), classifier.canonicalizer().canonicalize(searchB));
    assertNotEquals(classifier.canonicalizer().canonicalize(diffA), classifier.canonicalizer().canonicalize(diffB));
  }

  @Test
  void normalizesRefsSubroutes() {
    Operation heads = classify("/project/refs/heads", "h=main");
    Operation nestedHeads = classify("/project/refs/heads/ignored", "h=main");
    Operation allRefs = classify("/project/refs/other", "h=main");

    assertEquals(heads, nestedHeads);
    assertNotEquals(classifier.canonicalizer().canonicalize(heads), classifier.canonicalizer().canonicalize(allRefs));
  }

  @Test
  void rejectsUnknownDuplicateAndMixedRouting() {
    assertTrue(classifyOptional("/project/tree", "unknown=value").isEmpty());
    assertTrue(classifyOptional("/project/tree", "h=main&h=other").isEmpty());
    assertTrue(classifyOptional("/project/tree", "url=project%2Ftree").isEmpty());
    assertTrue(classifyOptional("/", "url=project%2Ftree&r=project").isEmpty());
    assertTrue(classifyOptional("/", "p=tree").isEmpty());
    assertTrue(classifyOptional("/", "r=project&p=").isEmpty());
  }

  @Test
  void rejectsCloneAdministrativeAndUnsupportedPages() {
    assertTrue(classifier.classify("/project/HEAD", null).isEmpty());
    assertTrue(classifier.classify("/project/info/refs", null).isEmpty());
    assertTrue(classifier.classify("/project/objects/info/packs", null).isEmpty());
    assertTrue(classifier.classify("/project/about", null).isEmpty());
    assertTrue(classifier.classify("/project/not-a-page", null).isEmpty());
  }

  @Test
  void rejectsMalformedRelevantOptions() {
    assertTrue(classifier.classify("/project/log", "ofs=-1").isEmpty());
    assertTrue(classifier.classify("/project/log", "qt=grep").isEmpty());
    assertTrue(classifier.classify("/project/log", "q=value").isEmpty());
    assertTrue(classifier.classify("/project/log", "qt=invalid&q=value").isEmpty());
    assertTrue(classifier.classify("/project/log", "showmsg=2").isEmpty());
    assertTrue(classifier.classify("/project/diff", "dt=3").isEmpty());
    assertTrue(classifier.classify("/project/diff", "context=41").isEmpty());
    assertTrue(classifier.classify("/project/diff", "dt=1&ss=1").isEmpty());
    assertTrue(classifier.classify(null, "url=project%2").isEmpty());
  }

  @Test
  void requiresPathForSnapshotAndBlame() {
    assertTrue(classifier.classify("/project/snapshot", "id=abc").isEmpty());
    assertTrue(classifier.classify("/project/blame", "id=abc").isEmpty());
    assertTrue(classifier.classify("/project/snapshot/project-main.tar.gz", "id=abc").isPresent());
    assertTrue(classifier.classify("/project/blame/src/App.java", "id=abc").isPresent());
  }

  @Test
  void validatesRepositoryConfigurationAndOperationType() {
    assertThrows(IllegalArgumentException.class, () -> new CgitSemanticClassifier(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgitSemanticClassifier(List.of("project", "project")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgitSemanticClassifier(List.of("/project")));

    Operation unrelated = new Operation("other", Map.of("repository", List.of("project")));
    assertThrows(IllegalArgumentException.class, () -> classifier.canonicalizer().canonicalize(unrelated));
  }

  private Operation classify(String pathInfo, String query) {
    return classifier.classify(pathInfo, query).orElseThrow();
  }
}
