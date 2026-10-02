import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import test from "node:test";
import {
  CLIENT_COMPARISON_TYPE,
  CLIENT_COMPARISON_VERSION,
  compareGit
} from "../../main/resources/io/github/aalsanie/boundedorigingit/client/cgit-compare-v1.mjs";
import {repository} from "./git-fixture.mjs";

globalThis.crypto ??= webcrypto;

test("compares verified commit trees with deterministic unified output", async () => {
  const repo = repository();
  const oldBlob = repo.add("blob", "alpha\nbeta\ngamma\n");
  const newBlob = repo.add("blob", "alpha\nBETA\ngamma\nadded\n");
  const oldCommit =
      repo.commit(repo.tree([{mode: "100644", name: "file.txt", oid: oldBlob}]));
  const newCommit =
      repo.commit(repo.tree([{mode: "100644", name: "file.txt", oid: newBlob}]));

  const request = {
    oldOid: oldCommit,
    newOid: newCommit,
    readObject: repo.readObject,
    context: 1
  };
  const first = await compareGit(request);
  const second = await compareGit(request);

  assert.deepEqual(first, second);
  assert.equal(first.type, CLIENT_COMPARISON_TYPE);
  assert.equal(first.version, CLIENT_COMPARISON_VERSION);
  assert.deepEqual(first.summary, {
    filesChanged: 1,
    additions: 2,
    deletions: 1,
    binaryFiles: 0,
    gitlinks: 0
  });
  assert.equal(first.changes[0].path, "file.txt");
  assert.equal(first.changes[0].detail.kind, "unified");
  assert.deepEqual(
      first.changes[0].detail.hunks[0].lines.map(line => [line.kind, line.text]),
      [
        ["context", "alpha"],
        ["delete", "beta"],
        ["add", "BETA"],
        ["context", "gamma"],
        ["add", "added"]
      ]);
});

test("supports side-by-side output and ignore-whitespace semantics", async () => {
  const repo = repository();
  const oldBlob = repo.add("blob", "one\na b\nthree\n");
  const newBlob = repo.add("blob", "one\nab\nTHREE\n");
  const oldCommit =
      repo.commit(repo.tree([{mode: "100644", name: "file.txt", oid: oldBlob}]));
  const newCommit =
      repo.commit(repo.tree([{mode: "100644", name: "file.txt", oid: newBlob}]));

  const compared = await compareGit({
    oldOid: oldCommit,
    newOid: newCommit,
    readObject: repo.readObject,
    mode: "side-by-side",
    ignoreWhitespace: true,
    context: 1
  });

  assert.equal(compared.summary.additions, 1);
  assert.equal(compared.summary.deletions, 1);
  assert.equal(compared.changes[0].detail.kind, "side-by-side");
  const changedRows =
      compared.changes[0].detail.hunks
          .flatMap(hunk => hunk.rows)
          .filter(row => row.kind === "change");
  assert.deepEqual(changedRows, [
    {
      kind: "change",
      left: {line: 3, text: "three"},
      right: {line: 3, text: "THREE"}
    }
  ]);
});

test("walks recursive trees and scopes stat comparison to one path", async () => {
  const repo = repository();
  const oldA = repo.add("blob", "old-a\n");
  const newA = repo.add("blob", "new-a\n");
  const oldB = repo.add("blob", "old-b\n");
  const newB = repo.add("blob", "new-b\n");
  const oldSrc = repo.tree([
    {mode: "100644", name: "a.txt", oid: oldA},
    {mode: "100644", name: "b.txt", oid: oldB}
  ]);
  const newSrc = repo.tree([
    {mode: "100644", name: "a.txt", oid: newA},
    {mode: "100644", name: "b.txt", oid: newB}
  ]);
  const oldCommit = repo.commit(repo.tree([{mode: "40000", name: "src", oid: oldSrc}]));
  const newCommit = repo.commit(repo.tree([{mode: "40000", name: "src", oid: newSrc}]));

  const compared = await compareGit({
    oldOid: oldCommit,
    newOid: newCommit,
    readObject: repo.readObject,
    path: "src/a.txt",
    mode: "stat"
  });

  assert.equal(compared.changes.length, 1);
  assert.equal(compared.changes[0].path, "src/a.txt");
  assert.equal(compared.changes[0].detail, null);
  assert.equal(compared.summary.additions, 1);
  assert.equal(compared.summary.deletions, 1);
});
