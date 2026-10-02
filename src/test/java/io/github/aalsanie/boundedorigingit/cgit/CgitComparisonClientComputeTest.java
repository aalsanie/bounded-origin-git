package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.proxy.BoundedOriginGateway;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CgitComparisonClientComputeTest {
  private static final Budget GLOBAL_BUDGET =
      new Budget(1, 2, Duration.ofSeconds(5), 4096);

  @TempDir Path temporaryDirectory;

  @Test
  void selectsOnlyExplicitTwoSidedComparisons() {
    CgitComparisonClientCompute integration = integration();
    PolicyEngine engine = engine(integration);

    OriginDecision.Selected selected =
        assertInstanceOf(
            OriginDecision.Selected.class,
            engine.evaluate(descriptor("/project/diff", "id=new&id2=old")));
    assertEquals(ExecutionStrategy.CLIENT_COMPUTE, selected.policy().strategy());
    assertEquals(
        new ClientComputation(
            "bounded-origin-git.compare",
            "1",
            Map.of("request", "cgit-two-sided-diff")),
        selected.policy().clientComputation().orElseThrow());

    OriginDecision.Denied oneSided =
        assertInstanceOf(
            OriginDecision.Denied.class,
            engine.evaluate(descriptor("/project/diff", "id=new")));
    assertEquals(DenialReason.NO_MATCH, oneSided.reason());

    OriginDecision.Denied nonComparison =
        assertInstanceOf(
            OriginDecision.Denied.class,
            engine.evaluate(descriptor("/project/tree", "h=main&id=new")));
    assertEquals(DenialReason.NO_MATCH, nonComparison.reason());
  }

  @Test
  void preservesSemanticIdentityAcrossAliasesAndDistinctPairs() {
    PolicyEngine engine = engine(integration());

    OriginDecision.Selected virtual =
        selected(engine, "/project/diff/src/App.java", "id=new&id2=old&context=5");
    OriginDecision.Selected legacy =
        selected(
            engine,
            "/",
            "r=project&p=diff&path=src%2FApp.java&id2=old&context=05&id=new");
    OriginDecision.Selected different =
        selected(engine, "/project/diff/src/App.java", "id=other&id2=old&context=5");

    assertEquals(virtual.operation(), legacy.operation());
    assertEquals(virtual.operationKey(), legacy.operationKey());
    assertNotEquals(virtual.operationKey(), different.operationKey());
    assertEquals(
        virtual.policy().clientComputation().orElseThrow(),
        different.policy().clientComputation().orElseThrow());
  }

  @Test
  void releasedGatewayReturnsDescriptionWithoutReachableOrigin() throws Exception {
    CgitComparisonClientCompute integration = integration();
    PolicyEngine engine = engine(integration);
    InetAddress loopback = InetAddress.getLoopbackAddress();
    GatewayConfig config =
        GatewayConfig.defaults(
            new InetSocketAddress(loopback, 0),
            new InetSocketAddress(loopback, 0),
            new InetSocketAddress(loopback, 1),
            temporaryDirectory.resolve("gateway"),
            GLOBAL_BUDGET);

    try (FileSystemArtifactStore store =
            new FileSystemArtifactStore(
                temporaryDirectory.resolve("artifacts"), 1_000_000, 100_000);
        BoundedOriginGateway gateway = new BoundedOriginGateway(config, engine, store)) {
      gateway.start();
      URI uri =
          URI.create(
              "http://"
                  + gateway.listenAddress().getHostString()
                  + ":"
                  + gateway.listenAddress().getPort()
                  + "/project/diff?id=new&id2=old");
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();

      HttpResponse<String> response;
      try (HttpClient client = HttpClient.newHttpClient()) {
        response = client.send(request, HttpResponse.BodyHandlers.ofString());
      }

      assertEquals(200, response.statusCode());
      assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
      assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
      assertEquals(
          "{\"type\":\"bounded-origin-git.compare\",\"version\":\"1\",\"parameters\":{\"request\":\"cgit-two-sided-diff\"}}",
          response.body());
    }
  }

  private static CgitComparisonClientCompute integration() {
    return new CgitComparisonClientCompute(
        new CgitSemanticClassifier(List.of("project")),
        "client-comparison",
        1,
        100);
  }

  private static PolicyEngine engine(CgitComparisonClientCompute integration) {
    return new PolicyEngine(
        List.of(integration.rule()),
        OriginPolicy.deny("fallback-deny", 1, Integer.MIN_VALUE));
  }

  private static OriginDecision.Selected selected(
      PolicyEngine engine, String path, String query) {
    return assertInstanceOf(
        OriginDecision.Selected.class, engine.evaluate(descriptor(path, query)));
  }

  private static RequestDescriptor descriptor(String path, String query) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("path", List.of(path));
    attributes.put("query", List.of(query));
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }
}
