package io.github.aalsanie.boundedorigingit.cgit;

import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.PolicyMatcher;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class CgitSemanticClassifier implements PolicyMatcher {
  private static final String REQUEST_TYPE = "http.request";
  private static final String OPERATION_TYPE = "cgit-render";
  private static final int MAX_PATH_LENGTH = 4096;
  private static final int MAX_QUERY_LENGTH = 8192;
  private static final int MAX_QUERY_PARAMETERS = 32;
  private static final int MAX_COMPONENT_LENGTH = 2048;

  private static final Set<String> SUPPORTED_PAGES =
      Set.of(
          "atom",
          "blame",
          "blob",
          "commit",
          "diff",
          "log",
          "patch",
          "plain",
          "rawdiff",
          "refs",
          "snapshot",
          "stats",
          "summary",
          "tag",
          "tree");

  private static final Set<String> KNOWN_QUERY_PARAMETERS =
      Set.of(
          "all",
          "context",
          "dt",
          "follow",
          "h",
          "id",
          "id2",
          "ignorews",
          "name",
          "ofs",
          "p",
          "path",
          "period",
          "q",
          "qt",
          "r",
          "s",
          "showmsg",
          "ss",
          "url");

  private static final List<String> IDENTITY_DIMENSIONS =
      List.of(
          "repository",
          "page",
          "path",
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

  private static final Set<String> SEARCH_MODES = Set.of("grep", "author", "committer", "range");

  private static final Canonicalizer BASE_CANONICALIZER =
      Canonicalizers.byDimensions(IDENTITY_DIMENSIONS);
  private static final Canonicalizer CANONICALIZER =
      operation -> {
        Objects.requireNonNull(operation, "operation");
        if (!OPERATION_TYPE.equals(operation.type())) {
          throw new IllegalArgumentException("unexpected operation type " + operation.type());
        }
        return BASE_CANONICALIZER.canonicalize(operation);
      };

  private final List<String> repositories;
  private final Set<String> repositorySet;

  public CgitSemanticClassifier(Collection<String> repositoryUrls) {
    Objects.requireNonNull(repositoryUrls, "repositoryUrls");
    Set<String> unique = new HashSet<>();
    for (String repositoryUrl : repositoryUrls) {
      validateRepository(repositoryUrl);
      if (!unique.add(repositoryUrl)) {
        throw new IllegalArgumentException("duplicate repository " + repositoryUrl);
      }
    }
    if (unique.isEmpty()) {
      throw new IllegalArgumentException("at least one repository is required");
    }
    repositorySet = Set.copyOf(unique);
    repositories =
        unique.stream()
            .sorted(
                Comparator.comparingInt(String::length)
                    .reversed()
                    .thenComparing(Comparator.naturalOrder()))
            .toList();
  }

  @Override
  public Optional<Operation> classify(RequestDescriptor request) {
    Objects.requireNonNull(request, "request");
    if (!REQUEST_TYPE.equals(request.name())) {
      return Optional.empty();
    }
    String method = singleAttribute(request, "method");
    if (!"GET".equals(method)) {
      return Optional.empty();
    }
    String pathInfo = singleAttribute(request, "path");
    String rawQuery = optionalSingleAttribute(request, "query").orElse(null);
    return classifyRequest(pathInfo, rawQuery);
  }

  public Canonicalizer canonicalizer() {
    return CANONICALIZER;
  }

  private Optional<Operation> classifyRequest(String pathInfo, String rawQuery) {
    if (pathInfo.length() > MAX_PATH_LENGTH) {
      return Optional.empty();
    }
    if (rawQuery != null && rawQuery.length() > MAX_QUERY_LENGTH) {
      return Optional.empty();
    }

    Optional<String> normalizedPathInfo = normalizePathInfo(pathInfo);
    if (normalizedPathInfo.isEmpty()) {
      return Optional.empty();
    }

    Optional<Map<String, String>> parsedQuery = parseQuery(rawQuery);
    if (parsedQuery.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> query = new LinkedHashMap<>(parsedQuery.orElseThrow());

    Optional<Route> parsedRoute = parseRoute(normalizedPathInfo.orElseThrow(), query);
    if (parsedRoute.isEmpty()) {
      return Optional.empty();
    }
    Route route = parsedRoute.orElseThrow();
    if (!SUPPORTED_PAGES.contains(route.page())) {
      return Optional.empty();
    }

    Optional<Map<String, List<String>>> dimensions = dimensions(route, query);
    return dimensions.map(values -> new Operation(OPERATION_TYPE, values));
  }

  private Optional<Route> parseRoute(String pathInfo, Map<String, String> query) {
    boolean hasPathRoute = !pathInfo.isBlank() && !"/".equals(pathInfo);
    boolean hasUrl = query.containsKey("url");
    boolean hasLegacyRoute =
        query.containsKey("r") || query.containsKey("p") || query.containsKey("path");

    if (hasUrl) {
      if (hasPathRoute || hasLegacyRoute) {
        return Optional.empty();
      }
      String url = query.remove("url");
      return parseRouteSpec(url);
    }

    if (query.containsKey("r")) {
      if (hasPathRoute) {
        return Optional.empty();
      }
      String repository = query.remove("r");
      boolean hasPage = query.containsKey("p");
      String page = query.remove("p");
      if (hasPage && (page == null || page.isBlank())) {
        return Optional.empty();
      }
      String path = normalizePath(query.remove("path"));
      if (!repositorySet.contains(repository)) {
        return Optional.empty();
      }
      return route(repository, page, path);
    }

    if (query.containsKey("p") || query.containsKey("path")) {
      return Optional.empty();
    }
    if (!hasPathRoute) {
      return Optional.empty();
    }
    return parseRouteSpec(pathInfo);
  }

  private Optional<Route> parseRouteSpec(String routeSpec) {
    if (routeSpec == null || routeSpec.isBlank() || routeSpec.length() > MAX_PATH_LENGTH) {
      return Optional.empty();
    }
    String value = routeSpec.charAt(0) == '/' ? routeSpec.substring(1) : routeSpec;
    if (value.isBlank() || value.charAt(0) == '/' || containsControl(value)) {
      return Optional.empty();
    }

    for (String repository : repositories) {
      if (value.equals(repository)) {
        return Optional.of(new Route(repository, "summary", null));
      }
      String prefix = repository + "/";
      if (!value.startsWith(prefix)) {
        continue;
      }

      String remainder = value.substring(prefix.length());
      if (remainder.isEmpty()) {
        return Optional.of(new Route(repository, "summary", null));
      }

      int slash = remainder.indexOf('/');
      String page = slash < 0 ? remainder : remainder.substring(0, slash);
      String path = slash < 0 ? null : normalizePath(remainder.substring(slash + 1));
      return route(repository, page, path);
    }
    return Optional.empty();
  }

  private Optional<Route> route(String repository, String page, String path) {
    String normalizedPage = page == null || page.isBlank() ? "summary" : page;
    if (containsControl(normalizedPage)
        || normalizedPage.length() > MAX_COMPONENT_LENGTH
        || path != null && (path.length() > MAX_PATH_LENGTH || containsControl(path))) {
      return Optional.empty();
    }
    return Optional.of(new Route(repository, normalizedPage, path));
  }

  private Optional<Map<String, List<String>>> dimensions(
      Route route, Map<String, String> query) {
    Map<String, List<String>> dimensions = new LinkedHashMap<>();
    put(dimensions, "repository", route.repository());
    put(dimensions, "page", route.page());

    switch (route.page()) {
      case "summary" -> {
        if (!addText(dimensions, "head", query.get("h"))) {
          return Optional.empty();
        }
      }
      case "tree", "plain", "blob" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))) {
          return Optional.empty();
        }
      }
      case "blame" -> {
        if (route.path() == null) {
          return Optional.empty();
        }
        put(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))) {
          return Optional.empty();
        }
      }
      case "log" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))
            || !addSearch(dimensions, query)
            || !addOffset(dimensions, query.get("ofs"), 0)
            || !addBoolean(dimensions, "showMessage", query.get("showmsg"))
            || !addBoolean(dimensions, "follow", query.get("follow"))
            || !addBoolean(dimensions, "ignoreWhitespace", query.get("ignorews"))) {
          return Optional.empty();
        }
      }
      case "commit" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))
            || !addDiffOptions(dimensions, query)) {
          return Optional.empty();
        }
      }
      case "diff" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))
            || !addText(dimensions, "oid2", query.get("id2"))
            || !addDiffOptions(dimensions, query)) {
          return Optional.empty();
        }
      }
      case "rawdiff" -> {
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))
            || !addText(dimensions, "oid2", query.get("id2"))) {
          return Optional.empty();
        }
      }
      case "patch" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))
            || !addText(dimensions, "oid2", query.get("id2"))) {
          return Optional.empty();
        }
      }
      case "refs" -> {
        putIfPresent(dimensions, "path", normalizeRefsPath(route.path()));
        if (!addText(dimensions, "head", query.get("h"))) {
          return Optional.empty();
        }
      }
      case "snapshot" -> {
        if (route.path() == null) {
          return Optional.empty();
        }
        put(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))) {
          return Optional.empty();
        }
      }
      case "stats" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addStatsPeriod(dimensions, query.get("period"))
            || !addOffset(dimensions, query.get("ofs"), 10)) {
          return Optional.empty();
        }
      }
      case "atom" -> {
        putIfPresent(dimensions, "path", route.path());
        if (!addText(dimensions, "head", query.get("h"))
            || !addBoolean(dimensions, "showAll", query.get("all"))) {
          return Optional.empty();
        }
      }
      case "tag" -> {
        if (!addText(dimensions, "head", query.get("h"))
            || !addText(dimensions, "oid", query.get("id"))) {
          return Optional.empty();
        }
      }
      default -> {
        return Optional.empty();
      }
    }

    return Optional.of(Map.copyOf(dimensions));
  }

  private static boolean addDiffOptions(
      Map<String, List<String>> dimensions, Map<String, String> query) {
    String dt = query.get("dt");
    String ss = query.get("ss");
    if (dt != null && ss != null) {
      return false;
    }
    if (dt != null) {
      Optional<Integer> value = parseInteger(dt, 0, 2);
      if (value.isEmpty()) {
        return false;
      }
      put(dimensions, "diffType", Integer.toString(value.orElseThrow()));
    } else if (ss != null) {
      Optional<Integer> value = parseInteger(ss, 0, 1);
      if (value.isEmpty()) {
        return false;
      }
      put(dimensions, "diffType", value.orElseThrow() == 0 ? "0" : "1");
    }

    String context = query.get("context");
    if (context != null) {
      Optional<Integer> value = parseInteger(context, 0, 40);
      if (value.isEmpty()) {
        return false;
      }
      int normalized = value.orElseThrow();
      if (normalized != 0 && normalized != 3) {
        put(dimensions, "context", Integer.toString(normalized));
      }
    }

    return addBoolean(dimensions, "ignoreWhitespace", query.get("ignorews"))
        && addBoolean(dimensions, "follow", query.get("follow"));
  }

  private static boolean addSearch(
      Map<String, List<String>> dimensions, Map<String, String> query) {
    String grep = query.get("qt");
    String search = query.get("q");
    if (grep == null && search == null) {
      return true;
    }
    if (grep == null
        || search == null
        || !SEARCH_MODES.contains(grep)
        || search.isBlank()
        || containsControl(search)) {
      return false;
    }
    put(dimensions, "grep", grep);
    put(dimensions, "search", search);
    return true;
  }

  private static boolean addStatsPeriod(
      Map<String, List<String>> dimensions, String value) {
    if (value == null) {
      return true;
    }
    String normalized =
        switch (value) {
          case "w", "week" -> "w";
          case "m", "month" -> "m";
          case "q", "quarter" -> "q";
          case "y", "year" -> "y";
          default -> null;
        };
    if (normalized == null) {
      return false;
    }
    if (!"w".equals(normalized)) {
      put(dimensions, "period", normalized);
    }
    return true;
  }

  private static boolean addOffset(
      Map<String, List<String>> dimensions, String value, int defaultValue) {
    if (value == null) {
      return true;
    }
    Optional<Integer> parsed = parseInteger(value, 0, Integer.MAX_VALUE);
    if (parsed.isEmpty()) {
      return false;
    }
    int normalized = parsed.orElseThrow();
    if (normalized != 0 && normalized != defaultValue) {
      put(dimensions, "offset", Integer.toString(normalized));
    }
    return true;
  }

  private static boolean addBoolean(
      Map<String, List<String>> dimensions, String dimension, String value) {
    if (value == null) {
      return true;
    }
    if ("0".equals(value)) {
      return true;
    }
    if (!"1".equals(value)) {
      return false;
    }
    put(dimensions, dimension, "1");
    return true;
  }

  private static boolean addText(
      Map<String, List<String>> dimensions, String dimension, String value) {
    if (value == null) {
      return true;
    }
    if (value.isBlank() || value.length() > MAX_COMPONENT_LENGTH || containsControl(value)) {
      return false;
    }
    put(dimensions, dimension, value);
    return true;
  }

  private static Optional<Integer> parseInteger(String value, int minimum, int maximum) {
    if (value == null || value.isBlank() || value.length() > 10) {
      return Optional.empty();
    }
    try {
      int parsed = Integer.parseInt(value);
      return parsed < minimum || parsed > maximum ? Optional.empty() : Optional.of(parsed);
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Map<String, String>> parseQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return Optional.of(Map.of());
    }

    String[] parameters = rawQuery.split("&", -1);
    if (parameters.length > MAX_QUERY_PARAMETERS) {
      return Optional.empty();
    }

    Map<String, String> result = new LinkedHashMap<>();
    try {
      for (String parameter : parameters) {
        if (parameter.isEmpty()) {
          return Optional.empty();
        }
        int separator = parameter.indexOf('=');
        String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
        String rawValue = separator < 0 ? "" : parameter.substring(separator + 1);
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);

        if (name.isBlank()
            || name.length() > MAX_COMPONENT_LENGTH
            || value.length() > MAX_COMPONENT_LENGTH
            || containsControl(name)
            || containsControl(value)
            || !KNOWN_QUERY_PARAMETERS.contains(name)
            || result.putIfAbsent(name, value) != null) {
          return Optional.empty();
        }
      }
    } catch (IllegalArgumentException invalidEncoding) {
      return Optional.empty();
    }
    return Optional.of(Map.copyOf(result));
  }

  private static Optional<String> normalizePathInfo(String path) {
    if (path.isBlank() || "*".equals(path)) {
      return Optional.of(path);
    }

    StringBuilder normalized = new StringBuilder(path.length());
    for (int index = 0; index < path.length(); index++) {
      char character = path.charAt(index);
      if (character != '%') {
        if (character < 0x21 || character > 0x7e || character == '\\' || character == '#') {
          return Optional.empty();
        }
        normalized.append(character);
        continue;
      }

      if (index + 2 >= path.length()) {
        return Optional.empty();
      }
      int high = Character.digit(path.charAt(index + 1), 16);
      int low = Character.digit(path.charAt(index + 2), 16);
      if (high < 0 || low < 0) {
        return Optional.empty();
      }
      char decoded = (char) ((high << 4) | low);
      if (decoded == '/' || decoded == '\\' || decoded == 0) {
        return Optional.empty();
      }
      if (isUnreserved(decoded)) {
        normalized.append(decoded);
      } else {
        normalized.append('%');
        normalized.append(Character.toUpperCase(path.charAt(index + 1)));
        normalized.append(Character.toUpperCase(path.charAt(index + 2)));
      }
      index += 2;
    }

    for (String segment : normalized.toString().split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return Optional.empty();
      }
    }
    return Optional.of(normalized.toString());
  }

  private static boolean isUnreserved(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == '-'
        || character == '.'
        || character == '_'
        || character == '~';
  }

  private static String normalizePath(String path) {
    if (path == null) {
      return null;
    }
    int end = path.length();
    while (end > 0 && path.charAt(end - 1) == '/') {
      end--;
    }
    return end == 0 ? null : path.substring(0, end);
  }

  private static String normalizeRefsPath(String path) {
    if (path == null) {
      return null;
    }
    if (path.startsWith("heads")) {
      return "heads";
    }
    if (path.startsWith("tags")) {
      return "tags";
    }
    return null;
  }

  private static String singleAttribute(RequestDescriptor request, String name) {
    List<String> values = request.attributes().get(name);
    if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
      throw new IllegalArgumentException(name + " must contain exactly one non-blank value");
    }
    return values.getFirst();
  }

  private static Optional<String> optionalSingleAttribute(RequestDescriptor request, String name) {
    List<String> values = request.attributes().get(name);
    if (values == null) {
      return Optional.empty();
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException(name + " must contain exactly one value");
    }
    return Optional.of(values.getFirst());
  }

  private static void validateRepository(String repositoryUrl) {
    Objects.requireNonNull(repositoryUrl, "repositoryUrl");
    if (repositoryUrl.isBlank()
        || repositoryUrl.length() > MAX_COMPONENT_LENGTH
        || repositoryUrl.startsWith("/")
        || repositoryUrl.endsWith("/")
        || repositoryUrl.indexOf('?') >= 0
        || repositoryUrl.indexOf('&') >= 0
        || repositoryUrl.indexOf('=') >= 0
        || containsControl(repositoryUrl)) {
      throw new IllegalArgumentException("invalid repository " + repositoryUrl);
    }
  }

  private static boolean containsControl(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current < 0x20 || current == 0x7f) {
        return true;
      }
    }
    return false;
  }

  private static void put(
      Map<String, List<String>> dimensions, String dimension, String value) {
    dimensions.put(dimension, List.of(value));
  }

  private static void putIfPresent(
      Map<String, List<String>> dimensions, String dimension, String value) {
    if (value != null) {
      put(dimensions, dimension, value);
    }
  }

  private record Route(String repository, String page, String path) {}
}
