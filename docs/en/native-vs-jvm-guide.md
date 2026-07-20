# Native Image or JVM Selection Guide

kkRepo supports two runtime packaging modes:

- **JVM**, the default and recommended production mode, runs the Spring Boot executable jar on Java 25.
- **Native**, an opt-in experimental mode, applies Spring AOT processing and compiles kkRepo with GraalVM Native Image.

Native packaging is not enabled implicitly. Docker images and archive distributions remain JVM-based unless `--native` is passed explicitly.

## Benchmark Scope

The following baseline was measured on 2026-07-20 in this feature worktree, based on commit `7bf554581d0d01cd6fd222a8657d8fb94bc46d11`. Both candidates used the same source, MySQL database, File blob store, application configuration, Hikari pool (`maximum-pool-size=8`, `minimum-idle=2`), two CPU limit, and 1 GiB memory limit.

The host was an x86_64 Intel Core i9-9880H running Linux containers through OrbStack. Both runtimes used Java 25.0.3: Liberica JRE for JVM and Liberica NIK for Native Image. ApacheBench used HTTP keep-alive. Each runtime completed five warm-layer fresh-container startup runs, endpoint warm-up, three single-concurrency rounds, three 32-concurrency rounds, and 15-second 64-concurrency sustained runs. The 408,495 measured requests completed with zero failures.

This is a controlled local comparison, not a production SLA. Network object storage, large artifact transfer, remote proxy latency, external Apollo, and multi-node load balancing were outside this benchmark.

## Startup, Memory, and Package Size

| Metric | Native | JVM | Interpretation |
| --- | ---: | ---: | --- |
| Container creation to readiness, median | 1.037 s | 11.888 s | Native was 11.5x faster |
| Spring-reported startup, median | 0.639 s | 9.562 s | Native was 15.0x faster |
| Readiness range across five runs | 1.031-1.793 s | 11.295-14.762 s | Native had the lower restart window |
| Idle Docker memory, median | 170.4 MiB | 315.1 MiB | Native used 45.9% less |
| Idle process RSS | 134.4 MiB | 327.0 MiB | Native used 58.9% less |
| Idle CPU, median | 0.32% | 1.27% | Both were low; Native had less background work |
| Idle threads | 40 | 50 | Native used 20% fewer |
| Peak process RSS during load | 218.4 MiB | 389.4 MiB | Native used 43.9% less |
| Docker image content size | 238 MB | 388 MB | Native was 38.7% smaller |
| Docker local disk usage | 494 MB | 796 MB | Native was 37.9% smaller |

Under the 1 GiB container limit, the buildpack memory calculator configured the JVM with an approximately 381 MiB maximum heap. Different JVM memory policies can change the memory comparison.

## Request Performance

After warm-up, both modes had approximately 1 ms median latency in the single-concurrency runs. The larger difference appeared under sustained concurrency, where the warmed JVM generally delivered more throughput.

| 64-concurrency sustained endpoint | Native requests/s | JVM requests/s | JVM throughput change | Native P95/P99 | JVM P95/P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Actuator health | 765 | 911 | +19.1% | 65/1006 ms | 44/1032 ms |
| Browse page | 2,071 | 2,293 | +10.7% | 81/209 ms | 86/147 ms |
| Repositories API | 2,084 | 3,005 | +44.2% | 91/102 ms | 74/185 ms |

The three 32-concurrency, 10,000-request rounds showed the same steady-state direction:

- Browse median throughput was 2,529 requests/s on Native and 3,003 requests/s on JVM, a JVM advantage of 18.7%.
- Repositories API median throughput was 1,942 requests/s on Native and 2,438 requests/s on JVM, a JVM advantage of 25.5%.

The health endpoint showed occasional one-to-seven-second outliers in both modes because it traverses the shared MySQL health path. Do not use the health result alone as an application throughput estimate. The sustained Repositories run also showed that higher JVM throughput does not eliminate rare tail pauses: its P99 was higher than Native in that one run.

## Build Cost

The measured Native build took about 10 minutes 5 seconds, including about 8 minutes 8 seconds in Native Image compilation. The clean JVM image build took about 2 minutes 14 seconds. These measurements used local build caches and are directional rather than clean-room build benchmarks, but Native clearly has the higher CI and release-build cost.

The final end-to-end archive validation also exercised a Buildpacks tool-cache miss. It took 14 minutes 44 seconds to build the Native image and another 38 seconds to assemble both archives; GraalVM's image-generation portion was 6 minutes 29 seconds. The extracted executable then reached `UP` and returned HTTP 200 from the bootstrap endpoint on a plain Ubuntu 24.04 container backed by MySQL. This validates the release path but is not an additional startup benchmark because it reused the already initialized test database.

## Recommendation

Use the default JVM distribution when:

- kkRepo is a long-running repository service and warmed throughput is the main priority.
- The deployment uses external Apollo, OSS/S3 providers, uncommon protocol paths, or other integrations that have not yet passed Native client E2E.
- Operational maturity, diagnostics, profiling, or existing JVM tuning is more important than cold-start speed.

Consider the opt-in Native distribution when:

- Fast readiness is important for autoscaling, scale-to-zero, preview environments, or rapid replacement of failed replicas.
- The deployment is memory-constrained or runs many small replicas.
- The exact database, storage provider, authentication integrations, and client protocols have passed the Native client E2E workflow.

For current production deployments, JVM remains the default recommendation. Native remains experimental until real-client, object-storage, external-configuration, upgrade, and representative artifact-transfer coverage is comparable to the JVM path.

## Build and CI Commands

Default JVM artifacts require no extra option:

```bash
./scripts/build-docker-image.sh kkrepo:local
./scripts/build-dist.sh
```

Native artifacts require the explicit `--native` option:

```bash
./scripts/build-docker-image.sh --native kkrepo:native
./scripts/build-dist.sh --native
```

The default archives remain `kkrepo-<version>.tar.gz` and `kkrepo-<version>.zip`. Native archives include the target platform, for example `kkrepo-<version>-native-linux-amd64.tar.gz` and `.zip`. The Native archive contains `lib/kkrepo`; the JVM archive contains `lib/kkrepo.jar`. The shared `bin/start.sh` detects the packaged runtime automatically.

On a pull request, add the `run-native-client-e2e` label to trigger the independent Native client E2E workflow. It builds a Native candidate and runs the Linux real-client matrix against both MySQL and PostgreSQL. The existing `run-client-e2e` label continues to test the default JVM candidate.
