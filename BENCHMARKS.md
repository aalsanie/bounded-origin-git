# Linux/cgit campaign: October 3, 2026

The campaign completed all **1,344 cells**. Prepared pages avoided request-time cgit work; warm nginx was faster, and client comparisons had lower coverage and higher latency. The [README](README.md) shows the main results.

## Evidence and acceptance

We verified all **12 runs** (two warmups and ten measured) and **29,617 evidence files**. The Windows copy matched, and no unexpected failures or leftover processes were found. Raw data is unchanged; [audit.json](benchmarks/results/2026-10-03/audit.json) records the checks and totals.

Source and evidence identity:

| Item | Identity |
| --- | --- |
| Git base | `9f1584a81f26a9ec56d3086a05a144a13ef0f167` |
| Frozen source archive SHA-256 | `a7b8faea86a1a74d7ad3af6665e8ed1a903c486cafbec82de6b9fead159499d7` |
| Bundle inventory SHA-256 | `a5ce1d0b87c4b140e048120f136119d79e1b07fc7d59825378e97909c78e88fa` |
| Original result inventory SHA-256 | `b8f60c8e79d0db038d29dc6ae77505996ed8d72e7af73727d0cb82d7336b368c` |
| Run interval (UTC) | `2026-10-03T12:07:48Z` to `2026-10-03T17:40:50Z` |
| Linux dataset HEAD | `adc218676eef25575469234709c2d87185ca223a` (`v6.12`) |
| Bounded Origin artifacts | `0.1.0`, external Maven Central dependencies |


## Design and statistical interpretation

The [full protocol](benchmarks/protocol.md) specifies requests, limits, seeds and validation. The server uses CPUs 0–1 and a two-core quota, 4 GiB memory including page cache, no extra swap and 512 tasks. Clients use CPUs 2–3, the same CPU quota, 2 GiB memory and 512 tasks. Native cgit concurrency is two in **every** configuration. Runtime processes and caches restart for every cell. “Cold” refers to application state; OS page cache was not dropped.

The host was WSL2/Kali on an AMD Ryzen 9 5900HX, with 14 visible logical CPUs and approximately 15.7 GiB RAM. The kernel was `5.15.153.1-microsoft-standard-WSL2`. This is one machine and one scheduling environment. CPU pinning and cgroups reduce interference, but confidence intervals do not capture portability to other machines, production traffic or the full Linux history.

Tools were Java 21.0.9, Node 22.11.0, Anubis 1.27.0, nginx 1.26.3 and cgit `1.2.3+git20250818.80.3346409+git2.51.0-1`. Exact package URLs, binary, shared-library, JDK and dependency hashes accompany the evidence. A private loopback network namespace and fixed service ports outside the ephemeral range prevent unrelated listeners from interfering.

The dataset is a bare depth-32 clone with 2,055 reachable commits and 158,681 objects. Shallow boundary commits are excluded from the selected non-merge commit catalogue. Four repository namespaces share the same objects; this is not four independent histories. The workload is a bounded, explicitly enumerated sample of the combinatorial problem.

Each workload has the same request order across modes within a repetition; cell order is shuffled with seed `424242 + repetition`. Measured repetitions use paired workload seeds. Means, medians, sample standard deviations and seeded percentile bootstrap 95% confidence intervals (10,000 resamples) use **ten repetition-level values**, not individual requests as independent replicates. An interval of `[0, 0]` for observed origin work describes these samples, not a universal statistical guarantee. Intervals are descriptive and not adjusted for multiple comparisons.

Request p50/p95/p99 use linear interpolation between ordered values. Reported mean p95 is the mean of ten within-cell p95s, not a pooled percentile. All outcomes are included. Successful throughput counts delivered content only; attempted throughput also includes rejected work. Process startup is outside request timing. Module loading is included in client CPU, wire totals and batch wall time, but precedes the per-request timer. Human-mix batch time includes the specified pauses. Fresh service processes mean that request measurements still include JVM/client warmup.

Native CPU measures cgit itself; total server CPU also includes the benchmark's Python transport. Byte counts cover HTTP traffic, excluding TCP/IP overhead. Browser assets and rendering are excluded.

## Comparison coverage

The client returns structured comparison data; cgit returns HTML. The campaign validates successful client changed paths, object IDs and modes against native Git with rename detection disabled. Plain files use exact SHA-256 checks; native commit/diff pages must identify the requested OID.

<!-- generated:comparisons -->

| Pair (old → new) | Presentation | Outcomes over all ten repetitions |
| --- | --- | --- |
| 0740e54304dc → 8ce41b0f9d77 | mode1 | delivered: 40 |
| 09663753bb7c → 31daa34315d4 | mode2 | Git tree entry budget exceeded: 40 |
| 31daa34315d4 → fd7b4f9f46d4 | mode1 | delivered: 40 |
| 32c4514455b2 → a4af89cc50f3 | mode0 | Git tree entry budget exceeded: 40 |
| 580bb355bcae → a39326767c55 | mode1 | Git tree entry budget exceeded: 40 |
| 669b0cb81e4e → 0740e54304dc | mode2 | delivered: 40 |
| 7013a8268d31 → 44f392fbf628 | mode2 | line diff complexity budget exceeded: 40 |
| 737f34137844 → d1aa0c04294e | mode1 | delivered: 40 |
| 7d493a5ecc26 → a4a282daf1a1 | mode0 | Git file budget exceeded: 40 |
| 86fb6173d11e → 09663753bb7c | mode1 | Git tree entry budget exceeded: 40 |
| 8ce41b0f9d77 → 737f34137844 | mode0 | delivered: 40 |
| 8eb36164d1a6 → 7d493a5ecc26 | mode2 | Git file budget exceeded: 40 |
| ca34aceb322b → 0ec8bc9e880e | mode2 | blob exceeds configured line limit: 40 |
| dc065076ee77 → 580bb355bcae | mode0 | Git tree entry budget exceeded: 40 |
| f66d6acccbc0 → adc218676eef | mode0 | delivered: 40 |
| fd7b4f9f46d4 → 669b0cb81e4e | mode0 | delivered: 40 |

<!-- /generated:comparisons -->

The default engine limits include 4,096 objects, 32 MiB cumulative object bytes, 50,000 tree entries, 4,096 file entries, 20,000 lines per blob, 50,000 total lines and 1,000,000 line-diff cells. Session object caches are 8 MiB / 4,096 entries.

Preparation and ingestion costs are [reported separately](README.md#preparation-cost).

## Resources and controls

Memory peaks include preparation; ingestion is excluded.

<!-- generated:memory -->

| Configuration | Max sampled server RSS (MiB) | Max server cgroup high water (MiB) | Max client cgroup high water (MiB) |
| --- | --- | --- | --- |
| cgit | 994.3 | 495.9 | 515.5 |
| nginx cold | 942.6 | 465.4 | 466.6 |
| nginx warm | 81.1 | 307.7 | 627.1 |
| Anubis d4 solve | 1,053.1 | 517.8 | 573.2 |
| Anubis d5 solve | 1,049.2 | 509.9 | 710.7 |
| Anubis d5 no solve | 61.0 | 32.6 | 383.9 |
| BO cold | 184.9 | 164.6 | 415.5 |
| BO prepared | 184.5 | 178.5 | 418.2 |

<!-- /generated:memory -->

Limits were **4 GiB server / 2 GiB client**; see the [admission and failure results](README.md#admission-and-ref-updates) for execution bounds and cleanup checks.

## Paired effects and complete results

The following are paired BO-prepared minus direct-cgit differences within the same repetition. Negative CPU/latency differences mean lower cost/time; negative delivery differences mean worse coverage. Comparison cost differences have unequal delivered coverage and must not be interpreted as equivalent-work speedups.

<!-- generated:paired -->

| Workload | BO prepared − cgit | Mean difference [95% CI] |
| --- | --- | --- |
| unique-crawl | origin_cpu_seconds_per_1000_attempts | -4.733 [-4.758, -4.710] |
| repeat-burst | origin_cpu_seconds_per_1000_attempts | -6.932 [-6.975, -6.897] |
| comparisons | origin_cpu_seconds_per_1000_attempts | -1,235.677 [-1,240.117, -1,231.522] |
| human-mix | origin_cpu_seconds_per_1000_attempts | -5.155 [-5.432, -4.870] |
| unique-crawl | server_cpu_seconds | -12.424 [-12.505, -12.367] |
| repeat-burst | server_cpu_seconds | -13.613 [-13.742, -13.500] |
| comparisons | server_cpu_seconds | -112.767 [-113.280, -112.275] |
| human-mix | server_cpu_seconds | -0.469 [-0.511, -0.430] |
| unique-crawl | client_cpu_seconds | -0.013 [-0.040, 0.013] |
| repeat-burst | client_cpu_seconds | -0.108 [-0.164, -0.062] |
| comparisons | client_cpu_seconds | 13.766 [13.625, 13.936] |
| human-mix | client_cpu_seconds | 0.193 [0.176, 0.212] |
| unique-crawl | p95_ms | -210.888 [-213.808, -207.815] |
| repeat-burst | p95_ms | -233.010 [-236.755, -229.241] |
| comparisons | p95_ms | 39,942.788 [39,673.994, 40,202.840] |
| human-mix | p95_ms | 333.865 [282.453, 388.232] |
| unique-crawl | delivered_fraction | 0.000 [0.000, 0.000] |
| repeat-burst | delivered_fraction | 0.000 [0.000, 0.000] |
| comparisons | delivered_fraction | -0.562 [-0.562, -0.562] |
| human-mix | delivered_fraction | -0.048 [-0.065, -0.033] |

<!-- /generated:paired -->

All configurations and phases follow. `scale label 256` is the protocol selector, not always the request count: the **Attempts** column is authoritative. Full means, medians, standard deviations, p50/p95/p99 and confidence intervals are in [`aggregates.csv`](benchmarks/results/2026-10-03/aggregates.csv); per-repetition counts, resource, cache, I/O and transfer values are in [`cells.csv`](benchmarks/results/2026-10-03/cells.csv).

<!-- generated:matrix -->

### alias-flood, scale label 32, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 6.842 [6.787, 6.893] | 32.0 | 0.946 | 0.848 | 291.7 | 34.55 |
| nginx cold | 32 | 100.00% | 3.407 [3.368, 3.448] | 16.0 | 0.485 | 0.842 | 215.8 | 50.05 |
| nginx warm | 32 | 100.00% | 2.574 [2.517, 2.631] | 12.0 | 0.369 | 0.860 | 148.2 | 54.33 |
| Anubis d4 solve | 32 | 100.00% | 7.183 [7.080, 7.293] | 32.0 | 1.033 | 2.141 | 763.8 | 24.98 |
| Anubis d5 solve | 32 | 100.00% | 6.841 [6.795, 6.887] | 32.0 | 0.985 | 14.452 | 6,271.4 | 4.24 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.019 | 0.899 | 58.4 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.299 | 0.923 | 127.0 | 0.00 |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.253 | 0.931 | 116.4 | 48.14 |

### alias-flood, scale label 128, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 128 | 100.00% | 6.900 [6.843, 6.970] | 128.0 | 3.802 | 0.977 | 286.8 | 53.01 |
| nginx cold | 128 | 100.00% | 0.852 [0.835, 0.870] | 16.0 | 0.496 | 0.931 | 153.6 | 192.38 |
| nginx warm | 128 | 100.00% | 0.658 [0.649, 0.667] | 12.0 | 0.382 | 0.955 | 94.2 | 211.89 |
| Anubis d4 solve | 128 | 100.00% | 7.130 [7.062, 7.194] | 128.0 | 4.014 | 2.441 | 691.5 | 46.19 |
| Anubis d5 solve | 128 | 100.00% | 6.987 [6.882, 7.121] | 128.0 | 3.947 | 16.689 | 4,983.0 | 14.68 |
| Anubis d5 no solve | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.047 | 1.006 | 40.9 | 0.00 |
| BO cold | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.623 | 1.048 | 116.5 | 0.00 |
| BO prepared | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.619 | 1.081 | 101.6 | 150.49 |

### alias-flood, scale label 512, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 512 | 100.00% | 6.927 [6.916, 6.938] | 512.0 | 15.204 | 1.474 | 279.9 | 61.20 |
| nginx cold | 512 | 100.00% | 0.222 [0.219, 0.225] | 16.0 | 0.533 | 1.150 | 2.1 | 752.85 |
| nginx warm | 512 | 100.00% | 0.162 [0.160, 0.164] | 12.0 | 0.395 | 1.193 | 18.3 | 758.28 |
| Anubis d4 solve | 512 | 100.00% | 6.950 [6.917, 6.987] | 512.0 | 15.559 | 2.668 | 289.9 | 58.09 |
| Anubis d5 solve | 512 | 100.00% | 7.371 [7.278, 7.479] | 512.0 | 16.649 | 17.226 | 169.1 | 44.06 |
| Anubis d5 no solve | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.150 | 1.322 | 18.0 | 0.00 |
| BO cold | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 1.525 | 1.323 | 44.2 | 0.00 |
| BO prepared | 512 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 1.678 | 1.389 | 50.3 | 370.48 |

### comparisons, scale label 256, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 64 | 100.00% | 1,235.677 [1,231.596, 1,240.156] | 64.0 | 118.155 | 2.288 | 10,454.5 | 0.98 |
| nginx cold | 64 | 100.00% | 318.703 [316.658, 320.613] | 16.0 | 31.805 | 2.126 | 5,970.0 | 3.66 |
| nginx warm | 64 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.179 | 2.122 | 90.9 | 49.12 |
| Anubis d4 solve | 64 | 100.00% | 1,236.238 [1,229.747, 1,242.394] | 64.0 | 118.713 | 2.619 | 9,932.8 | 0.98 |
| Anubis d5 solve | 64 | 100.00% | 1,232.269 [1,224.612, 1,239.807] | 64.0 | 117.668 | 6.269 | 8,548.0 | 0.97 |
| Anubis d5 no solve | 64 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.024 | 0.800 | 9.3 | 0.00 |
| BO cold | 64 | 43.75% | 0.000 [0.000, 0.000] | 0.0 | 5.389 | 16.072 | 50,356.0 | 0.26 |
| BO prepared | 64 | 43.75% | 0.000 [0.000, 0.000] | 0.0 | 5.388 | 16.054 | 50,397.3 | 0.26 |

### human-mix, scale label 256, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 40 | 100.00% | 5.155 [4.868, 5.434] | 40.0 | 1.027 | 0.205 | 37.1 | 7.04 |
| nginx cold | 40 | 100.00% | 2.355 [2.200, 2.518] | 18.8 | 0.504 | 0.211 | 34.3 | 7.33 |
| nginx warm | 40 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.012 | 0.212 | 4.8 | 7.75 |
| Anubis d4 solve | 40 | 100.00% | 5.172 [4.894, 5.431] | 40.0 | 1.036 | 0.379 | 47.8 | 6.87 |
| Anubis d5 solve | 40 | 100.00% | 5.208 [4.942, 5.453] | 40.0 | 1.046 | 2.020 | 74.8 | 5.81 |
| Anubis d5 no solve | 40 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.035 | 0.211 | 2.9 | 0.00 |
| BO cold | 40 | 15.75% | 0.000 [0.000, 0.000] | 0.0 | 0.554 | 0.368 | 367.5 | 0.86 |
| BO prepared | 40 | 95.25% | 0.000 [0.000, 0.000] | 0.0 | 0.558 | 0.398 | 370.9 | 5.20 |

### large-plain, scale label 256, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 6.761 [6.621, 6.935] | 32.0 | 1.039 | 0.726 | 150.4 | 39.54 |
| nginx cold | 32 | 100.00% | 0.219 [0.214, 0.223] | 1.0 | 0.085 | 0.733 | 541.6 | 35.68 |
| nginx warm | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.050 | 0.759 | 61.7 | 73.00 |
| Anubis d4 solve | 32 | 100.00% | 6.971 [6.852, 7.096] | 32.0 | 1.138 | 1.375 | 403.6 | 32.49 |
| Anubis d5 solve | 32 | 100.00% | 7.269 [7.224, 7.317] | 32.0 | 1.185 | 7.593 | 2,619.7 | 7.83 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.017 | 0.492 | 22.0 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.272 | 0.494 | 71.3 | 0.00 |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 1.327 | 1.146 | 269.3 | 32.50 |

### ref-update, scale label 256, after-publication

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 1.950 [1.930, 1.979] | 32.0 | 0.689 | 0.279 | 77.3 | 59.94 |
| nginx cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.002 | 0.271 | 9.6 | 0.00 |
| nginx warm | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.002 | 0.271 | 10.3 | 0.00 |
| Anubis d4 solve | 32 | 100.00% | 1.996 [1.974, 2.017] | 32.0 | 0.719 | 0.601 | 154.3 | 47.76 |
| Anubis d5 solve | 32 | 100.00% | 2.256 [2.214, 2.297] | 32.0 | 0.761 | 5.178 | 1,633.6 | 11.39 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.012 | 0.270 | 10.9 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.164 | 0.302 | 12.2 | 0.00 |
| BO prepared | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.148 | 0.292 | 10.2 | 0.00 |

### ref-update, scale label 256, after-update-preparation

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.197 | 0.305 | 15.4 | 100.17 |

### ref-update, scale label 256, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 1.952 [1.943, 1.962] | 32.0 | 0.686 | 0.279 | 82.9 | 58.83 |
| nginx cold | 32 | 100.00% | 0.069 [0.068, 0.071] | 1.0 | 0.026 | 0.264 | 507.6 | 47.57 |
| nginx warm | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.003 | 0.278 | 10.0 | 159.31 |
| Anubis d4 solve | 32 | 100.00% | 2.031 [2.001, 2.072] | 32.0 | 0.730 | 0.579 | 153.0 | 49.97 |
| Anubis d5 solve | 32 | 100.00% | 2.306 [2.279, 2.341] | 32.0 | 0.762 | 3.921 | 1,165.0 | 12.89 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.015 | 0.273 | 11.4 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.272 | 0.298 | 52.8 | 0.00 |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.302 | 0.310 | 34.5 | 87.90 |

### repeat-burst, scale label 32, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 6.818 [6.732, 6.893] | 32.0 | 0.944 | 0.863 | 290.1 | 34.75 |
| nginx cold | 32 | 100.00% | 0.880 [0.851, 0.910] | 4.0 | 0.128 | 0.851 | 519.5 | 34.32 |
| nginx warm | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.003 | 0.888 | 48.5 | 62.92 |
| Anubis d4 solve | 32 | 100.00% | 7.252 [7.142, 7.368] | 32.0 | 1.047 | 2.174 | 744.5 | 24.96 |
| Anubis d5 solve | 32 | 100.00% | 6.828 [6.760, 6.900] | 32.0 | 0.988 | 15.755 | 6,878.2 | 3.89 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.020 | 0.909 | 62.4 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.297 | 0.922 | 123.0 | 0.00 |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.262 | 0.928 | 119.6 | 47.07 |

### repeat-burst, scale label 128, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 128 | 100.00% | 6.889 [6.850, 6.927] | 128.0 | 3.793 | 1.000 | 285.8 | 52.79 |
| nginx cold | 128 | 100.00% | 0.220 [0.211, 0.228] | 4.0 | 0.131 | 0.946 | 507.4 | 130.92 |
| nginx warm | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.007 | 1.019 | 40.5 | 222.81 |
| Anubis d4 solve | 128 | 100.00% | 7.085 [7.029, 7.142] | 128.0 | 3.978 | 2.379 | 653.8 | 46.48 |
| Anubis d5 solve | 128 | 100.00% | 7.046 [6.967, 7.138] | 128.0 | 3.998 | 17.783 | 5,119.5 | 13.71 |
| Anubis d5 no solve | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.047 | 1.027 | 44.6 | 0.00 |
| BO cold | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.627 | 1.048 | 121.2 | 0.00 |
| BO prepared | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.607 | 1.052 | 114.3 | 150.59 |

### repeat-burst, scale label 512, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 512 | 100.00% | 6.932 [6.897, 6.973] | 512.0 | 15.243 | 1.485 | 281.3 | 60.96 |
| nginx cold | 512 | 100.00% | 0.054 [0.052, 0.056] | 4.0 | 0.145 | 1.191 | 17.4 | 477.88 |
| nginx warm | 512 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.020 | 1.198 | 24.4 | 770.33 |
| Anubis d4 solve | 512 | 100.00% | 6.956 [6.916, 6.993] | 512.0 | 15.573 | 2.854 | 290.4 | 57.89 |
| Anubis d5 solve | 512 | 100.00% | 7.362 [7.288, 7.445] | 512.0 | 16.621 | 17.269 | 167.6 | 44.23 |
| Anubis d5 no solve | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.151 | 1.308 | 16.9 | 0.00 |
| BO cold | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 1.494 | 1.324 | 44.9 | 0.00 |
| BO prepared | 512 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 1.630 | 1.377 | 48.3 | 377.17 |

### session-churn, scale label 256, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 128 | 100.00% | 4.666 [4.617, 4.717] | 128.0 | 3.395 | 0.593 | 149.9 | 61.74 |
| nginx cold | 128 | 100.00% | 1.152 [1.138, 1.165] | 32.0 | 0.872 | 0.535 | 122.2 | 176.54 |
| nginx warm | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.006 | 0.543 | 15.2 | 395.79 |
| Anubis d4 solve | 128 | 100.00% | 4.889 [4.835, 4.950] | 128.0 | 3.664 | 2.679 | 243.9 | 53.02 |
| Anubis d5 solve | 128 | 100.00% | 4.684 [4.671, 4.696] | 128.0 | 3.575 | 32.051 | 4,896.5 | 7.59 |
| Anubis d5 no solve | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.043 | 0.557 | 14.4 | 0.00 |
| BO cold | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.616 | 0.583 | 57.2 | 0.00 |
| BO prepared | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.559 | 0.598 | 58.4 | 210.40 |

### unique-crawl, scale label 32, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 32 | 100.00% | 4.738 [4.563, 4.916] | 32.0 | 0.852 | 0.852 | 274.8 | 36.34 |
| nginx cold | 32 | 100.00% | 4.670 [4.492, 4.873] | 32.0 | 0.869 | 0.837 | 240.8 | 38.10 |
| nginx warm | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.003 | 0.892 | 57.6 | 62.17 |
| Anubis d4 solve | 32 | 100.00% | 4.978 [4.789, 5.195] | 32.0 | 0.937 | 2.365 | 854.1 | 23.34 |
| Anubis d5 solve | 32 | 100.00% | 4.804 [4.635, 5.003] | 32.0 | 0.914 | 15.520 | 6,817.3 | 4.08 |
| Anubis d5 no solve | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.020 | 0.916 | 58.0 | 0.00 |
| BO cold | 32 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.300 | 0.947 | 118.6 | 0.00 |
| BO prepared | 32 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.239 | 0.931 | 97.6 | 48.44 |

### unique-crawl, scale label 128, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 128 | 100.00% | 4.725 [4.638, 4.827] | 128.0 | 3.403 | 1.001 | 267.5 | 57.42 |
| nginx cold | 128 | 100.00% | 4.722 [4.616, 4.845] | 128.0 | 3.491 | 0.981 | 260.0 | 55.06 |
| nginx warm | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.007 | 0.989 | 39.6 | 231.33 |
| Anubis d4 solve | 128 | 100.00% | 4.897 [4.786, 5.023] | 128.0 | 3.586 | 2.248 | 616.9 | 50.50 |
| Anubis d5 solve | 128 | 100.00% | 4.851 [4.769, 4.939] | 128.0 | 3.626 | 17.683 | 4,866.4 | 13.87 |
| Anubis d5 no solve | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.048 | 1.023 | 43.1 | 0.00 |
| BO cold | 128 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.601 | 1.051 | 104.4 | 0.00 |
| BO prepared | 128 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.495 | 1.062 | 101.0 | 160.00 |

### unique-crawl, scale label 512, requests

| Mode | Attempts | Delivered | Native CPU s / 1,000 (95% CI) | CGI executions | Server CPU s | Client CPU s | p95 ms | Delivered/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cgit | 512 | 100.00% | 4.733 [4.710, 4.758] | 512.0 | 13.627 | 1.430 | 248.8 | 67.62 |
| nginx cold | 512 | 100.00% | 4.653 [4.625, 4.679] | 512.0 | 13.815 | 1.380 | 261.8 | 63.34 |
| nginx warm | 512 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 0.023 | 1.218 | 22.3 | 758.26 |
| Anubis d4 solve | 512 | 100.00% | 4.794 [4.768, 4.821] | 512.0 | 14.101 | 2.828 | 256.2 | 63.71 |
| Anubis d5 solve | 512 | 100.00% | 5.062 [5.008, 5.111] | 512.0 | 15.044 | 16.813 | 162.6 | 45.00 |
| Anubis d5 no solve | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 0.149 | 1.301 | 16.9 | 0.00 |
| BO cold | 512 | 0.00% | 0.000 [0.000, 0.000] | 0.0 | 1.476 | 1.324 | 41.8 | 0.00 |
| BO prepared | 512 | 100.00% | 0.000 [0.000, 0.000] | 0.0 | 1.202 | 1.417 | 37.9 | 436.88 |


<!-- /generated:matrix -->

## Evidence layout and reproduction

| File | Contents |
| --- | --- |
| `requests.jsonl.gz` | Every original request row, including warmups, failures, error reasons, comparison results, latency, proof hashes and transfer/cache counters; augmented with cell/phase identity |
| `native.jsonl.gz` | Every original cgit start/end event, including controls; augmented with cell identity |
| `support.jsonl.gz` | Measurements, preparation, controls, process/resource samples and normalized plans; local command/cwd/cgroup labels and endpoint addresses are omitted |
| `catalogue.json.gz` | Deduplicated full request definitions and native Git comparison oracle; normalized plans refer to these SHA-256 keys |
| `result-inventory.json.gz` | Original per-file hashes for the complete archived evidence, including service logs not copied into this compact package |
| `source.tar.gz`, `source-files.json`, `source.json` | Exact frozen source, file hashes and base revision |
| `bundle-files.json`, `jdk-files.json`, `packages.json`, `environment.json` | Runtime, dependency and environment provenance |
| `cells.csv`, `aggregates.csv`, `preparation.csv`, `ingestion.csv`, `controls.csv`, `paired.csv` | Frozen analyzer outputs; cells additionally include client memory high water, native peak RSS and proof time |
| `audit.json`, `export-inventory.json` | Completed independent integrity checks and compact-package hashes |
| `generated/` | Reproducible SVG/PNG figures and paired estimates |

The compact package keeps all observations needed to regenerate the publication. Duplicate plan entries are normalized without losing their definitions or indexes. Reconstruct a plan entry by looking up its `entry` key in the catalogue and restoring its recorded `index`. Support paths are relative to the original `raw/` directory. Resource JSONL paths have multiple records; ordinary JSON paths have one. Process commands and local paths are redacted, not claimed to be byte-identical files. 

Regenerate the published tables and figures from the committed evidence, on Windows or Linux:

```sh
python -m pip install -r benchmarks/plot-requirements.txt
python benchmarks/publish.py benchmarks/results/2026-10-03
```

To repeat the native experiment, use the exact source archive and the [protocol](benchmarks/protocol.md), package URLs and recorded hashes. Extract the archive into a new directory, build `./gradlew clean check benchmarkBundle`, and copy the resulting bundle to native Linux storage. Build the dataset from `git clone --bare --depth 32 --branch v6.12 https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git linux.git`; verify the recorded head, selected catalogue and object inventory. Clone transfer representation may differ; the recorded manifest identifies the measured dataset exactly.

Create a new manifest with `python3 benchmarks/prepare.py /path/to/linux.git /path/to/manifest.json`. Adapt `benchmarks/config.example.json` to explicit tool, bundle, manifest, provenance and **new** scratch/result paths. Keep the frozen source archive and its hash in provenance. Run helper tests, the runtime validator, a complete fixture validation and a real-data pilot before a new measured campaign. The example config intentionally contains placeholders and cannot be launched unchanged. Never reuse the archived result or scratch paths.

After successful completion, run the frozen analyzer, then the publication collector:

```sh
python3 benchmarks/summarize.py /path/to/completed-results
python3 benchmarks/collect.py /path/to/completed-results /path/to/new-export
```

`collect.py` is a post-campaign addition in this repository, so invoke it from the publication checkout, not from the older frozen source. It requires the original dataset, frozen bundle, JDK and libraries to verify their hashes and rebuild the native Git oracle. It refuses an existing export directory. 

## Final validation and audit

The post-campaign review independently reconstructed raw process and request evidence, then recalculated all 1,844 aggregate records across 121 groups, including every 10,000-resample confidence interval.

Resource sampling covers every request phase: the largest first-sample delay was 6.81 ms, largest last-sample gap 100.81 ms, and largest interval between samples 113.29 ms. Anubis logs show the two expected single-instance configuration warnings and no error-level entries. All 12,434 protected files in the original failed campaign retain their recorded hashes, sizes and timestamps.

Validation of the publication checkout:

| Platform | Java | JavaScript | Python | Build |
| --- | --- | --- | --- | --- |
| Windows | 72 passed, 2 native checks skipped | 22 passed | 14 passed, 6 POSIX/native checks skipped | `clean check benchmarkBundle` passed |
| Linux/WSL | 74 passed | 22 passed | 20 passed, including namespace checks | `clean check benchmarkBundle` passed |

```sh
python benchmarks/verify_statistics.py benchmarks/results/2026-10-03
```
