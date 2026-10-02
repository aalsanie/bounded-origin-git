package io.github.aalsanie.boundedorigingit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleasedArtifactsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void consumesReleasedPublicArtifacts() throws Exception {
    Operation operation = new Operation("external-consumer", Map.of("id", List.of("42")));
    Budget budget = new Budget(1, 0, Duration.ofSeconds(1), 1024);

    assertEquals("external-consumer", operation.type());
    assertEquals(1, budget.maxActive());

    try (BoundedOriginExecutor ignored =
            new BoundedOriginExecutor(budget, Duration.ofMillis(1), 16);
        FileSystemArtifactStore store =
            new FileSystemArtifactStore(temporaryDirectory.resolve("store"), 4096, 16)) {
      assertEquals(0, store.stats().entryCount());
    }

    assertFalse(GatewayConfig.class.getName().isBlank());
  }
}
