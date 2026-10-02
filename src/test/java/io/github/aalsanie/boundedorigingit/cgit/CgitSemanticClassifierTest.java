package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            "/",
            "url=group%2Frepo%2Ftree%2Fsrc%2Fmain%2FApp.java&h=main&id=abc123");
    Operation legacy =
        classify(
            "/",
            "r=group%2Frepo&p=tree&path=src%2Fmain%2FApp.java%2F&h=main&id=abc123");

    assertEquals(virtual, url);
    assertEquals(virtual, legacy);
    assertEquals(canonicalize(virtual), canonicalize(url));
    assertEquals(canonicalize(virtual), canonicalize(legacy));
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
    Operation url = classify("/", "url=%2Fproject");
    Operation legacy = classify("/", "r=project");

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
    assertEquals(canonicalize(first), canonicalize(second));
  }

  @Test
  void decodesUtf8AndReservedFilenameCharactersExactlyOnceAcrossRoutes() {
    String encoded = "src/A%20B%2B%25%23%C3%A9.java";
    Operation virtual = classify("/project/tree/" + encoded, "id=abc");
    Operation legacy = classify("/", "r=project&p=tree&path=" + encoded + "&id=abc");
    Operation url = classify("/", "url=project/tree/" + encoded + "&id=abc");
    assertEquals(List.of("src/A B+%#\u00e9.java"), virtual.dimensions().get("path"));
    assertEquals(virtual, legacy);
    assertEquals(virtual, url);
    assertEquals(
        List.of("src/A%20B.java"),
        classify("/project/tree/src/A%2520B.java", null).dimensions().get("path"));
    assertEquals(
        List.of("src/A+B.java"),
        classify("/project/tree/src/A+B.java", null).dimensions().get("path"));
  }

  @Test
  void rejectsMalformedUtf8AndUnsafePathsAcrossRoutingForms() {
    assertTrue(classifyOptional("/project/tree/%C3%28", null).isEmpty());
    assertTrue(classifyOptional("/", "r=project&p=tree&path=%C3%28").isEmpty());
    assertTrue(classifyOptional("/project/log", "qt=grep&q=%C0%AF").isEmpty());
    assertTrue(classifyOptional("/", "r=project&p=tree&path=../secret").isEmpty());
    assertTrue(classifyOptional("/", "url=project/tree/%2e%2e/secret").isEmpty());
    assertTrue(classifyOptional("/project/tree/a%00b", null).isEmpty());
  }

  @Test
  void canonicalizesCgitAliasesAndDefaults() {
    Operation sideBySide = classify("/project/diff/src", "id=new&id2=old&ss=1");
    Operation explicitType =
        classify("/project/diff/src/", "id=new&id2=old&dt=1&context=3");
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
    Operation commitWithIrrelevantParent =
        classify("/project/commit", "id=abc&id2=parent");

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

    assertNotEquals(canonicalize(treeA), canonicalize(treeB));
    assertNotEquals(canonicalize(searchA), canonicalize(searchB));
    assertNotEquals(canonicalize(diffA), canonicalize(diffB));
  }

  @Test
  void normalizesRefsSubroutes() {
    Operation heads = classify("/project/refs/heads", "h=main");
    Operation nestedHeads = classify("/project/refs/heads/ignored", "h=main");
    Operation allRefs = classify("/project/refs/other", "h=main");

    assertEquals(heads, nestedHeads);
    assertNotEquals(canonicalize(heads), canonicalize(allRefs));
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
    assertTrue(classifyOptional("/project/HEAD", null).isEmpty());
    assertTrue(classifyOptional("/project/info/refs", null).isEmpty());
    assertTrue(classifyOptional("/project/objects/info/packs", null).isEmpty());
    assertTrue(classifyOptional("/project/about", null).isEmpty());
    assertTrue(classifyOptional("/project/not-a-page", null).isEmpty());
  }

  @Test
  void rejectsMalformedRelevantOptions() {
    assertTrue(classifyOptional("/project/log", "ofs=-1").isEmpty());
    assertTrue(classifyOptional("/project/log", "qt=grep").isEmpty());
    assertTrue(classifyOptional("/project/log", "q=value").isEmpty());
    assertTrue(classifyOptional("/project/log", "qt=invalid&q=value").isEmpty());
    assertTrue(classifyOptional("/project/log", "showmsg=2").isEmpty());
    assertTrue(classifyOptional("/project/diff", "dt=3").isEmpty());
    assertTrue(classifyOptional("/project/diff", "context=41").isEmpty());
    assertTrue(classifyOptional("/project/diff", "dt=1&ss=1").isEmpty());
    assertTrue(classifyOptional("/", "url=project%2").isEmpty());
  }

  @Test
  void requiresPathForSnapshotAndBlame() {
    assertTrue(classifyOptional("/project/snapshot", "id=abc").isEmpty());
    assertTrue(classifyOptional("/project/blame", "id=abc").isEmpty());
    assertTrue(
        classifyOptional("/project/snapshot/project-main.tar.gz", "id=abc").isPresent());
    assertTrue(classifyOptional("/project/blame/src/App.java", "id=abc").isPresent());
  }

  @Test
  void classifiesThroughReleasedPolicyMatcherContract() {
    assertTrue(classifier.classify(descriptor("POST", "/project/tree", null)).isEmpty());
    assertTrue(
        classifier
            .classify(
                new RequestDescriptor(
                    "other",
                    Map.of("method", List.of("GET"), "path", List.of("/project/tree")),
                    TrustLevel.UNTRUSTED))
            .isEmpty());
    assertTrue(classifyOptional("/project/tree/%2Fescape", null).isEmpty());
    assertTrue(classifyOptional("/project/tree/%2e%2e/file", null).isEmpty());
  }

  @Test
  void validatesRepositoryConfigurationAndCanonicalizationDomain() {
    assertThrows(IllegalArgumentException.class, () -> new CgitSemanticClassifier(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgitSemanticClassifier(List.of("project", "project")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgitSemanticClassifier(List.of("/project")));

    Operation unrelated = new Operation("other", Map.of("repository", List.of("project")));
    assertThrows(IllegalArgumentException.class, () -> canonicalize(unrelated));
  }

  private Operation classify(String path, String query) {
    return classifyOptional(path, query).orElseThrow();
  }

  private Optional<Operation> classifyOptional(String path, String query) {
    return classifier.classify(descriptor("GET", path, query));
  }

  private String canonicalize(Operation operation) {
    return classifier.canonicalizer().canonicalize(operation);
  }

  private static RequestDescriptor descriptor(String method, String path, String query) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of(method));
    attributes.put("path", List.of(path));
    if (query != null) {
      attributes.put("query", List.of(query));
    }
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }
}
