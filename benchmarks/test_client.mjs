import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import http from "node:http";
import {once} from "node:events";
import {deflateSync, gzipSync} from "node:zlib";
import test from "node:test";
import {challengeFrom, Session, solve, unpackObject} from "./client.mjs";

test("solves the actual Anubis proof formula and parses its challenge envelope", () => {
  const value = {rules: {difficulty: 2}, challenge: {randomData: "fixture", id: "id"}};
  const html = Buffer.from('<script type="application/json" id="anubis_challenge">' + JSON.stringify(value) + "</script>");
  assert.deepEqual(challengeFrom(html), value);
  const proof = solve(value);
  assert.equal(proof.response, createHash("sha256").update("fixture" + proof.nonce).digest("hex"));
  assert.ok(proof.response.startsWith("00"));
  assert.equal(challengeFrom(Buffer.from("ordinary cgit page")), null);
});

test("enforces both the declared and inflated native Git object byte limits", () => {
  const object = unpackObject(deflateSync(Buffer.from("blob 5\0hello")), 5);
  assert.equal(object.type, "blob");
  assert.equal(object.data.toString(), "hello");
  assert.throws(() => unpackObject(deflateSync(Buffer.from("blob 4\0hello")), 5));
  assert.throws(() => unpackObject(deflateSync(Buffer.from("blob 5\0hello")), 4), {code: "LIMIT_EXCEEDED"});
  assert.throws(() => unpackObject(deflateSync(Buffer.alloc(4096)), 8), {code: "LIMIT_EXCEEDED"});
});

test("browser transport negotiates gzip, decodes challenges, and accounts compressed socket bytes", async () => {
  const challenge = {rules: {difficulty: 2}, challenge: {randomData: "fixture", id: "id"}};
  const html = Buffer.from('<script id="anubis_challenge">' + JSON.stringify(challenge) + "</script>" + " ".repeat(16384));
  const compressed = gzipSync(html);
  const server = http.createServer((request, response) => {
    assert.equal(request.headers["accept-encoding"], "gzip");
    response.writeHead(200, {"Content-Encoding": "gzip", "Content-Length": compressed.length,
      "Set-Cookie": "challenge=issued; HttpOnly"});
    response.end(compressed);
  }).listen(0, "127.0.0.1");
  await once(server, "listening");
  const session = new Session({base: `http://127.0.0.1:${server.address().port}`});
  try {
    const response = await session.get(session.config.base);
    assert.deepEqual(response.body, html);
    assert.deepEqual(challengeFrom(response.body), challenge);
    assert.equal(session.cookies.get("challenge"), "issued");
    assert.ok(session.bytesRead >= compressed.length);
    assert.ok(session.bytesRead < html.length);
    assert.equal(session.httpRequests, 1);
  } finally {
    session.agent.destroy();
    await new Promise(resolve => server.close(resolve));
  }
});

test("HTTP transport rejects encoded and decoded overflow and corrupt gzip", async () => {
  const server = http.createServer((request, response) => {
    response.setHeader("Content-Encoding", "gzip");
    const body = request.url === "/decoded" ? gzipSync(Buffer.alloc(4096)) : Buffer.alloc(256, 120);
    response.end(body);
  }).listen(0, "127.0.0.1");
  await once(server, "listening");
  const session = new Session({base: `http://127.0.0.1:${server.address().port}`});
  try {
    await assert.rejects(session.get(session.config.base + "/decoded", 64), {code: "RESPONSE_LIMIT"});
    await assert.rejects(session.get(session.config.base + "/encoded", 64), {code: "RESPONSE_LIMIT"});
    await assert.rejects(session.get(session.config.base + "/corrupt", 1024), {code: "Z_DATA_ERROR"});
  } finally {
    session.agent.destroy();
    await new Promise(resolve => server.close(resolve));
  }
});
