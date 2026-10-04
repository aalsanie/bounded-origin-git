import {fork} from "node:child_process";
import {createHash} from "node:crypto";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import http from "node:http";
import {fileURLToPath, pathToFileURL} from "node:url";
import {join} from "node:path";
import {gunzipSync, inflateSync} from "node:zlib";

const source = fileURLToPath(import.meta.url);
const userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36";
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

export function challengeFrom(body) {
  const match = body.subarray(0, 65536).toString("utf8").match(/<script\b[^>]*\bid=["']anubis_challenge["'][^>]*>([\s\S]*?)<\/script>/);
  return match ? JSON.parse(match[1]) : null;
}

export function solve(challenge, maximumMs = 60000) {
  const start = performance.now();
  const difficulty = challenge.rules.difficulty;
  if (!Number.isInteger(difficulty) || difficulty < 1 || difficulty > 6) {
    throw new Error("unsupported challenge difficulty");
  }
  const prefix = "0".repeat(difficulty);
  for (let nonce = 0; ; nonce++) {
    const response = createHash("sha256").update(challenge.challenge.randomData + nonce).digest("hex");
    if (response.startsWith(prefix)) {
      return {nonce, response, elapsedTime: Math.max(1, performance.now() - start), hashes: nonce + 1};
    }
    if ((nonce & 16383) === 0 && performance.now() - start > maximumMs) {
      throw Object.assign(new Error("proof-of-work deadline exceeded"),
          {code: "POW_DEADLINE", hashes: nonce + 1, elapsedTime: performance.now() - start});
    }
  }
}

export function unpackObject(compressed, maxBytes) {
  let inflated;
  try {
    inflated = inflateSync(compressed, {maxOutputLength: maxBytes + 128});
  } catch (error) {
    if (error.code === "ERR_BUFFER_TOO_LARGE") {
      throw Object.assign(new Error("Git object exceeds client byte budget"), {code: "LIMIT_EXCEEDED"});
    }
    throw error;
  }
  const separator = inflated.indexOf(0);
  if (separator < 0 || separator > 127) {
    throw new Error("invalid loose Git header");
  }
  const [type, size] = inflated.subarray(0, separator).toString("ascii").split(" ");
  const data = inflated.subarray(separator + 1);
  if (!/^[0-9]+$/.test(size) || Number(size) !== data.length) {
    throw new Error("invalid loose Git object size");
  }
  if (data.length > maxBytes) {
    throw Object.assign(new Error("Git object exceeds client byte budget"), {code: "LIMIT_EXCEEDED"});
  }
  return {type, data};
}

export class Session {
  constructor(config) {
    this.config = config;
    this.agent = new http.Agent({keepAlive: true, maxSockets: 2});
    this.cookies = new Map();
    this.objectCache = new Map();
    this.cacheBytes = 0;
    this.bytesRead = 0;
    this.bytesWritten = 0;
    this.httpRequests = 0;
    this.objectRequests = 0;
    this.objectCacheHits = 0;
    this.moduleBytes = 0;
  }

  async get(url, limit = 128 * 1024 * 1024) {
    const headers = {"User-Agent": userAgent, Accept: "*/*", "Accept-Encoding": "gzip"};
    if (this.cookies.size && new URL(url).origin === new URL(this.config.base).origin) {
      headers.Cookie = [...this.cookies].map(([name, value]) => name + "=" + value).join("; ");
    }
    this.httpRequests++;
    return await new Promise((resolve, reject) => {
      let socket;
      let beforeRead = 0;
      let beforeWrite = 0;
      let accounted = false;
      const account = () => {
        if (!accounted && socket) {
          this.bytesRead += socket.bytesRead - beforeRead;
          this.bytesWritten += socket.bytesWritten - beforeWrite;
          accounted = true;
        }
      };
      const fail = error => {
        account();
        reject(error);
      };
      const request = http.get(url, {agent: this.agent, headers}, response => {
        const chunks = [];
        let size = 0;
        response.on("data", chunk => {
          size += chunk.length;
          if (size > limit) {
            response.destroy(Object.assign(new Error("response byte limit exceeded"), {code: "RESPONSE_LIMIT"}));
          } else {
            chunks.push(chunk);
          }
        });
        response.on("error", fail);
        response.on("end", () => {
          account();
          try {
            let body = Buffer.concat(chunks);
            const encoding = response.headers["content-encoding"]?.trim().toLowerCase();
            if (encoding === "gzip") {
              body = gunzipSync(body, {maxOutputLength: limit});
            } else if (encoding && encoding !== "identity") {
              throw new Error("unsupported HTTP content encoding: " + encoding);
            }
            for (const cookie of response.headers["set-cookie"] ?? []) {
              const first = cookie.split(";", 1)[0];
              const separator = first.indexOf("=");
              this.cookies.set(first.slice(0, separator), first.slice(separator + 1));
            }
            resolve({status: response.statusCode, headers: response.headers, body});
          } catch (error) {
            if (error.code === "ERR_BUFFER_TOO_LARGE") {
              error = Object.assign(new Error("decoded response byte limit exceeded"), {code: "RESPONSE_LIMIT"});
            }
            fail(error);
          }
        });
      });
      request.on("socket", value => {
        socket = value;
        beforeRead = value.bytesRead;
        beforeWrite = value.bytesWritten;
      });
      const timer = setTimeout(() => request.destroy(new Error("HTTP deadline exceeded")), 60000);
      request.on("error", fail);
      request.on("close", () => clearTimeout(timer));
    });
  }

  async initialize() {
    if (!this.config.bounded) {
      return;
    }
    const directory = join(this.config.directory, "client-modules-" + process.pid);
    await mkdir(directory);
    for (const module of ["cgit-compare-v1.mjs", "cgit-comparison-request-v1.mjs"]) {
      const response = await this.get(this.config.assets + "/client/" + module, 128 * 1024);
      if (response.status !== 200) {
        throw new Error("client module unavailable");
      }
      this.moduleBytes += response.body.length;
      await writeFile(join(directory, module), response.body);
    }
    this.compare = (await import(pathToFileURL(join(directory, "cgit-compare-v1.mjs")))).compareGit;
    this.parse = (await import(pathToFileURL(join(directory, "cgit-comparison-request-v1.mjs")))).comparisonRequestFromCgitUrl;
  }

  async object(oid, options) {
    const cached = this.objectCache.get(oid);
    if (cached && cached.data.length <= options.maxBytes) {
      this.objectCacheHits++;
      this.objectCache.delete(oid);
      this.objectCache.set(oid, cached);
      return cached;
    }
    this.objectRequests++;
    let response;
    try {
      response = await this.get(this.config.assets + "/objects/" + oid, options.maxBytes + 65536);
    } catch (error) {
      if (error.code === "RESPONSE_LIMIT") {
        throw Object.assign(new Error("Git object transfer exceeds client byte budget"), {code: "LIMIT_EXCEEDED"});
      }
      throw error;
    }
    if (response.status !== 200) {
      throw new Error("Git object unavailable: " + response.status);
    }
    const object = unpackObject(response.body, options.maxBytes);
    const maximum = 8 * 1024 * 1024;
    while ((this.cacheBytes + object.data.length > maximum || this.objectCache.size >= 4096) && this.objectCache.size) {
      const first = this.objectCache.keys().next().value;
      this.cacheBytes -= this.objectCache.get(first).data.length;
      this.objectCache.delete(first);
    }
    if (object.data.length <= maximum) {
      this.objectCache.set(oid, object);
      this.cacheBytes += object.data.length;
    }
    return object;
  }

  counters() {
    return {wire_read_bytes: this.bytesRead, wire_written_bytes: this.bytesWritten,
      http_requests: this.httpRequests, object_requests: this.objectRequests,
      object_cache_hits: this.objectCacheHits};
  }

  async run(entry, index) {
    if (this.config.session_requests && index % this.config.session_requests === 0) {
      this.cookies.clear();
      this.objectCache.clear();
      this.cacheBytes = 0;
    }
    const counters = this.counters();
    const cpu = process.cpuUsage();
    const started = performance.now();
    const row = {index: entry.index, operation: entry.semantic, target: entry.target,
      kind: entry.kind, start_unix_ms: Date.now(), outcome: "http_error", challenges: 0,
      pow_hashes: 0, pow_ms: 0};
    try {
      const url = this.config.base + entry.target;
      let response = await this.get(url);
      let challenge = challengeFrom(response.body);
      if (challenge) {
        row.challenges++;
        if (!this.config.solve) {
          row.status = response.status;
          row.body_bytes = response.body.length;
          row.body_sha256 = createHash("sha256").update(response.body).digest("hex");
          row.outcome = "challenge";
          return row;
        }
        const proof = solve(challenge);
        row.pow_hashes += proof.hashes;
        row.pow_ms += proof.elapsedTime;
        const params = new URLSearchParams({id: challenge.challenge.id, response: proof.response,
          nonce: String(proof.nonce), elapsedTime: String(proof.elapsedTime), redir: url});
        const passed = await this.get(this.config.base + "/.within.website/x/cmd/anubis/api/pass-challenge?" + params);
        if (passed.status !== 302 && passed.status !== 303 && passed.status !== 307) {
          throw new Error("Anubis rejected valid proof: " + passed.status + " " + passed.body.toString().slice(0, 100));
        }
        response = await this.get(url);
        challenge = challengeFrom(response.body);
        if (challenge) {
          throw new Error("Anubis issued another challenge after a valid proof");
        }
      }
      row.status = response.status;
      row.body_bytes = response.body.length;
      row.body_sha256 = createHash("sha256").update(response.body).digest("hex");
      row.cache = response.headers["x-benchmark-cache"] ?? null;
      if (response.status !== 200) {
        row.outcome = this.config.bounded && response.status === 404 ? "artifact_miss" : "http_error";
        return row;
      }
      if (this.config.bounded && entry.kind === "comparison") {
        const descriptor = JSON.parse(response.body.toString());
        if (descriptor.type !== "bounded-origin-git.compare" || descriptor.version !== "1") {
          throw new Error("invalid comparison descriptor");
        }
        const request = this.parse(entry.target, entry.repository);
        const compared = await this.compare({...request, readObject: (oid, options) => this.object(oid, options)});
        row.comparison = {summary: compared.summary, consumed: compared.consumed,
          changes: compared.changes.map(change => ({path: change.path, oldOid: change.oldOid, newOid: change.newOid,
            oldMode: change.oldMode, newMode: change.newMode, additions: change.additions,
            deletions: change.deletions, binary: change.binary, gitlink: change.gitlink}))};
        const paths = compared.changes.map(change => change.path).sort();
        if (JSON.stringify(paths) !== JSON.stringify([...entry.expected_paths].sort())) {
          row.outcome = "semantic_mismatch";
          return row;
        }
        for (const expected of entry.expected_changes ?? []) {
          const actual = compared.changes.find(change => change.path === expected.path);
          if (["oldOid", "newOid", "oldMode", "newMode"].some(key => actual[key] !== expected[key])) {
            row.outcome = "semantic_mismatch";
            return row;
          }
        }
      } else if (entry.expected_sha256 && row.body_sha256 !== entry.expected_sha256) {
        row.outcome = "semantic_mismatch";
        return row;
      } else if (entry.expected_oid && !response.body.includes(entry.expected_oid)) {
        row.outcome = "semantic_mismatch";
        return row;
      }
      row.outcome = "delivered";
    } catch (error) {
      if (error.code === "POW_DEADLINE") {
        row.pow_hashes += error.hashes;
        row.pow_ms += error.elapsedTime;
      }
      row.error = error.message;
      row.error_code = error.code ?? null;
      row.outcome = error.code === "LIMIT_EXCEEDED" ? "comparison_limit"
          : error.code === "POW_DEADLINE" ? "challenge_timeout"
          : error.code === "RESPONSE_LIMIT" ? "response_limit"
          : error.code?.startsWith("UNSUPPORTED_") ? "unsupported_comparison"
          : "client_error";
    } finally {
      row.latency_ms = performance.now() - started;
      const used = process.cpuUsage(cpu);
      row.client_cpu_seconds = (used.user + used.system) / 1e6;
      row.client_max_rss_kib = process.resourceUsage().maxRSS;
      const after = this.counters();
      for (const key of Object.keys(after)) {
        row[key] = after[key] - counters[key];
      }
    }
    return row;
  }
}

async function worker(config) {
  const session = new Session(config);
  await session.initialize();
  process.send({ready: true});
  await new Promise(resolve => process.once("message", resolve));
  for (let index = 0; index < config.requests.length; index++) {
    const row = await session.run(config.requests[index], index);
    process.send({row});
    if (config.interval_ms) {
      await sleep(config.interval_ms);
    }
  }
  process.send({summary: {...session.counters(), module_body_bytes: session.moduleBytes,
    process_cpu: process.cpuUsage(), max_rss_kib: process.resourceUsage().maxRSS}});
  session.agent.destroy();
  process.disconnect();
}

async function main(configPath) {
  const config = JSON.parse(await readFile(configPath, "utf8"));
  const count = Math.min(config.concurrency, config.requests.length);
  const workers = [];
  const summaries = [];
  let ready = 0;
  let completed = 0;
  const deadline = setTimeout(() => {
    for (const child of workers) child.kill("SIGTERM");
    process.stderr.write("client batch deadline exceeded\n");
    process.exitCode = 1;
  }, config.timeout_ms ?? 600000);
  await new Promise((resolve, reject) => {
    for (let index = 0; index < count; index++) {
      const child = fork(source, ["--worker"], {stdio: ["ignore", "ignore", "inherit", "ipc"]});
      workers.push(child);
      child.on("message", message => {
        if (message.ready) {
          ready++;
          if (ready === count) {
            for (const current of workers) current.send({begin: true});
          }
        } else if (message.row) {
          process.stdout.write(JSON.stringify(message.row) + "\n");
        } else if (message.summary) {
          summaries.push(message.summary);
        }
      });
      child.on("error", reject);
      child.on("exit", code => {
        if (code !== 0) {
          for (const current of workers) current.kill("SIGTERM");
          reject(new Error("client worker failed with exit " + code));
          return;
        }
        if (++completed === count) resolve();
      });
      child.send({...config, requests: config.requests.filter((_, ordinal) => ordinal % count === index)});
    }
  });
  clearTimeout(deadline);
  await writeFile(config.summary, JSON.stringify({workers: summaries, cpu: process.cpuUsage()}, null, 2) + "\n");
}

if (process.argv[2] === "--worker") {
  process.once("message", config => worker(config).catch(error => {
    process.stderr.write(error.stack + "\n");
    process.exit(1);
  }));
} else if (process.argv[1] === source && process.argv[2]) {
  await main(process.argv[2]);
}
