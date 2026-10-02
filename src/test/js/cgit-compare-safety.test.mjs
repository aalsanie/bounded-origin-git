import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import test from "node:test";
import {
  GitComparisonError,
  compareGit
} from "../../main/resources/io/github/aalsanie/boundedorigingit/client/cgit-compare-v1.mjs";
import {repository} from "./git-fixture.mjs";

globalThis.crypto ??= webcrypto;

test("reports binary and gitlink changes without dereferencing gitlinks", async () => {
  const repo = repository();
  const oldBinary = repo.add("blob", new Uint8Array([1, 0, 2]));
  const newBinary = repo.add("blob", new Uint8Array([1, 0, 3]));
  const oldLink = "a".repeat(40);
  const newLink = "b".repeat(40);
  const oldTree = repo.tree([
    {mode: "100644", name: "binary.dat", oid: oldBinary},
    {mode: "160000", name: "vendor", oid: oldLink}
  ]);
  const newTree = repo.tree([
    {mode: "100644", name: "binary.dat", oid: newBinary},
    {mode: "160000", name: "vendor", oid: newLink}
  ]);

  const compared = await compareGit({
    oldOid: repo.commit(oldTree),
    newOid: repo.commit(newTree),
    readObject: repo.readObject
  });

  assert.equal(compared.summary.binaryFiles, 1);
  assert.equal(compared.summary.gitlinks, 1);
  assert.equal(compared.changes.find(change => change.path === "binary.dat").binary, true);
  assert.equal(compared.changes.find(change => change.path === "vendor").gitlink, true);
  assert.equal(repo.reads.has(oldLink), false);
  assert.equal(repo.reads.has(newLink), false);
});

test("supports SHA-256 trees and annotated commit tags", async () => {
  const repo = repository("sha256");
  const oldBlob = repo.add("blob", "before\n");
  const newBlob = repo.add("blob", "after\n");
  const oldCommit =
      repo.commit(repo.tree([{mode: "100644", name: "f", oid: oldBlob}]));
  const newCommit =
      repo.commit(repo.tree([{mode: "100644", name: "f", oid: newBlob}]));
  const oldTag = repo.tag(oldCommit, "commit");
  const newTag = repo.tag(newCommit, "commit");

  const compared = await compareGit({
    oldOid: oldTag,
    newOid: newTag,
    hashAlgorithm: "SHA-256",
    readObject: repo.readObject
  });

  assert.equal(compared.hashAlgorithm, "SHA-256");
  assert.equal(compared.summary.filesChanged, 1);
  assert.equal(compared.summary.additions, 1);
  assert.equal(compared.summary.deletions, 1);
});

test("fails closed on object corruption and unsupported follow semantics", async () => {
  const repo = repository();
  const blob = repo.add("blob", "value\n");
  const commit =
      repo.commit(repo.tree([{mode: "100644", name: "f", oid: blob}]));
  const corruptOid = "f".repeat(40);

  await assert.rejects(
      () =>
          compareGit({
            oldOid: commit,
            newOid: corruptOid,
            readObject: async oid => {
              if (oid === corruptOid) {
                return {
                  type: "commit",
                  data: new TextEncoder().encode("tree " + "0".repeat(40) + "\n")
                };
              }
              return repo.readObject(oid);
            }
          }),
      error => error instanceof GitComparisonError && error.code === "OBJECT_ID_MISMATCH");

  await assert.rejects(
      () =>
          compareGit({
            oldOid: commit,
            newOid: commit,
            readObject: repo.readObject,
            follow: true
          }),
      error => error instanceof GitComparisonError && error.code === "UNSUPPORTED_SEMANTICS");
});

test("enforces aggregate complexity and abort budgets", async () => {
  const repo = repository();
  const oldBlob =
      repo.add(
          "blob",
          Array.from({length: 20}, (_, index) => "old-" + index).join("\n") + "\n");
  const newBlob =
      repo.add(
          "blob",
          Array.from({length: 20}, (_, index) => "new-" + index).join("\n") + "\n");
  const oldCommit =
      repo.commit(repo.tree([{mode: "100644", name: "f", oid: oldBlob}]));
  const newCommit =
      repo.commit(repo.tree([{mode: "100644", name: "f", oid: newBlob}]));

  await assert.rejects(
      () =>
          compareGit({
            oldOid: oldCommit,
            newOid: newCommit,
            readObject: repo.readObject,
            limits: {maxDiffCells: 100}
          }),
      error => error instanceof GitComparisonError && error.code === "LIMIT_EXCEEDED");

  const controller = new AbortController();
  controller.abort();
  await assert.rejects(
      () =>
          compareGit({
            oldOid: oldCommit,
            newOid: newCommit,
            readObject: repo.readObject,
            signal: controller.signal
          }),
      error => error instanceof GitComparisonError && error.code === "ABORTED");
});

test("treats invalid UTF-8 text as binary and keeps mode-only changes deterministic", async () => {
  const repo = repository();
  const bytes = new Uint8Array([0xc3, 0x28, 0x0a]);
  const blob = repo.add("blob", bytes);
  const oldTree = repo.tree([{mode: "100644", name: "bad.txt", oid: blob}]);
  const newTree = repo.tree([{mode: "100755", name: "bad.txt", oid: blob}]);

  const compared = await compareGit({
    oldOid: repo.commit(oldTree),
    newOid: repo.commit(newTree),
    readObject: repo.readObject
  });

  assert.equal(compared.changes.length, 1);
  assert.equal(compared.changes[0].status, "mode-changed");
  assert.equal(compared.changes[0].binary, false);
  assert.equal(compared.summary.additions, 0);
  assert.equal(compared.summary.deletions, 0);
});
