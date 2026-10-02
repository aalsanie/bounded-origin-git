import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import {mkdtemp, writeFile, rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {spawnSync} from "node:child_process";
import test from "node:test";
import {compareGit} from "../../main/resources/io/github/aalsanie/boundedorigingit/client/cgit-compare-v1.mjs";
import {repository} from "./git-fixture.mjs";

globalThis.crypto ??= webcrypto;

function textPair(before, after) {
  const repo = repository();
  const commit = text => repo.commit(repo.tree([
    {mode: "100644", name: "file.txt", oid: repo.add("blob", text)}
  ]));
  return {oldOid: commit(before), newOid: commit(after), readObject: repo.readObject};
}

test("matches native Git for final newlines, BOM bytes and whitespace", async () => {
  const directory = await mkdtemp(join(tmpdir(), "git-diff-oracle-"));
  try {
    for (const [before, after] of [["hello", "hello\n"], ["hello\n", "hello"],
      ["hello\n", "\ufeffhello\n"], ["a b\n", "ab\n"], ["\n", ""],
      ["a\nb\nc\n", "a\nB\nc\n"]]) {
      await writeFile(join(directory, "before"), before);
      await writeFile(join(directory, "after"), after);
      for (const ignoreWhitespace of [false, true]) {
        const oracle = spawnSync("git", ["-c", "core.autocrlf=false", "diff", "--no-index",
          "--no-ext-diff", "--no-textconv", "--numstat",
          ...(ignoreWhitespace ? ["--ignore-all-space"] : []), "--", "before", "after"],
          {cwd: directory, encoding: "utf8"});
        assert.ok(oracle.status === 0 || oracle.status === 1, oracle.stderr);
        const counts = oracle.stdout.trim() ? oracle.stdout.split(/\s+/).slice(0, 2).map(Number) : [0, 0];
        const result = await compareGit({...textPair(before, after), ignoreWhitespace});
        assert.deepEqual([result.summary.additions, result.summary.deletions], counts,
            JSON.stringify({before, after, ignoreWhitespace}));
      }
    }
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test("preserves missing-newline markers and zero-length hunk ranges", async () => {
  const result = await compareGit({...textPair("hello", "hello\n"), context: 0});
  const lines = result.changes[0].detail.hunks[0].lines;
  assert.equal(lines.find(line => line.kind === "delete").noNewline, true);
  assert.equal(lines.find(line => line.kind === "add").noNewline, undefined);
  const added = await compareGit({...textPair("", "hello\n"), context: 0});
  const hunk = added.changes[0].detail.hunks[0];
  assert.deepEqual([hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount], [0, 0, 1, 1]);
});

test("does not read unchanged subtrees while comparing large repositories", async () => {
  const repo = repository();
  const blob = repo.add("blob", "same\n");
  const stableTree = repo.tree(Array.from({length: 6000}, (_, index) =>
    ({mode: "100644", name: `file-${index}`, oid: blob})));
  const commit = text => repo.commit(repo.tree([
    {mode: "40000", name: "stable", oid: stableTree},
    {mode: "100644", name: "changed", oid: repo.add("blob", text)}
  ]));
  const result = await compareGit({oldOid: commit("before\n"), newOid: commit("after\n"),
    limits: {maxFiles: 2, maxTreeEntries: 4}, readObject: async (oid, options) => {
      assert.notEqual(oid, stableTree);
      assert.ok(Number.isSafeInteger(options.maxBytes));
      return repo.readObject(oid);
    }});
  assert.equal(result.summary.filesChanged, 1);
  assert.equal(result.consumed.files, 2);
});

test("compares a single changed file in a large flat tree within file limits", async () => {
  const repo = repository();
  const unchanged = repo.add("blob", "same\n");
  const entries = Array.from({length: 6000}, (_, index) =>
    ({mode: "100644", name: `file-${index}`, oid: unchanged}));
  const oldOid = repo.commit(repo.tree(entries));
  const newOid = repo.commit(repo.tree(entries.map((entry, index) =>
    index === 0 ? {...entry, oid: repo.add("blob", "changed\n")} : entry)));
  const result = await compareGit({oldOid, newOid, readObject: repo.readObject});
  assert.equal(result.summary.filesChanged, 1);
  assert.equal(result.consumed.files, 2);
});

test("trims equal lines before charging the quadratic diff budget", async () => {
  const before = Array.from({length: 10000}, (_, index) => "line-" + index);
  const after = [...before];
  after[5000] = "changed";
  const result = await compareGit({...textPair(before.join("\n") + "\n", after.join("\n") + "\n"),
    limits: {maxDiffCells: 4}});
  assert.equal(result.summary.additions, 1);
  assert.equal(result.summary.deletions, 1);
  assert.equal(result.consumed.diffCells, 4);
  assert.equal(result.changes[0].detail.hunks[0].oldStart, 4998);
});

test("rejects duplicate tree names instead of merging their descendants", async () => {
  const repo = repository();
  const blob = repo.add("blob", "content\n");
  const tree = name => repo.tree([{mode: "100644", name, oid: blob}]);
  const malformed = repo.tree([
    {mode: "40000", name: "duplicate", oid: tree("a")},
    {mode: "40000", name: "duplicate", oid: tree("b")}
  ]);
  await assert.rejects(() => compareGit({oldOid: repo.commit(repo.tree([])),
    newOid: repo.commit(malformed), readObject: repo.readObject}),
    error => error.code === "INVALID_GIT_OBJECT");
});

test("preserves file-to-directory replacements and UTF-8 BOM filenames", async () => {
  const repo = repository();
  const blob = repo.add("blob", "content\n");
  const oldOid = repo.commit(repo.tree([{mode: "100644", name: "path", oid: blob}]));
  const newOid = repo.commit(repo.tree([{mode: "40000", name: "path",
    oid: repo.tree([{mode: "100644", name: "\ufefffile", oid: blob}])}]));
  const result = await compareGit({oldOid, newOid, readObject: repo.readObject});
  assert.deepEqual(result.changes.map(change => [change.path, change.status]),
      [["path", "deleted"], ["path/\ufefffile", "added"]]);
});
