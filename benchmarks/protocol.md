# Linux Git workload benchmark protocol

This protocol measures whether anonymous cgit request growth increases expensive Git rendering. It is a local experiment using released Bounded Origin 0.1.0 artifacts, native cgit, nginx and Anubis. It is not a replay of production traffic or a claim about every crawler, Git repository, browser or deployment.

## Dataset and provenance

Use one bare clone of `https://github.com/torvalds/linux.git`, tag `v6.12`, depth 32. Its commit must be `adc218676eef25575469234709c2d87185ca223a`. Keep complete trees and blobs; use only commits whose parent is available, excluding every commit listed in Git's shallow-boundary file even if its parent is reachable through another branch. Select the first 128 eligible non-merge commits in Git's recorded traversal order. Four repository namespaces share the same native object data. They simulate duplicate content across forks, not four independent histories or cross-repository HTML deduplication.

`prepare.py` records the dataset file hashes, commit/object inventory, selected commits, exact request catalogue, large-file payload digest, ref-update content digests, and native Git comparison oracle. Eight comparison pairs use parent/child commits; eight use distinct sampled commits. The largest available blob no larger than 4 MiB supplies the streaming workload.

Freeze the executable bundle before launching the campaign. Reproduction consumes released Maven artifacts; it requires no generic source checkout or private fixtures.

## Compared configurations

| Configuration | Behavior |
| --- | --- |
| cgit | Real CGI rendering for each request, internal cgit caching disabled. |
| nginx-cold | nginx proxy cache initially empty; raw request-URI keys, cache locking enabled. |
| nginx-warm | Same nginx configuration, canonical catalogue URLs populated before measurement. |
| anubis-d4-solve | Anubis 1.27.0 with difficulty 4; client solves real challenges and retains issued cookies. |
| anubis-d5-solve | Same at difficulty 5. |
| anubis-d5-nosolve | Difficulty 5; client records challenges without solving them. |
| bounded-cold | Immutable Git objects ingested; no rendered HTML/plain artifacts published. |
| bounded-warm | Same ingestion plus trusted publication of the canonical planned artifacts. |

nginx 1.26.3 uses a 2 GiB cache and one-hour retention. It caches public CGI responses deliberately, ignoring CGI cache expiration headers, and locks concurrent misses for up to 60 seconds. Its warm catalogue includes comparison HTML. Bounded Origin comparisons run on clients and require no rendered comparison artifact. nginx has no semantic URL canonicalizer or automatic ref-publication invalidation. This is an ordinary-cache baseline with an explicit freshness tradeoff. Record `X-Benchmark-Cache` outcomes. A cached CGI hit does not invoke cgit.

The baseline HTTP transport, CGI wrapper, cgit configuration and native packed object view are shared by cgit/nginx/Anubis. The Bounded Origin producer uses the integration's native object ingestion and `ProcessCgitRenderer` without changing its implementation. Its immutable objects are loose Git objects. Capture both storage layouts and their preparation costs.

The Bounded Origin HTTP path uses the released gateway's `ARTIFACT_ONLY` and `CLIENT_COMPUTE` strategies. Artifact identities project the pinned cgit operation into an explicit `HttpOperation` with `PUBLIC_IMMUTABLE`. Stored materialized bytes are authoritative for that run's published generation; they are not evicted or regenerated during that generation. Public artifacts contain no personalized data. A separate loopback control endpoint, protected by an unpredictable bearer token, performs trusted publication/rendering. Object transfer reads already-ingested native object files without invoking Git or cgit.

## Workloads

The publication run uses two complete warmup repetitions and ten measured repetitions `seed(424242)`.

- Human mix: 40 requests across commits, comparisons and a large plain file; two clients with 250 ms pauses per client.
- Repeated burst: independent cells at 32, 128 and 512 requests over four semantic commit operations, concurrency 16.
- Alias flood: independent cells at 32, 128 and 512 requests over four repository/commit operations with equivalent path, legacy-query, reordered-query and `url=` routes, concurrency 16.
- Unique crawl: independent cells at 32, 128 and 512 distinct operations sampled from the four-namespace, 128-commit catalogue, concurrency 16.
- Comparisons: 64 two-sided diff requests over the 16 recorded pairs, concurrency four. Unified, side-by-side and stat modes are included.
- Large plain file: 32 requests, concurrency eight, with exact payload-digest verification.
- Session churn: 128 requests, concurrency eight; cookie and client-object caches reset after every four requests per worker.
- Ref update: requests before and after trusted ref publication, then after new-generation materialization for Bounded Origin warm. Record stale cache responses, artifact misses, and update preparation costs separately.

The 112 configuration/workload/count cells repeat twelve times, giving 1,344 cells including warmups, plus separate failure controls. Request counts are configurable and recorded.

Each repetition also runs separate trusted-producer controls: simultaneous same-key submissions, distinct-key queue exhaustion, a delayed child exceeding the execution deadline, and an injected CGI failure followed by cooldown requests.

## Resource and timing controls

Run all data and active caches on the native Linux filesystem. Server processes and descendants share CPUs 0–1, a two-core cgroup quota, 4 GiB memory including page cache, no additional swap, and a 512-task ceiling. Clients use CPUs 2–3, the same CPU quota, 2 GiB memory and the same task ceiling. The orchestrator is outside these groups. These native cgroup controls and executable hashes replace a container image in this WSL2 experiment; record WSL and host scheduling as a reproducibility limitation.

The orchestrator creates a private Linux network namespace with only loopback enabled. nginx, Anubis and Anubis metrics use distinct fixed ports outside the ephemeral range; other listeners bind port zero. This avoids bind-and-release port selection races and interference from host services. Readiness checks verify both Anubis listeners before requests begin. Failures capture live process, cgroup, socket and kernel diagnostics before cleanup; cleanup errors fail the cell.

Trusted rendering permits 16 queued jobs, a five-second execution deadline and 500 ms failure cooldown. CGI bodies, HTTP responses and artifacts have a 128 MiB limit, sufficient for the large native comparison pages observed during protocol validation. Client engine limits remain unchanged. Each client session has an 8 MiB, 4,096-entry object cache and fetches the actual comparison modules from the running adapter; module transfer/setup costs are recorded. 

## Raw results and analysis

```
environment.json
dataset.json
config.json
protocol.md
status.json
raw/
  warmup-00/...
  warmup-01/...
  run-00/
    ingestion.json
    ingestion-resources.jsonl
    <cell>/
      plan.json
      startup.json
      endpoints.json
      *.process.json
      shutdown.json
      failure.json (on failure)
      preparation.json
      requests.stdout.log
      requests-client-summary.json
      requests-measurement.json
      requests-resources.jsonl
      origin.jsonl
      *-gateway.prom
      *-anubis.prom
    controls/...
  ...
result-inventory.json
```

`status.json` distinguishes running, completed, interrupted and failed, and names the current cell. Raw results are mirrored after each completed cell. A failed/interrupted campaign preserves its records and never produces a successful completion marker.

After the owner reports completion, `summarize.py` generates per-cell and aggregate CSV/JSON using raw evidence. Report means, medians, standard deviations, request p50/p95/p99, and seeded 95% bootstrap confidence intervals over the ten repetition-level measurements. Preserve paired repetition IDs for comparisons. Do not treat thousands of requests in one repetition as thousands of independent experiment repetitions. Report failure/rejection rates, complete-response throughput and preparation costs alongside attempted-request rates. Tables must be generated, not manually transcribed.

The completed publication campaign is reported in [the benchmark report](../BENCHMARKS.md) and [README](../README.md), with the complete exported observation data and generated figures under `results/2026-10-03`.

## Running

Build with Java 21, Node 22, Python 3 and Git using `./gradlew clean check benchmarkBundle`. Copy `build/benchmark` onto the native Linux filesystem. Adapt `config.example.json` to identify that frozen bundle, pinned tools, dataset, manifest, a new scratch directory and source provenance. Create the manifest with `python3 benchmarks/prepare.py DATASET MANIFEST`. The provenance directory must contain `source.tar.gz`, a complete source snapshot matching `source.archive_sha256`; retain the Git base revision and any uncommitted changes in that snapshot. The campaign copies provenance into its results and rejects a mismatched archive. Package archives, dependency and runtime hashes accompany the recorded campaign.

Run helper tests and a complete small fixture validation before a native Linux-data pilot. Linux requires Python 3.12 or newer, iproute2, network namespaces and cgroup v1 CPU, CPU accounting, memory, task-count and block-I/O controllers; unavailable optional block-I/O counters are recorded as null. Root is used to create private cgroups and the network namespace; all listeners bind loopback. Set `BO_BENCHMARK_NATIVE_TESTS=1` to run the namespace regression with these privileges. Set `PYTHONDONTWRITEBYTECODE=1` when launching the frozen Python bundle.

Run `python3 benchmarks/validate_runtime.py --config CONFIG --output NEW_DIRECTORY` with the pinned native tools. It verifies an occupied listener fails with durable diagnostics, 200 service startup/shutdown cycles release all processes and cgroups, and 2,048 unsolved requests receive valid gzip-capable Anubis challenges. This is separate validation evidence and is excluded from measured campaign statistics.

Launch `python3 benchmarks/campaign.py --config CONFIG --output RESULTS --mirror MIRROR` in a detached supervisor with stdout/stderr redirected to log files. Do not reuse existing result/scratch directories. To interrupt, send SIGTERM to the orchestrator PID recorded in `status.json`; it records interruption and cleans up only its own child processes/cgroups. Run `python3 benchmarks/summarize.py RESULTS` only during the subsequent result-review step.
