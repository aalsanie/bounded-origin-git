export const CLIENT_COMPARISON_TYPE = "bounded-origin-git.compare";
export const CLIENT_COMPARISON_VERSION = "1";

const encoder = new TextEncoder();
const fatalDecoder = new TextDecoder("utf-8", {fatal: true, ignoreBOM: true});

const HARD_LIMITS = Object.freeze({
  maxObjects: 8192,
  maxObjectBytes: 16 * 1024 * 1024,
  maxTotalObjectBytes: 64 * 1024 * 1024,
  maxTreeEntries: 100000,
  maxFiles: 10000,
  maxBlobBytes: 8 * 1024 * 1024,
  maxLinesPerBlob: 50000,
  maxTotalLines: 100000,
  maxDiffCells: 2000000,
  maxChanges: 10000,
  maxOutputRows: 100000,
  maxPathBytes: 4096,
  maxTagDepth: 8
});

const DEFAULT_LIMITS = Object.freeze({
  maxObjects: 4096,
  maxObjectBytes: 8 * 1024 * 1024,
  maxTotalObjectBytes: 32 * 1024 * 1024,
  maxTreeEntries: 50000,
  maxFiles: 4096,
  maxBlobBytes: 4 * 1024 * 1024,
  maxLinesPerBlob: 20000,
  maxTotalLines: 50000,
  maxDiffCells: 1000000,
  maxChanges: 4096,
  maxOutputRows: 50000,
  maxPathBytes: 4096,
  maxTagDepth: 4
});

export class GitComparisonError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "GitComparisonError";
    this.code = code;
  }
}

export async function compareGit(options) {
  const request = normalizeRequest(options);
  const budget = new Budget(request.limits);
  const context = {
    readObject: request.readObject,
    signal: request.signal,
    algorithm: request.algorithm,
    hashBytes: request.hashBytes,
    oidLength: request.oidLength,
    budget,
    cache: new Map()
  };

  checkAborted(request.signal);

  const oldTree = await resolveCommitTree(request.oldOid, context);
  const newTree = await resolveCommitTree(request.newOid, context);
  if (oldTree === newTree) {
    return result(request, [], budget);
  }

  const oldFiles = new Map();
  const newFiles = new Map();
  await collectChangedTrees(oldTree, newTree, "", request.path, oldFiles, newFiles, context);

  const paths = [...new Set([...oldFiles.keys(), ...newFiles.keys()])];
  paths.sort(compareUtf8);

  const changes = [];
  for (const path of paths) {
    checkAborted(request.signal);
    const oldEntry = oldFiles.get(path);
    const newEntry = newFiles.get(path);
    if (oldEntry && newEntry && oldEntry.oid === newEntry.oid && oldEntry.mode === newEntry.mode) {
      continue;
    }
    budget.change();

    const change = await compareEntry(path, oldEntry, newEntry, request, context);
    if (change) {
      changes.push(change);
    }
  }

  return result(request, changes, budget);
}

function normalizeRequest(options) {
  if (!options || typeof options !== "object") {
    fail("INVALID_REQUEST", "comparison request must be an object");
  }
  if (typeof options.readObject !== "function") {
    fail("INVALID_REQUEST", "readObject must be a function");
  }

  const algorithm = normalizeAlgorithm(options.hashAlgorithm ?? "SHA-1");
  const oidLength = algorithm === "SHA-1" ? 40 : 64;
  const hashBytes = oidLength / 2;
  const oldOid = normalizeOid(options.oldOid, oidLength, "oldOid");
  const newOid = normalizeOid(options.newOid, oidLength, "newOid");
  const path = normalizePath(options.path ?? "");
  const limits = normalizeLimits(options.limits ?? {});
  if (encoder.encode(path).length > limits.maxPathBytes) {
    fail("LIMIT_EXCEEDED", "requested path exceeds configured byte limit");
  }
  const context = integer(options.context ?? 3, 0, 40, "context");
  const ignoreWhitespace = boolean(options.ignoreWhitespace ?? false, "ignoreWhitespace");
  const mode = normalizeMode(options.mode ?? "unified");
  const follow = boolean(options.follow ?? false, "follow");
  if (follow) {
    fail("UNSUPPORTED_SEMANTICS", "follow/rename traversal is not supported");
  }

  if (options.signal != null
      && (typeof options.signal !== "object"
          || typeof options.signal.aborted !== "boolean")) {
    fail("INVALID_REQUEST", "signal must be an AbortSignal");
  }

  return {
    oldOid,
    newOid,
    path,
    context,
    ignoreWhitespace,
    mode,
    algorithm,
    oidLength,
    hashBytes,
    readObject: options.readObject,
    signal: options.signal ?? null,
    limits
  };
}

function normalizeLimits(values) {
  if (!values || typeof values !== "object") {
    fail("INVALID_LIMIT", "limits must be an object");
  }
  for (const name of Object.keys(values)) {
    if (!Object.hasOwn(HARD_LIMITS, name)) {
      fail("INVALID_LIMIT", "unknown comparison limit " + name);
    }
  }
  const result = {};
  for (const [name, hardMaximum] of Object.entries(HARD_LIMITS)) {
    result[name] = integer(
        values[name] ?? DEFAULT_LIMITS[name],
        1,
        hardMaximum,
        name);
  }
  return Object.freeze(result);
}

function normalizeAlgorithm(value) {
  if (typeof value !== "string") {
    fail("UNSUPPORTED_HASH", "hashAlgorithm must be a string");
  }
  const normalized = value.toUpperCase().replaceAll("_", "-");
  if (normalized === "SHA1") {
    return "SHA-1";
  }
  if (normalized === "SHA256") {
    return "SHA-256";
  }
  if (normalized === "SHA-1" || normalized === "SHA-256") {
    return normalized;
  }
  fail("UNSUPPORTED_HASH", "hashAlgorithm must be SHA-1 or SHA-256");
}

function normalizeMode(value) {
  if (value === "unified" || value === "side-by-side" || value === "stat") {
    return value;
  }
  fail("UNSUPPORTED_SEMANTICS", "mode must be unified, side-by-side, or stat");
}

function normalizeOid(value, length, label) {
  if (typeof value !== "string" || value.length !== length || !/^[0-9a-fA-F]+$/.test(value)) {
    fail("INVALID_OID", `${label} must be a full ${length}-character object id`);
  }
  return value.toLowerCase();
}

function normalizePath(value) {
  if (typeof value !== "string") {
    fail("INVALID_PATH", "path must be a string");
  }
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

async function resolveCommitTree(startOid, context) {
  let oid = startOid;
  const seen = new Set();
  for (let depth = 0; depth <= context.budget.limits.maxTagDepth; depth++) {
    if (seen.has(oid)) {
      fail("INVALID_GIT_OBJECT", "tag chain contains a cycle");
    }
    seen.add(oid);
    const object = await readVerified(oid, null, context);
    if (object.type === "commit") {
      return commitTreeOid(object.data, context.oidLength);
    }
    if (object.type !== "tag") {
      fail("UNSUPPORTED_OBJECT", "comparison operands must resolve to commits");
    }
    if (depth === context.budget.limits.maxTagDepth) {
      fail("LIMIT_EXCEEDED", "annotated tag chain exceeds configured depth");
    }
    const targetType = asciiHeader(object.data, "type");
    if (targetType !== "commit" && targetType !== "tag") {
      fail("UNSUPPORTED_OBJECT", "annotated tag does not target a commit");
    }
    oid = normalizeOid(asciiHeader(object.data, "object"), context.oidLength, "tag object");
  }
  fail("LIMIT_EXCEEDED", "annotated tag chain exceeds configured depth");
}

function commitTreeOid(bytes, oidLength) {
  return normalizeOid(asciiHeader(bytes, "tree"), oidLength, "commit tree");
}

function asciiHeader(bytes, name) {
  const prefix = encoder.encode(name + " ");
  let start = 0;
  while (start < bytes.length) {
    let end = start;
    while (end < bytes.length && bytes[end] !== 10) {
      end++;
    }
    if (end === start) {
      break;
    }
    if (startsWith(bytes, start, end, prefix)) {
      const value = bytes.slice(start + prefix.length, end);
      for (const byte of value) {
        if (byte < 0x21 || byte > 0x7e) {
          fail("INVALID_GIT_OBJECT", `invalid ${name} header`);
        }
      }
      return new TextDecoder("ascii").decode(value);
    }
    start = end + 1;
  }
  fail("INVALID_GIT_OBJECT", `missing ${name} header`);
}

function startsWith(bytes, start, end, prefix) {
  if (end - start < prefix.length) {
    return false;
  }
  for (let index = 0; index < prefix.length; index++) {
    if (bytes[start + index] !== prefix[index]) {
      return false;
    }
  }
  return true;
}

async function collectChangedTrees(oldOid, newOid, parent, filter, oldFiles, newFiles, context) {
  checkAborted(context.signal);
  if (oldOid === newOid) {
    return;
  }
  const oldEntries = await treeEntries(oldOid, context);
  const newEntries = await treeEntries(newOid, context);
  const names = new Set([...oldEntries.keys(), ...newEntries.keys()]);
  for (const name of names) {
    const oldEntry = oldEntries.get(name);
    const newEntry = newEntries.get(name);
    if (oldEntry && newEntry && oldEntry.oid === newEntry.oid && oldEntry.mode === newEntry.mode) {
      continue;
    }
    const path = parent ? parent + "/" + name : name;
    if (encoder.encode(path).length > context.budget.limits.maxPathBytes) {
      fail("LIMIT_EXCEEDED", "Git path exceeds configured byte limit");
    }

    const oldTree = oldEntry?.kind === "tree" ? oldEntry.oid : null;
    const newTree = newEntry?.kind === "tree" ? newEntry.oid : null;
    if (oldTree || newTree) {
      if (treeRelevant(path, filter)) {
        await collectChangedTrees(oldTree, newTree, path, filter, oldFiles, newFiles, context);
      }
    }
    if (fileRelevant(path, filter)) {
      rememberFile(path, oldEntry, oldFiles, context);
      rememberFile(path, newEntry, newFiles, context);
    }
  }
}

async function treeEntries(oid, context) {
  if (oid == null) {
    return new Map();
  }
  const object = await readVerified(oid, "tree", context);
  return new Map(parseTree(object.data, context).map(entry => [entry.name, entry]));
}

function rememberFile(path, entry, files, context) {
  if (!entry || entry.kind === "tree") {
    return;
  }
  context.budget.file();
  if (files.has(path)) {
    fail("INVALID_GIT_OBJECT", "tree contains duplicate repository path");
  }
  files.set(path, entry);
}

function parseTree(bytes, context) {
  const entries = [];
  let offset = 0;
  const seenNames = new Set();

  while (offset < bytes.length) {
    context.budget.treeEntry();

    const modeEnd = indexOf(bytes, 0x20, offset);
    if (modeEnd < 0) {
      fail("INVALID_GIT_OBJECT", "tree entry is missing mode separator");
    }
    const nameEnd = indexOf(bytes, 0x00, modeEnd + 1);
    if (nameEnd < 0) {
      fail("INVALID_GIT_OBJECT", "tree entry is missing name terminator");
    }
    const oidStart = nameEnd + 1;
    const oidEnd = oidStart + context.hashBytes;
    if (oidEnd > bytes.length) {
      fail("INVALID_GIT_OBJECT", "tree entry object id is truncated");
    }

    const mode = ascii(bytes.slice(offset, modeEnd), "tree mode");
    const nameBytes = bytes.slice(modeEnd + 1, nameEnd);
    if (nameBytes.length === 0) {
      fail("INVALID_GIT_OBJECT", "tree entry name must not be empty");
    }

    let name;
    try {
      name = fatalDecoder.decode(nameBytes);
    } catch {
      fail("UNSUPPORTED_PATH", "tree entry name is not valid UTF-8");
    }
    if (name === "." || name === ".." || name.includes("/") || name.includes("\0")) {
      fail("INVALID_GIT_OBJECT", "tree entry name is invalid");
    }
    if (seenNames.has(name)) {
      fail("INVALID_GIT_OBJECT", "tree contains duplicate entry names");
    }
    seenNames.add(name);

    entries.push({
      name,
      mode: normalizeGitMode(mode),
      kind: kindForMode(mode),
      oid: hex(bytes.slice(oidStart, oidEnd))
    });
    offset = oidEnd;
  }
  return entries;
}

function normalizeGitMode(mode) {
  if (mode === "40000" || mode === "040000") {
    return "040000";
  }
  if (mode === "100644"
      || mode === "100755"
      || mode === "120000"
      || mode === "160000") {
    return mode;
  }
  fail("UNSUPPORTED_OBJECT", `unsupported Git tree mode ${mode}`);
}

function kindForMode(mode) {
  const normalized = normalizeGitMode(mode);
  if (normalized === "040000") {
    return "tree";
  }
  if (normalized === "160000") {
    return "gitlink";
  }
  if (normalized === "120000") {
    return "symlink";
  }
  return "file";
}

function treeRelevant(path, filter) {
  return !filter
      || path === filter
      || filter.startsWith(path + "/")
      || path.startsWith(filter + "/");
}

function fileRelevant(path, filter) {
  return !filter || path === filter || path.startsWith(filter + "/");
}

async function compareEntry(path, oldEntry, newEntry, request, context) {
  const status =
      oldEntry == null ? "added" : newEntry == null ? "deleted" : "modified";
  const oldMode = oldEntry?.mode ?? null;
  const newMode = newEntry?.mode ?? null;
  const modeChanged = oldEntry != null && newEntry != null && oldMode !== newMode;

  if (oldEntry?.kind === "gitlink" || newEntry?.kind === "gitlink") {
    return {
      path,
      status,
      oldMode,
      newMode,
      oldOid: oldEntry?.oid ?? null,
      newOid: newEntry?.oid ?? null,
      binary: false,
      gitlink: true,
      additions: 0,
      deletions: 0,
      detail: null
    };
  }

  if (oldEntry && newEntry && oldEntry.oid === newEntry.oid) {
    return modeChanged
        ? {
            path,
            status: "mode-changed",
            oldMode,
            newMode,
            oldOid: oldEntry.oid,
            newOid: newEntry.oid,
            binary: false,
            gitlink: false,
            additions: 0,
            deletions: 0,
            detail: null
          }
        : null;
  }

  const oldBytes = oldEntry ? await readBlob(oldEntry, context) : new Uint8Array();
  const newBytes = newEntry ? await readBlob(newEntry, context) : new Uint8Array();

  if (isBinary(oldBytes) || isBinary(newBytes)) {
    return {
      path,
      status,
      oldMode,
      newMode,
      oldOid: oldEntry?.oid ?? null,
      newOid: newEntry?.oid ?? null,
      binary: true,
      gitlink: false,
      additions: 0,
      deletions: 0,
      detail: null
    };
  }

  const oldText = decodeText(oldBytes, context);
  const newText = decodeText(newBytes, context);
  if (oldText == null || newText == null) {
    return {
      path,
      status,
      oldMode,
      newMode,
      oldOid: oldEntry?.oid ?? null,
      newOid: newEntry?.oid ?? null,
      binary: true,
      gitlink: false,
      additions: 0,
      deletions: 0,
      detail: null
    };
  }

  const diff = lineDiff(oldText, newText, request.ignoreWhitespace, context);
  if (diff.additions === 0 && diff.deletions === 0 && !modeChanged && oldEntry && newEntry) {
    return null;
  }

  const detail =
      request.mode === "stat"
          ? null
          : request.mode === "unified"
              ? unifiedDetail(diff.edits, request.context, context)
              : sideBySideDetail(diff.edits, request.context, context);

  return {
    path,
    status: modeChanged && diff.additions === 0 && diff.deletions === 0
        ? "mode-changed"
        : status,
    oldMode,
    newMode,
    oldOid: oldEntry?.oid ?? null,
    newOid: newEntry?.oid ?? null,
    binary: false,
    gitlink: false,
    additions: diff.additions,
    deletions: diff.deletions,
    oldEndsWithNewline: oldText.endsWithNewline,
    newEndsWithNewline: newText.endsWithNewline,
    detail
  };
}

async function readBlob(entry, context) {
  if (entry.kind !== "file" && entry.kind !== "symlink") {
    fail("UNSUPPORTED_OBJECT", "only blobs and symlinks have byte content");
  }
  const object = await readVerified(entry.oid, "blob", context);
  if (object.data.length > context.budget.limits.maxBlobBytes) {
    fail("LIMIT_EXCEEDED", "blob exceeds configured comparison byte limit");
  }
  return object.data;
}

function decodeText(bytes, context) {
  if (bytes.length === 0) {
    return {lines: [], endsWithNewline: false};
  }

  let text;
  try {
    text = fatalDecoder.decode(bytes);
  } catch {
    return null;
  }

  const endsWithNewline = bytes.length > 0 && bytes[bytes.length - 1] === 0x0a;
  const lines = text.split("\n");
  if (endsWithNewline) {
    lines.pop();
  }
  if (lines.length > context.budget.limits.maxLinesPerBlob) {
    fail("LIMIT_EXCEEDED", "blob exceeds configured line limit");
  }
  context.budget.lines(lines.length);
  return {lines, endsWithNewline};
}

function lineDiff(oldText, newText, ignoreWhitespace, context) {
  const oldLines = oldText.lines;
  const newLines = newText.lines;
  const keys = text => text.lines.map((line, index) => ignoreWhitespace
      ? normalizeWhitespace(line)
      : line + (index < text.lines.length - 1 || text.endsWithNewline ? "\n" : ""));
  const normalizedOld = keys(oldText);
  const normalizedNew = keys(newText);
  let prefix = 0;
  while (prefix < oldLines.length && prefix < newLines.length
      && normalizedOld[prefix] === normalizedNew[prefix]) {
    prefix++;
  }
  let suffix = 0;
  while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
      && normalizedOld[oldLines.length - suffix - 1] === normalizedNew[newLines.length - suffix - 1]) {
    suffix++;
  }
  const oldLength = oldLines.length - prefix - suffix;
  const newLength = newLines.length - prefix - suffix;
  const cells = (oldLength + 1) * (newLength + 1);
  context.budget.diffCells(cells);

  const width = newLength + 1;
  const matrix = new Uint32Array(cells);

  for (let oldIndex = oldLength - 1; oldIndex >= 0; oldIndex--) {
    for (let newIndex = newLength - 1; newIndex >= 0; newIndex--) {
      const index = oldIndex * width + newIndex;
      if (normalizedOld[prefix + oldIndex] === normalizedNew[prefix + newIndex]) {
        matrix[index] = 1 + matrix[(oldIndex + 1) * width + newIndex + 1];
      } else {
        matrix[index] = Math.max(
            matrix[(oldIndex + 1) * width + newIndex],
            matrix[oldIndex * width + newIndex + 1]);
      }
    }
  }

  const edits = [];
  const edit = (kind, oldPosition, newPosition) => {
    const noNewline = kind === "add"
        ? newPosition === newLines.length - 1 && !newText.endsWithNewline
        : oldPosition === oldLines.length - 1 && !oldText.endsWithNewline;
    return {
      kind,
      text: kind === "add" ? newLines[newPosition] : oldLines[oldPosition],
      oldLine: kind === "add" ? null : oldPosition + 1,
      newLine: kind === "delete" ? null : newPosition + 1,
      oldPosition,
      newPosition,
      ...(noNewline ? {noNewline: true} : {})
    };
  };
  for (let index = 0; index < prefix; index++) {
    edits.push(edit("context", index, index));
  }
  let additions = 0;
  let deletions = 0;
  let oldIndex = 0;
  let newIndex = 0;

  while (oldIndex < oldLength || newIndex < newLength) {
    if (oldIndex < oldLength
        && newIndex < newLength
        && normalizedOld[prefix + oldIndex] === normalizedNew[prefix + newIndex]) {
      edits.push(edit("context", prefix + oldIndex, prefix + newIndex));
      oldIndex++;
      newIndex++;
      continue;
    }

    const deleteScore =
        oldIndex < oldLength ? matrix[(oldIndex + 1) * width + newIndex] : -1;
    const addScore =
        newIndex < newLength ? matrix[oldIndex * width + newIndex + 1] : -1;

    if (oldIndex < oldLength && (newIndex >= newLength || deleteScore >= addScore)) {
      edits.push(edit("delete", prefix + oldIndex, prefix + newIndex));
      deletions++;
      oldIndex++;
    } else {
      edits.push(edit("add", prefix + oldIndex, prefix + newIndex));
      additions++;
      newIndex++;
    }
  }

  for (let index = 0; index < suffix; index++) {
    edits.push(edit("context", prefix + oldLength + index, prefix + newLength + index));
  }

  return {edits, additions, deletions};
}

function normalizeWhitespace(value) {
  return value.replace(/[ \t\v\f\r]/g, "");
}

function unifiedDetail(edits, contextLines, context) {
  const regions = changeRegions(edits, contextLines);
  return {
    kind: "unified",
    hunks: regions.map(([start, end]) => {
      const lines = edits.slice(start, end);
      context.budget.output(lines.length);
      const oldCount = lines.filter(line => line.kind !== "add").length;
      const newCount = lines.filter(line => line.kind !== "delete").length;
      return {
        oldStart: edits[start].oldPosition + (oldCount === 0 ? 0 : 1),
        oldCount,
        newStart: edits[start].newPosition + (newCount === 0 ? 0 : 1),
        newCount,
        lines: lines.map(line => ({
          kind: line.kind,
          text: line.text,
          oldLine: line.oldLine,
          newLine: line.newLine,
          ...(line.noNewline ? {noNewline: true} : {})
        }))
      };
    })
  };
}

function sideBySideDetail(edits, contextLines, context) {
  const regions = changeRegions(edits, contextLines);
  const hunks = [];

  for (const [start, end] of regions) {
    const source = edits.slice(start, end);
    const rows = [];
    let index = 0;
    while (index < source.length) {
      if (source[index].kind === "context") {
        const line = source[index];
        rows.push({
          kind: "context",
          left: {line: line.oldLine, text: line.text, ...(line.noNewline ? {noNewline: true} : {})},
          right: {line: line.newLine, text: line.text, ...(line.noNewline ? {noNewline: true} : {})}
        });
        index++;
        continue;
      }

      const deletes = [];
      const adds = [];
      while (index < source.length && source[index].kind !== "context") {
        const line = source[index++];
        if (line.kind === "delete") {
          deletes.push(line);
        } else {
          adds.push(line);
        }
      }
      const count = Math.max(deletes.length, adds.length);
      for (let row = 0; row < count; row++) {
        const left = deletes[row];
        const right = adds[row];
        rows.push({
          kind: "change",
          left: left ? {line: left.oldLine, text: left.text, ...(left.noNewline ? {noNewline: true} : {})} : null,
          right: right ? {line: right.newLine, text: right.text, ...(right.noNewline ? {noNewline: true} : {})} : null
        });
      }
    }
    context.budget.output(rows.length);
    hunks.push({
      oldStart: edits[start]?.oldPosition + 1 ?? 1,
      newStart: edits[start]?.newPosition + 1 ?? 1,
      rows
    });
  }

  return {kind: "side-by-side", hunks};
}

function changeRegions(edits, contextLines) {
  const regions = [];
  for (let index = 0; index < edits.length; index++) {
    if (edits[index].kind === "context") {
      continue;
    }
    const start = Math.max(0, index - contextLines);
    const end = Math.min(edits.length, index + contextLines + 1);
    const previous = regions.at(-1);
    if (previous && start <= previous[1]) {
      previous[1] = Math.max(previous[1], end);
    } else {
      regions.push([start, end]);
    }
  }
  return regions;
}

async function readVerified(oid, expectedType, context) {
  checkAborted(context.signal);
  const cached = context.cache.get(oid);
  if (cached) {
    if (expectedType && cached.type !== expectedType) {
      fail("UNSUPPORTED_OBJECT", `expected ${expectedType} but found ${cached.type}`);
    }
    return cached;
  }

  if (context.budget.objects >= context.budget.limits.maxObjects) {
    fail("LIMIT_EXCEEDED", "Git object read budget exceeded");
  }
  const maxBytes = Math.min(
      context.budget.limits.maxObjectBytes,
      context.budget.limits.maxTotalObjectBytes - context.budget.objectBytes);
  const supplied = await context.readObject(oid, {signal: context.signal, maxBytes});
  checkAborted(context.signal);
  if (!supplied || typeof supplied !== "object") {
    fail("INVALID_OBJECT_READER", "readObject must return an object");
  }
  const type = supplied.type;
  if (type !== "commit" && type !== "tree" && type !== "blob" && type !== "tag") {
    fail("UNSUPPORTED_OBJECT", "readObject returned an unsupported Git object type");
  }
  const bytes = toBytes(supplied.data);
  context.budget.object(bytes.length);
  const data = new Uint8Array(bytes);

  const header = encoder.encode(`${type} ${data.length}\0`);
  const canonical = new Uint8Array(header.length + data.length);
  canonical.set(header);
  canonical.set(data, header.length);
  if (!globalThis.crypto?.subtle) {
    fail("UNSUPPORTED_RUNTIME", "Web Crypto is required for Git object verification");
  }
  const digest = await globalThis.crypto.subtle.digest(context.algorithm, canonical);
  const actual = hex(new Uint8Array(digest));
  if (actual !== oid) {
    fail("OBJECT_ID_MISMATCH", "Git object payload does not match requested object id");
  }

  const object = {type, data};
  if (expectedType && type !== expectedType) {
    fail("UNSUPPORTED_OBJECT", `expected ${expectedType} but found ${type}`);
  }
  context.cache.set(oid, object);
  return object;
}

function toBytes(value) {
  if (value instanceof Uint8Array) {
    return value;
  }
  if (value instanceof ArrayBuffer) {
    return new Uint8Array(value);
  }
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  fail("INVALID_OBJECT_READER", "Git object data must be a byte array");
}

function isBinary(bytes) {
  const limit = Math.min(bytes.length, 8000);
  for (let index = 0; index < limit; index++) {
    if (bytes[index] === 0) {
      return true;
    }
  }
  return false;
}

function result(request, changes, budget) {
  const additions = changes.reduce((sum, change) => sum + change.additions, 0);
  const deletions = changes.reduce((sum, change) => sum + change.deletions, 0);
  return {
    type: CLIENT_COMPARISON_TYPE,
    version: CLIENT_COMPARISON_VERSION,
    oldOid: request.oldOid,
    newOid: request.newOid,
    hashAlgorithm: request.algorithm,
    path: request.path,
    mode: request.mode,
    context: request.context,
    ignoreWhitespace: request.ignoreWhitespace,
    changes,
    summary: {
      filesChanged: changes.length,
      additions,
      deletions,
      binaryFiles: changes.filter(change => change.binary).length,
      gitlinks: changes.filter(change => change.gitlink).length
    },
    consumed: budget.snapshot()
  };
}

function compareUtf8(left, right) {
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  const length = Math.min(a.length, b.length);
  for (let index = 0; index < length; index++) {
    if (a[index] !== b[index]) {
      return a[index] - b[index];
    }
  }
  return a.length - b.length;
}

function ascii(bytes, label) {
  for (const byte of bytes) {
    if (byte < 0x21 || byte > 0x7e) {
      fail("INVALID_GIT_OBJECT", `${label} contains non-ASCII data`);
    }
  }
  return new TextDecoder("ascii").decode(bytes);
}

function indexOf(bytes, value, start) {
  for (let index = start; index < bytes.length; index++) {
    if (bytes[index] === value) {
      return index;
    }
  }
  return -1;
}

function hex(bytes) {
  let value = "";
  for (const byte of bytes) {
    value += byte.toString(16).padStart(2, "0");
  }
  return value;
}

function integer(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    fail("INVALID_LIMIT", `${label} must be an integer between ${minimum} and ${maximum}`);
  }
  return value;
}

function boolean(value, label) {
  if (typeof value !== "boolean") {
    fail("INVALID_REQUEST", `${label} must be boolean`);
  }
  return value;
}

function checkAborted(signal) {
  if (signal?.aborted) {
    fail("ABORTED", "comparison was aborted");
  }
}

function fail(code, message) {
  throw new GitComparisonError(code, message);
}

class Budget {
  constructor(limits) {
    this.limits = limits;
    this.objects = 0;
    this.objectBytes = 0;
    this.treeEntries = 0;
    this.files = 0;
    this.linesRead = 0;
    this.diffCellsUsed = 0;
    this.changes = 0;
    this.outputRows = 0;
  }

  object(bytes) {
    if (bytes > this.limits.maxObjectBytes) {
      fail("LIMIT_EXCEEDED", "Git object exceeds configured byte limit");
    }
    this.objects++;
    this.objectBytes += bytes;
    if (this.objects > this.limits.maxObjects
        || this.objectBytes > this.limits.maxTotalObjectBytes) {
      fail("LIMIT_EXCEEDED", "Git object read budget exceeded");
    }
  }

  treeEntry() {
    this.treeEntries++;
    if (this.treeEntries > this.limits.maxTreeEntries) {
      fail("LIMIT_EXCEEDED", "Git tree entry budget exceeded");
    }
  }

  file() {
    this.files++;
    if (this.files > this.limits.maxFiles) {
      fail("LIMIT_EXCEEDED", "Git file budget exceeded");
    }
  }

  lines(count) {
    this.linesRead += count;
    if (this.linesRead > this.limits.maxTotalLines) {
      fail("LIMIT_EXCEEDED", "Git text line budget exceeded");
    }
  }

  diffCells(count) {
    if (count > this.limits.maxDiffCells) {
      fail("LIMIT_EXCEEDED", "line diff complexity budget exceeded");
    }
    this.diffCellsUsed += count;
    if (this.diffCellsUsed > this.limits.maxDiffCells) {
      fail("LIMIT_EXCEEDED", "aggregate line diff complexity budget exceeded");
    }
  }

  change() {
    this.changes++;
    if (this.changes > this.limits.maxChanges) {
      fail("LIMIT_EXCEEDED", "Git change budget exceeded");
    }
  }

  output(count) {
    this.outputRows += count;
    if (this.outputRows > this.limits.maxOutputRows) {
      fail("LIMIT_EXCEEDED", "comparison output budget exceeded");
    }
  }

  snapshot() {
    return {
      objects: this.objects,
      objectBytes: this.objectBytes,
      treeEntries: this.treeEntries,
      files: this.files,
      lines: this.linesRead,
      diffCells: this.diffCellsUsed,
      candidateChanges: this.changes,
      outputRows: this.outputRows
    };
  }
}
