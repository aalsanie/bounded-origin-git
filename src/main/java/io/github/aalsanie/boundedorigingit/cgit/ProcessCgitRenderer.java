package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.GitRepositoryView;
import io.github.aalsanie.boundedorigingit.git.GitRepositoryViewFactory;
import io.github.aalsanie.boundedorigingit.git.RefGenerationSnapshot;
import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Operation;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessCgitRenderer implements CgitRenderer {
  private static final List<String> QUERY_DIMENSIONS =
      List.of(
          "head",
          "oid",
          "oid2",
          "grep",
          "search",
          "offset",
          "showMessage",
          "period",
          "diffType",
          "showAll",
          "context",
          "ignoreWhitespace",
          "follow");

  private final List<String> command;
  private final CgitArtifactNamespace namespace;
  private final GitRepositoryViewFactory views;
  private final Path artifactDirectory;
  private final long maxArtifactBytes;
  private final int maxHeaderBytes;
  private final int maxStderrBytes;
  private final Duration terminationGrace;

  public ProcessCgitRenderer(
      List<String> command,
      CgitArtifactNamespace namespace,
      Path workRoot,
      GitObjectStore objectStore,
      long maxArtifactBytes,
      int maxHeaderBytes,
      int maxStderrBytes,
      Duration terminationGrace)
      throws IOException {
    this.command = validatedCommand(command);
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(workRoot, "workRoot");
    if (maxArtifactBytes <= 0) {
      throw new IllegalArgumentException("maxArtifactBytes must be positive");
    }
    if (maxHeaderBytes <= 0 || maxStderrBytes < 0) {
      throw new IllegalArgumentException("invalid cgit output limits");
    }
    this.maxArtifactBytes = maxArtifactBytes;
    this.maxHeaderBytes = maxHeaderBytes;
    this.maxStderrBytes = maxStderrBytes;
    this.terminationGrace = Objects.requireNonNull(terminationGrace, "terminationGrace");
    if (terminationGrace.isNegative() || terminationGrace.isZero()) {
      throw new IllegalArgumentException("terminationGrace must be positive");
    }

    Path root = workRoot.toAbsolutePath().normalize();
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root)
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("cgit work root must be a local directory");
    }
    this.views =
        new GitRepositoryViewFactory(
            root.resolve("views"), objectStore, namespace.defaultRef());
    this.artifactDirectory = root.resolve("artifacts");
    Files.createDirectories(artifactDirectory);
  }

  @Override
  public Artifact render(RefGenerationSnapshot snapshot, Operation operation)
      throws MaterializationException {
    namespace.validate(operation, snapshot);

    try (GitRepositoryView view = views.create(snapshot)) {
      Path config = writeCgitConfig(view.path());
      Process process = start(view.path(), config, query(operation));
      try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<CgiOutput> stdout =
            tasks.submit(() -> readOutput(process.getInputStream()));
        Future<String> stderr =
            tasks.submit(() -> readBounded(process.getErrorStream(), maxStderrBytes));

        CgiOutput output;
        String error;
        try {
          output = stdout.get();
          int exit = process.waitFor();
          error = stderr.get();
          if (exit != 0) {
            output.close();
            throw new MaterializationException(
                "cgit exited with status " + exit + boundedSuffix(error));
          }
        } catch (InterruptedException exception) {
          terminate(process);
          stdout.cancel(true);
          stderr.cancel(true);
          Thread.currentThread().interrupt();
          throw new MaterializationException("cgit rendering was interrupted", exception);
        } catch (ExecutionException exception) {
          terminate(process);
          stdout.cancel(true);
          stderr.cancel(true);
          throw outputFailure(exception);
        }

        return output.artifact();
      } finally {
        if (process.isAlive()) {
          terminate(process);
        }
      }
    } catch (IOException exception) {
      throw new MaterializationException("cgit rendering failed", exception);
    }
  }

  private Process start(Path namespace.repository()View, Path config, String query) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(namespace.repository()View.toFile());
    Map<String, String> environment = builder.environment();
    String systemRoot = environment.get("SystemRoot");
    String windowsDirectory = environment.get("WINDIR");
    environment.clear();
    preserve(environment, "SystemRoot", systemRoot);
    preserve(environment, "WINDIR", windowsDirectory);
    environment.put("CGIT_CONFIG", config.toString());
    environment.put("QUERY_STRING", query);
    environment.put("REQUEST_METHOD", "GET");
    environment.put("PATH_INFO", "");
    environment.put("SCRIPT_NAME", "");
    environment.put("HTTP_HOST", "localhost");
    environment.put("SERVER_NAME", "localhost");
    environment.put("SERVER_PORT", "80");
    environment.put("HOME", namespace.repository()View.toString());
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    return builder.start();
  }

  private Path writeCgitConfig(Path view) throws IOException {
    Path config = view.resolve("cgitrc");
    String content =
        "cache-size=0\n"
            + "enable-http-clone=0\n"
            + "enable-index-owner=0\n"
            + "repo.url="
            + namespace.repository()
            + "\nrepo.path="
            + view
            + "\n";
    Files.writeString(
        config,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    return config;
  }

  private CgiOutput readOutput(InputStream processOutput) throws IOException {
    Path body = Files.createTempFile(artifactDirectory, "cgit-", ".body");
    boolean completed = false;
    try (InputStream input = new BufferedInputStream(processOutput);
        OutputStream output =
            Files.newOutputStream(
                body, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      HeaderBlock headers = readHeaders(input);
      long length = copyBounded(input, output);
      completed = true;
      return new CgiOutput(
          new Artifact(
              headers.statusCode(),
              length,
              headers.metadata(),
              new TemporaryBody(body)));
    } finally {
      if (!completed) {
        Files.deleteIfExists(body);
      }
    }
  }

  private HeaderBlock readHeaders(InputStream input) throws IOException {
    int total = 0;
    int status = 200;
    boolean statusSeen = false;
    Map<String, String> metadata = new LinkedHashMap<>();

    while (true) {
      byte[] line = readHeaderLine(input, maxHeaderBytes - total);
      total += line.length + 1;
      if (total > maxHeaderBytes) {
        throw new IOException("cgit response headers exceed configured limit");
      }
      String value = new String(line, StandardCharsets.ISO_8859_1);
      if (value.endsWith("\r")) {
        value = value.substring(0, value.length() - 1);
      }
      if (value.isEmpty()) {
        return new HeaderBlock(status, Map.copyOf(metadata));
      }

      int separator = value.indexOf(':');
      if (separator <= 0) {
        throw new IOException("cgit emitted a malformed CGI header");
      }
      String name = value.substring(0, separator).trim();
      String content = value.substring(separator + 1).trim();
      if (name.equalsIgnoreCase("Status")) {
        if (statusSeen) {
          throw new IOException("cgit emitted duplicate Status headers");
        }
        statusSeen = true;
        status = parseStatus(content);
        continue;
      }

      String normalized = name.toLowerCase(Locale.ROOT);
      String canonical =
          switch (normalized) {
            case "content-type" -> "Content-Type";
            case "content-disposition" -> "Content-Disposition";
            case "location" -> "Location";
            default -> null;
          };
      if (canonical != null && metadata.putIfAbsent(canonical, content) != null) {
        throw new IOException("cgit emitted duplicate reusable response metadata");
      }
    }
  }

  private static byte[] readHeaderLine(InputStream input, int remaining) throws IOException {
    if (remaining <= 0) {
      throw new IOException("cgit response headers exceed configured limit");
    }
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    while (line.size() < remaining) {
      int value = input.read();
      if (value < 0) {
        throw new IOException("cgit response ended before CGI headers completed");
      }
      if (value == '\n') {
        return line.toByteArray();
      }
      line.write(value);
    }
    throw new IOException("cgit response headers exceed configured limit");
  }

  private long copyBounded(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[16 * 1024];
    long written = 0;
    while (true) {
      int count = input.read(buffer);
      if (count < 0) {
        return written;
      }
      if (count == 0) {
        continue;
      }
      if (written > maxArtifactBytes - count) {
        throw new IOException("cgit response body exceeds configured limit");
      }
      output.write(buffer, 0, count);
      written += count;
    }
  }

  private void terminate(Process process) {
    List<ProcessHandle> descendants = process.toHandle().descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    waitForExit(process, terminationGrace);

    descendants.stream()
        .filter(ProcessHandle::isAlive)
        .forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) {
      process.destroyForcibly();
    }
    waitForExit(process, terminationGrace);
  }

  private static void waitForExit(Process process, Duration duration) {
    try {
      process.waitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static int parseStatus(String value) throws IOException {
    int separator = value.indexOf(' ');
    String code = separator < 0 ? value : value.substring(0, separator);
    try {
      int status = Integer.parseInt(code);
      if (status < 200 || status > 599) {
        throw new IOException("cgit emitted an invalid status code");
      }
      return status;
    } catch (NumberFormatException exception) {
      throw new IOException("cgit emitted an invalid status code", exception);
    }
  }

  private String query(Operation operation) {
    List<Map.Entry<String, String>> values = new ArrayList<>();
    values.add(Map.entry("r", single(operation, "namespace.repository()")));
    values.add(Map.entry("p", single(operation, "page")));
    optional(operation, "path").ifPresent(value -> values.add(Map.entry("path", value)));
    for (String dimension : QUERY_DIMENSIONS) {
      optional(operation, dimension)
          .ifPresent(value -> values.add(Map.entry(queryName(dimension), value)));
    }

    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> value : values) {
      if (!query.isEmpty()) {
        query.append('&');
      }
      query.append(encode(value.getKey())).append('=').append(encode(value.getValue()));
    }
    return query.toString();
  }

  private void validateOperation(Operation operation) {
    Objects.requireNonNull(operation, "operation");
    if (!"cgit-render".equals(operation.type())) {
      throw new IllegalArgumentException("unexpected operation type " + operation.type());
    }
    if (!namespace.repository().equals(single(operation, "namespace.repository()"))) {
      throw new IllegalArgumentException("operation targets a different namespace.repository()");
    }
    single(operation, "generation");
    single(operation, "refSnapshot");
    single(operation, "defaultRef");
    single(operation, "page");
  }

  private static String queryName(String dimension) {
    return switch (dimension) {
      case "head" -> "h";
      case "oid" -> "id";
      case "oid2" -> "id2";
      case "grep" -> "qt";
      case "search" -> "q";
      case "offset" -> "ofs";
      case "showMessage" -> "showmsg";
      case "period" -> "period";
      case "diffType" -> "dt";
      case "showAll" -> "all";
      case "context" -> "context";
      case "ignoreWhitespace" -> "ignorews";
      case "follow" -> "follow";
      default -> throw new IllegalArgumentException("unsupported cgit query dimension " + dimension);
    };
  }

  private static Optional<String> optional(Operation operation, String dimension) {
    List<String> values = operation.dimensions().get(dimension);
    if (values == null) {
      return Optional.empty();
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException(
          dimension + " must contain exactly one value");
    }
    return Optional.of(values.getFirst());
  }

  private static String single(Operation operation, String dimension) {
    return optional(operation, dimension)
        .filter(value -> !value.isBlank())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    dimension + " must contain exactly one non-blank value"));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String readBounded(InputStream input, int maximum) throws IOException {
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      int remaining = Math.max(0, maximum - capture.size());
      if (remaining > 0) {
        capture.write(buffer, 0, Math.min(read, remaining));
      }
    }
    return capture.toString(StandardCharsets.UTF_8);
  }

  private static MaterializationException outputFailure(ExecutionException exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof IOException io) {
      return new MaterializationException("invalid cgit CGI response", io);
    }
    if (cause instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return new MaterializationException("cgit rendering failed", cause);
  }

  private static String boundedSuffix(String stderr) {
    return stderr.isBlank() ? "" : ": " + stderr.strip();
  }

  private static List<String> validatedCommand(List<String> command) {
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("cgit command must not be empty");
    }
    List<String> copy = List.copyOf(command);
    for (String value : copy) {
      if (value.isBlank() || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("invalid cgit command");
      }
    }
    return copy;
  }

  private static void preserve(
      Map<String, String> environment, String name, String value) {
    if (value != null) {
      environment.put(name, value);
    }
  }

  private record HeaderBlock(int statusCode, Map<String, String> metadata) {}

  private record CgiOutput(Artifact artifact) implements AutoCloseable {
    private CgiOutput {
      Objects.requireNonNull(artifact, "artifact");
    }

    @Override
    public void close() throws IOException {
      artifact.body().close();
    }
  }

  private static final class TemporaryBody implements ArtifactBody {
    private final Path path;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TemporaryBody(Path path) {
      this.path = path;
    }

    @Override
    public InputStream openStream() throws IOException {
      if (closed.get()) {
        throw new IOException("cgit artifact body is closed");
      }
      return Files.newInputStream(path);
    }

    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true)) {
        Files.deleteIfExists(path);
      }
    }
  }
}
