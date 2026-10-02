import assert from "node:assert/strict";
import test from "node:test";
import {comparisonRequestFromCgitUrl} from "../../main/resources/io/github/aalsanie/boundedorigingit/client/cgit-comparison-request-v1.mjs";

const oldOid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const newOid = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

test("normalizes supported cgit routing forms", () => {
  const virtual =
      comparisonRequestFromCgitUrl(
          "/project/diff/src/App.java?id="
              + newOid
              + "&id2="
              + oldOid
              + "&context=05&ignorews=1&dt=1",
          "project");
  const legacy =
      comparisonRequestFromCgitUrl(
          "/?r=project&p=diff&path=src/App.java&id="
              + newOid
              + "&id2="
              + oldOid
              + "&ignorews=1&context=5&ss=1",
          "project");

  assert.deepEqual(virtual, legacy);
  assert.equal(virtual.hashAlgorithm, "SHA-1");
  assert.equal(virtual.path, "src/App.java");
  assert.equal(virtual.context, 5);
  assert.equal(virtual.ignoreWhitespace, true);
  assert.equal(virtual.mode, "side-by-side");
});

test("maps default context and SHA-256 stat mode", () => {
  const request =
      comparisonRequestFromCgitUrl(
          "/project/diff?id="
              + "b".repeat(64)
              + "&id2="
              + "a".repeat(64)
              + "&context=0&dt=2",
          "project");

  assert.equal(request.hashAlgorithm, "SHA-256");
  assert.equal(request.context, 3);
  assert.equal(request.mode, "stat");
});
