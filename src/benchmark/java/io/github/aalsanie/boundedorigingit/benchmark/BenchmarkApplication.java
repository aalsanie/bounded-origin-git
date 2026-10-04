package io.github.aalsanie.boundedorigingit.benchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.OriginExecutionException;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import io.github.aalsanie.boundedorigin.proxy.BoundedOriginGateway;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.proxy.HttpOperation;
import io.github.aalsanie.boundedorigin.proxy.RepresentationContract;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import io.github.aalsanie.boundedorigingit.cgit.CgitArtifactNamespace;
import io.github.aalsanie.boundedorigingit.cgit.CgitArtifactService;
import io.github.aalsanie.boundedorigingit.cgit.CgitComparisonClientCompute;
import io.github.aalsanie.boundedorigingit.cgit.CgitRenderExecutor;
import io.github.aalsanie.boundedorigingit.cgit.CgitSemanticClassifier;
import io.github.aalsanie.boundedorigingit.cgit.ProcessCgitRenderer;
import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitObjectType;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationStore;
import io.github.aalsanie.boundedorigingit.git.RepositoryUpdateEvent;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkApplication {
  private static final GitRefName MAIN = new GitRefName("refs/heads/main");
  private static final String EMPTY_BODY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String RENDER_POLICY = "cgit-render";
  private static final String HTTP_POLICY = "cgit-http";
  private static final String VERSION = "cgit-benchmark-v1";
  private static final long MAX_OBJECT = 64L * 1024 * 1024;
  private static final long MAX_ARTIFACT = 128L * 1024 * 1024;

  private BenchmarkApplication() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2) {
      throw new IllegalArgumentException("usage: ingest|serve configuration.properties");
    }
    Properties config = new Properties();
    try (var input = Files.newBufferedReader(Path.of(arguments[1]), StandardCharsets.UTF_8)) {
      config.load(input);
    }
    if (arguments[0].equals("ingest")) {
      ingest(config);
    } else if (arguments[0].equals("serve")) {
      try (Application application = new Application(config)) {
        application.start();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        new CountDownLatch(1).await();
      }
    } else {
      throw new IllegalArgumentException("unknown command");
    }
  }

  private static void ingest(Properties config) throws Exception {
    Path root = Path.of(required(config, "objects"));
    GitObjectStore objects = new GitObjectStore(root, GitHashAlgorithm.SHA1, MAX_OBJECT);
    long start = System.nanoTime();
    long count = 0;
    long created = 0;
    long bytes = 0;
    Process process =
        new ProcessBuilder(
                "git",
                "--git-dir=" + required(config, "dataset"),
                "cat-file",
                "--batch-all-objects",
                "--batch",
                "--unordered")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try (InputStream input = new BufferedInputStream(process.getInputStream(), 128 * 1024)) {
      String header;
      while ((header = line(input)) != null) {
        String[] fields = header.split(" ");
        if (fields.length != 3 || ++count > 1_000_000) {
          throw new IOException("invalid or excessive Git object stream");
        }
        long size = Long.parseLong(fields[2]);
        GitObjectId oid = new GitObjectId(GitHashAlgorithm.SHA1, fields[0]);
        GitObjectType type = GitObjectType.valueOf(fields[1].toUpperCase(java.util.Locale.ROOT));
        LimitedInput payload = new LimitedInput(input, size);
        if (objects.ingest(oid, type, size, payload).created()) {
          created++;
        }
        payload.transferTo(OutputStream.nullOutputStream());
        if (payload.remaining != 0 || input.read() != '\n') {
          throw new IOException("truncated Git object stream");
        }
        bytes += size;
        if (count % 10_000 == 0) {
          System.err.println("ingested " + count + " objects");
        }
      }
      if (process.waitFor() != 0) {
        throw new IOException("git cat-file failed");
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor();
      }
    }
    System.out.println(
        json(
            map(
                "objects",
                count,
                "created",
                created,
                "payload_bytes",
                bytes,
                "wall_ns",
                System.nanoTime() - start)));
  }

  private static final class Application implements AutoCloseable {
    private final Path root;
    private final GitObjectStore objects;
    private final FileSystemArtifactStore disk;
    private final CgitRenderExecutor executor;
    private final CgitSemanticClassifier classifier;
    private final Map<String, Repository> repositories = new LinkedHashMap<>();
    private final AtomicLong renderCalls = new AtomicLong();
    private final AtomicLong objectRequests = new AtomicLong();
    private final AtomicLong objectBytes = new AtomicLong();
    private final BoundedOriginGateway gateway;
    private final HttpServer assets;
    private final HttpServer control;
    private final ExecutorService assetWorkers = workers(8, 128);
    private final ExecutorService controlWorkers = workers(2, 8);
    private final String token;
    private final Properties config;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Application(Properties config) throws Exception {
      this.config = config;
      root = Path.of(required(config, "root"));
      Files.createDirectories(root);
      token = required(config, "token");
      objects =
          new GitObjectStore(
              Path.of(required(config, "objects")), GitHashAlgorithm.SHA1, MAX_OBJECT);
      disk =
          new FileSystemArtifactStore(
              root.resolve("artifacts"), 2L * 1024 * 1024 * 1024, MAX_ARTIFACT);
      Budget budget = new Budget(2, 16, Duration.ofSeconds(5), MAX_ARTIFACT);
      executor = new CgitRenderExecutor(budget, Duration.ofMillis(500), 2048);
      List<String> names = List.of(required(config, "repositories").split(","));
      classifier = new CgitSemanticClassifier(names);
      ArtifactStore renderStore =
          new ArtifactStore() {
            @Override
            public Optional<Artifact> get(OperationKey key) throws IOException {
              return disk.get(httpKey(key));
            }

            @Override
            public void put(OperationKey key, Artifact artifact) throws IOException {
              Map<String, String> metadata = new LinkedHashMap<>(artifact.metadata());
              metadata.put("Cache-Control", "public");
              disk.put(
                  httpKey(key),
                  new Artifact(
                      artifact.statusCode(), artifact.contentLength(), metadata, artifact.body()));
            }
          };
      for (String name : names) {
        CgitArtifactNamespace namespace = new CgitArtifactNamespace(name, MAIN);
        RefGenerationStore refs =
            new RefGenerationStore(root.resolve(name).resolve("refs"), name, objects, 32, 32);
        if (refs.current().generation() == 0) {
          refs.publish(
              List.of(
                  new RepositoryUpdateEvent(
                      name, MAIN, Optional.empty(), Optional.of(oid(required(config, "head"))))),
              TrustLevel.TRUSTED);
        }
        ProcessCgitRenderer renderer =
            new ProcessCgitRenderer(
                List.of(required(config, "cgi.wrapper")),
                namespace,
                root.resolve(name).resolve("render"),
                objects,
                MAX_ARTIFACT,
                16384,
                16384,
                Duration.ofMillis(250));
        CgitArtifactService service =
            new CgitArtifactService(
                namespace,
                executor,
                renderStore,
                (snapshot, operation) -> {
                  renderCalls.incrementAndGet();
                  return renderer.render(snapshot, operation);
                },
                RENDER_POLICY,
                1,
                VERSION,
                budget);
        repositories.put(name, new Repository(namespace, refs, service));
      }
      OriginPolicy artifactPolicy =
          OriginPolicy.artifactOnly(HTTP_POLICY, 1, 10, VERSION, HttpOperation.canonicalizer());
      PolicyRule artifacts =
          new PolicyRule(
              artifactPolicy,
              request ->
                  classifier
                      .classify(request)
                      .map(
                          operation -> {
                            Repository repository = repository(operation);
                            Operation pinned =
                                repository.namespace.pin(operation, repository.refs.current());
                            OperationKey key =
                                new OperationKey(
                                    RENDER_POLICY,
                                    1,
                                    repository.namespace.canonicalizer().canonicalize(pinned),
                                    VERSION);
                            return httpOperation(key).operation();
                          }));
      CgitComparisonClientCompute comparisons =
          new CgitComparisonClientCompute(classifier, "client-comparison", 1, 100);
      PolicyEngine policies =
          new PolicyEngine(
              List.of(comparisons.rule(), artifacts),
              OriginPolicy.deny("default-deny", 1, Integer.MIN_VALUE));
      GatewayConfig gatewayConfig =
          GatewayConfig.from(
              Map.ofEntries(
                  Map.entry("listen.host", "127.0.0.1"),
                  Map.entry("listen.port", "0"),
                  Map.entry("admin.host", "127.0.0.1"),
                  Map.entry("admin.port", "0"),
                  Map.entry("origin.host", "127.0.0.1"),
                  Map.entry("origin.port", "1"),
                  Map.entry("temporary.directory", root.resolve("gateway").toString()),
                  Map.entry("event-loop.threads", "2"),
                  Map.entry("origin.event-loop.threads", "2"),
                  Map.entry("frontend.max-connections", "256"),
                  Map.entry("origin.max-active", "2"),
                  Map.entry("origin.max-queued", "16"),
                  Map.entry("origin.max-result-bytes", Long.toString(MAX_ARTIFACT)),
                  Map.entry("request.timeout", "PT60S"),
                  Map.entry("drain.timeout", "PT2S")));
      gateway = new BoundedOriginGateway(gatewayConfig, policies, disk);
      assets = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
      assets.setExecutor(assetWorkers);
      assets.createContext("/", this::asset);
      control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
      control.setExecutor(controlWorkers);
      control.createContext("/", this::command);
    }

    private void start() throws IOException {
      gateway.start();
      assets.start();
      control.start();
      Files.writeString(
          root.resolve("ready.json"),
          json(
              map(
                  "gateway",
                  gateway.listenAddress().getPort(),
                  "admin",
                  gateway.adminAddress().getPort(),
                  "assets",
                  assets.getAddress().getPort(),
                  "control",
                  control.getAddress().getPort(),
                  "pid",
                  ProcessHandle.current().pid())));
    }

    private void asset(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (!exchange.getRequestMethod().equals("GET")) {
          respond(exchange, 405, map("error", "GET required"));
          return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if (path.matches("/objects/[0-9a-f]{40}")) {
          Path object = objects.objectPath(oid(path.substring(9)));
          if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) {
            respond(exchange, 404, map("error", "object unavailable"));
            return;
          }
          long length = Files.size(object);
          if (length > MAX_OBJECT + 65536) {
            respond(exchange, 413, map("error", "object limit"));
            return;
          }
          objectRequests.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", "application/x-git-loose-object");
          exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
          exchange.sendResponseHeaders(200, length);
          try (InputStream input = Files.newInputStream(object)) {
            objectBytes.addAndGet(input.transferTo(exchange.getResponseBody()));
          }
        } else if (path.equals("/client/cgit-compare-v1.mjs")
            || path.equals("/client/cgit-comparison-request-v1.mjs")) {
          Path module = Path.of(required(config, "client.modules")).resolve(path.substring(8));
          exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
          exchange.sendResponseHeaders(200, Files.size(module));
          try (InputStream input = Files.newInputStream(module)) {
            input.transferTo(exchange.getResponseBody());
          }
        } else {
          respond(exchange, 404, map("error", "not found"));
        }
      }
    }

    private void command(HttpExchange exchange) throws IOException {
      try (exchange) {
        byte[] supplied =
            exchange.getRequestHeaders().getFirst("Authorization") == null
                ? new byte[0]
                : exchange
                    .getRequestHeaders()
                    .getFirst("Authorization")
                    .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(
            ("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied)) {
          respond(exchange, 403, map("error", "unauthorized control request"));
          return;
        }
        try {
          String path = exchange.getRequestURI().getPath();
          if (path.equals("/stats") && exchange.getRequestMethod().equals("GET")) {
            var stats = executor.stats();
            respond(
                exchange,
                200,
                map(
                    "render_calls",
                    renderCalls.get(),
                    "active",
                    stats.activeJobs(),
                    "queued",
                    stats.queuedJobs(),
                    "in_flight",
                    stats.inFlightJobs(),
                    "artifact_entries",
                    disk.stats().entryCount(),
                    "object_requests",
                    objectRequests.get(),
                    "object_bytes",
                    objectBytes.get()));
            return;
          }
          if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, map("error", "POST required"));
            return;
          }
          byte[] body = exchange.getRequestBody().readNBytes(1024 * 1024 + 1);
          if (body.length > 1024 * 1024) {
            respond(exchange, 413, map("error", "control batch too large"));
            return;
          }
          List<String> lines =
              new String(body, StandardCharsets.UTF_8)
                  .lines()
                  .filter(line -> !line.isEmpty())
                  .toList();
          if (lines.size() > 2048) {
            throw new IllegalArgumentException("too many planned operations");
          }
          if (path.equals("/prepare") || path.equals("/flood")) {
            respond(exchange, 200, materialize(lines, path.equals("/flood")));
          } else if (path.equals("/publish")) {
            List<Object> published = new ArrayList<>();
            for (String line : lines) {
              String[] values = line.split("\t", -1);
              Repository repository = repositories.get(values[0]);
              var snapshot =
                  repository.refs.publish(
                      List.of(
                          new RepositoryUpdateEvent(
                              values[0],
                              MAIN,
                              repository.refs.current().resolve(MAIN),
                              Optional.of(oid(values[1])))),
                      TrustLevel.TRUSTED);
              published.add(map("repository", values[0], "generation", snapshot.generation()));
            }
            respond(exchange, 200, published);
          } else {
            respond(exchange, 404, map("error", "unknown control command"));
          }
        } catch (Exception failure) {
          respond(exchange, 500, map("error", failure.toString()));
        }
      }
    }

    private List<Object> materialize(List<String> lines, boolean concurrent) throws Exception {
      List<Object> results = new ArrayList<>();
      List<CgitArtifactService.Materialization> jobs = new ArrayList<>();
      for (String line : lines) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 2) {
          throw new IllegalArgumentException("expected path and query columns");
        }
        Operation operation =
            classifier
                .classify(
                    new RequestDescriptor(
                        "http.request",
                        Map.of(
                            "method",
                            List.of("GET"),
                            "path",
                            List.of(fields[0]),
                            "query",
                            List.of(fields[1])),
                        TrustLevel.TRUSTED))
                .orElseThrow();
        Repository repository = repository(operation);
        CgitArtifactService.Materialization job =
            repository.artifacts.materialize(
                repository.refs.current(), operation, TrustLevel.TRUSTED);
        jobs.add(job);
        if (!concurrent) {
          results.add(outcome(job));
        }
      }
      if (concurrent) {
        for (var job : jobs) {
          results.add(outcome(job));
        }
      }
      return results;
    }

    private static Object outcome(CgitArtifactService.Materialization job) {
      String result = "success";
      try {
        job.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
      } catch (Exception failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof OriginExecutionException)) {
          cause = cause.getCause();
        }
        result =
            cause instanceof OriginExecutionException origin
                ? origin.failure().name()
                : cause.toString();
      }
      return map("result", result, "already_stored", job.alreadyStored(), "joined", job.joined());
    }

    private Repository repository(Operation operation) {
      return repositories.get(operation.dimensions().get("repository").getFirst());
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      control.stop(0);
      assets.stop(0);
      controlWorkers.close();
      assetWorkers.close();
      gateway.close();
      executor.close();
      try {
        disk.close();
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    }
  }

  private record Repository(
      CgitArtifactNamespace namespace, RefGenerationStore refs, CgitArtifactService artifacts) {}

  private static HttpOperation httpOperation(OperationKey key) {
    String identity =
        json(
            List.of(
                key.policyId(),
                key.policyVersion(),
                key.semanticIdentity(),
                key.materializerVersion()));
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
    return new HttpOperation(
        "GET",
        "benchmark.invalid",
        "/artifact/" + digest,
        EMPTY_BODY,
        TrustLevel.UNTRUSTED,
        RepresentationContract.PUBLIC_IMMUTABLE,
        Map.of());
  }

  private static OperationKey httpKey(OperationKey key) {
    return new OperationKey(
        HTTP_POLICY,
        1,
        HttpOperation.canonicalizer().canonicalize(httpOperation(key).operation()),
        VERSION);
  }

  private static ExecutorService workers(int threads, int queue) {
    return new ThreadPoolExecutor(
        threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue));
  }

  private static GitObjectId oid(String value) {
    return new GitObjectId(GitHashAlgorithm.SHA1, value);
  }

  private static String required(Properties config, String key) {
    String value = config.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing property " + key);
    }
    return value;
  }

  private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] body = json(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }

  private static String json(Object value) {
    if (value instanceof Map<?, ?> map) {
      return "{"
          + String.join(
              ",",
              map.entrySet().stream()
                  .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue()))
                  .toList())
          + "}";
    }
    if (value instanceof List<?> list) {
      return "[" + String.join(",", list.stream().map(BenchmarkApplication::json).toList()) + "]";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    StringBuilder result = new StringBuilder("\"");
    for (char character : value.toString().toCharArray()) {
      if (character == '"' || character == '\\') {
        result.append('\\').append(character);
      } else if (character < 32) {
        result.append(String.format("\\u%04x", (int) character));
      } else {
        result.append(character);
      }
    }
    return result.append('"').toString();
  }

  private static String line(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) >= 0 && value != '\n') {
      if (bytes.size() >= 1024) {
        throw new IOException("object header too large");
      }
      bytes.write(value);
    }
    return bytes.size() == 0 && value < 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
  }

  private static final class LimitedInput extends InputStream {
    private final InputStream input;
    private long remaining;

    private LimitedInput(InputStream input, long remaining) {
      this.input = input;
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      if (remaining == 0) {
        return -1;
      }
      int value = input.read();
      if (value >= 0) {
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      int count = input.read(bytes, offset, (int) Math.min(length, remaining));
      if (count > 0) {
        remaining -= count;
      }
      return count;
    }
  }
}
