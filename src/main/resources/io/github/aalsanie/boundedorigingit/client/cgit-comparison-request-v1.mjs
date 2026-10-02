import {GitComparisonError} from "./cgit-compare-v1.mjs";

const KNOWN_PARAMETERS =
    new Set([
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
      "url"
    ]);

export function comparisonRequestFromCgitUrl(value, repository) {
  const repositoryName = normalizeRepository(repository);
  let url;
  try {
    url = new URL(value, "https://bounded-origin.invalid");
  } catch {
    fail("INVALID_REQUEST", "comparison URL is invalid");
  }

  for (const name of url.searchParams.keys()) {
    if (!KNOWN_PARAMETERS.has(name)) {
      fail("INVALID_REQUEST", "comparison URL contains an unknown query parameter");
    }
  }

  const path = routePath(url, repositoryName);
  const newOid = parameter(url.searchParams, "id", true);
  const oldOid = parameter(url.searchParams, "id2", true);
  if (newOid.length !== oldOid.length || (newOid.length !== 40 && newOid.length !== 64)) {
    fail("INVALID_OID", "comparison operands must use one full object-id width");
  }

  const contextValue = parameter(url.searchParams, "context", false);
  const parsedContext =
      contextValue == null ? 3 : integerValue(contextValue, 0, 40, "context");
  const follow = booleanParameter(url.searchParams, "follow");
  if (follow) {
    fail("UNSUPPORTED_SEMANTICS", "follow/rename traversal is not supported");
  }

  const diffType = parameter(url.searchParams, "dt", false);
  const sideBySide = parameter(url.searchParams, "ss", false);
  if (diffType != null && sideBySide != null) {
    fail("INVALID_REQUEST", "comparison URL contains conflicting diff modes");
  }

  let mode = "unified";
  if (diffType != null) {
    const value = integerValue(diffType, 0, 2, "dt");
    mode = value === 0 ? "unified" : value === 1 ? "side-by-side" : "stat";
  } else if (sideBySide != null) {
    mode = integerValue(sideBySide, 0, 1, "ss") === 1 ? "side-by-side" : "unified";
  }

  return {
    oldOid: normalizeOid(oldOid),
    newOid: normalizeOid(newOid),
    hashAlgorithm: newOid.length === 40 ? "SHA-1" : "SHA-256",
    path: normalizePath(path),
    context: parsedContext === 0 ? 3 : parsedContext,
    ignoreWhitespace: booleanParameter(url.searchParams, "ignorews"),
    mode,
    follow: false
  };
}

function routePath(url, repository) {
  const params = url.searchParams;
  const urlRoute = parameter(params, "url", false);
  const legacyRepository = parameter(params, "r", false);
  const legacyPage = parameter(params, "p", false);
  const legacyPath = parameter(params, "path", false);
  const pathname = decodePathname(url.pathname);

  if (urlRoute != null) {
    if ((pathname !== "" && pathname !== "/")
        || legacyRepository != null
        || legacyPage != null
        || legacyPath != null) {
      fail("INVALID_REQUEST", "comparison URL mixes cgit routing forms");
    }
    return pathFromRoute(urlRoute, repository);
  }

  if (legacyRepository != null) {
    if (pathname !== "" && pathname !== "/") {
      fail("INVALID_REQUEST", "comparison URL mixes cgit routing forms");
    }
    if (legacyRepository !== repository || legacyPage !== "diff") {
      fail("INVALID_REQUEST", "comparison URL does not target the selected repository diff");
    }
    return legacyPath ?? "";
  }

  if (legacyPage != null || legacyPath != null) {
    fail("INVALID_REQUEST", "comparison URL has incomplete legacy cgit routing");
  }
  return pathFromRoute(pathname.startsWith("/") ? pathname.slice(1) : pathname, repository);
}

function pathFromRoute(route, repository) {
  const normalized = route.startsWith("/") ? route.slice(1) : route;
  const prefix = repository + "/diff";
  if (normalized === prefix) {
    return "";
  }
  if (!normalized.startsWith(prefix + "/")) {
    fail("INVALID_REQUEST", "comparison URL does not target the selected repository diff");
  }
  return normalized.slice(prefix.length + 1);
}

function decodePathname(pathname) {
  if (/%(?:2f|5c|00)/i.test(pathname)) {
    fail("INVALID_PATH", "encoded path separators and NUL are not supported");
  }
  try {
    return decodeURIComponent(pathname);
  } catch {
    fail("INVALID_PATH", "comparison URL path encoding is invalid");
  }
}

function parameter(params, name, required) {
  const values = params.getAll(name);
  if (values.length > 1) {
    fail("INVALID_REQUEST", "comparison URL contains duplicate " + name);
  }
  if (values.length === 0) {
    if (required) {
      fail("INVALID_REQUEST", "comparison URL is missing " + name);
    }
    return null;
  }
  if (required && values[0] === "") {
    fail("INVALID_REQUEST", "comparison URL contains an empty " + name);
  }
  return values[0];
}

function booleanParameter(params, name) {
  const value = parameter(params, name, false);
  if (value == null || value === "0") {
    return false;
  }
  if (value === "1") {
    return true;
  }
  fail("INVALID_REQUEST", name + " must be 0 or 1");
}

function integerValue(value, minimum, maximum, name) {
  if (!/^[0-9]{1,10}$/.test(value)) {
    fail("INVALID_REQUEST", name + " must be a non-negative integer");
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    fail("INVALID_REQUEST", name + " is outside the supported range");
  }
  return parsed;
}

function normalizeOid(value) {
  if (!/^[0-9a-fA-F]+$/.test(value)) {
    fail("INVALID_OID", "comparison operand is not hexadecimal");
  }
  return value.toLowerCase();
}

function normalizePath(value) {
  let path = value;
  while (path.endsWith("/")) {
    path = path.slice(0, -1);
  }
  if (path.startsWith("/") || path.includes("\\") || path.includes("\0")) {
    fail("INVALID_PATH", "path must be repository-relative");
  }
  if (path) {
    for (const segment of path.split("/")) {
      if (segment === "" || segment === "." || segment === "..") {
        fail("INVALID_PATH", "path must not contain empty or dot segments");
      }
    }
  }
  return path;
}

function normalizeRepository(repository) {
  if (typeof repository !== "string"
      || repository.length === 0
      || repository.length > 2048
      || repository.startsWith("/")
      || repository.endsWith("/")
      || repository.includes("?")
      || repository.includes("&")
      || repository.includes("=")) {
    fail("INVALID_REQUEST", "invalid repository");
  }
  for (let index = 0; index < repository.length; index++) {
    const code = repository.charCodeAt(index);
    if (code < 0x21 || code === 0x7f) {
      fail("INVALID_REQUEST", "invalid repository");
    }
  }
  return repository;
}

function fail(code, message) {
  throw new GitComparisonError(code, message);
}
