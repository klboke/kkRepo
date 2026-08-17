# Local Hugging Face Models Performance Baseline Against Nexus

This document records the implementation baseline for the Models-only Hugging Face proxy. It is a
same-host protocol comparison, not a production capacity result. Correct commit, headers, bytes,
SHA-256, Range body, and absence of client-visible Xet/external routing are checked before any
sample is accepted.

The [Chinese version](../../zh/dev/hugging-face-models-performance-baseline.md) contains the same
measurements.

## Environment And Method

- Time: `2026-08-17T14:42:57Z` for the warm confirmation.
- Host: Intel Core i9-9880H, 64 GiB RAM, macOS 14.7.8 x86_64.
- Runtime: Docker 29.4.0 (OrbStack); neither repository container had a separate CPU/memory limit.
- Reference: Sonatype Nexus Repository `3.94.0-12`, PostgreSQL datastore, File blob store.
- Candidate: kkRepo `0.8.0` development image
  `sha256:74e3ca5e645c0bed9c60241b82e77f8268924b441a17a55413d9215581a59b29`,
  PostgreSQL 17.10, File blob store.
- Both JVMs used `-Xms512m -Xmx1536m`.
- Fixture: a deterministic 4 MiB Xet-backed file, commit
  `0123456789abcdef0123456789abcdef01234567`, SHA-256
  `74a18e3f48369ee8c8e7cd03bd8b786591b0c19e2ee4df6ec97e74bef0c849d8`.

The [comparison runner](../../../scripts/perf/compare-huggingface-nexus.py) validates model info GET
and `304`, model-file HEAD/full GET/64 KiB Range, and rejects mismatched content or leaked
`Location`, `X-Xet-Hash`, Xet Link, and upstream host values. Each warm scenario used 64 warmups,
then 500 requests at concurrency 16 for five alternating-order rounds. The table reports the
median per-round summary; all 25,000 timed requests succeeded.

Warm metadata gates require at least `0.80x` Nexus throughput and at most `1.25x` Nexus p95. File
GET/Range gates require at least `0.90x` throughput and at most `1.15x` p95. Cold fill was measured
separately over five newly created repository pairs, alternating target order, so one noisy first
fill cannot decide the result.

## Results

The warm runner reported an empty `gate_failures` list.

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| model info GET | 1827.02 | 2117.62 | 1.159x | 16.586 ms | 11.952 ms | 0.721x |
| model info 304 | 1462.52 | 2339.78 | 1.600x | 22.092 ms | 11.103 ms | 0.503x |
| model file HEAD | 1877.90 | 2687.45 | 1.431x | 16.308 ms | 11.241 ms | 0.689x |
| 4 MiB model file GET | 365.61 | 483.70 | 1.323x | 76.317 ms | 51.706 ms | 0.678x |
| model file Range 64 KiB | 1207.30 | 1748.69 | 1.448x | 26.346 ms | 17.531 ms | 0.665x |

Full-file median throughput was `1462.42 MiB/s` for Nexus and `1934.78 MiB/s` for kkRepo.

### Cold Fill

Each sample used a new repository and the immutable commit route. The fixture saw exactly one
resolve request and one full CDN-body request per repository.

| Target | Samples | Median total | Median TTFB | Median throughput |
| --- | ---: | ---: | ---: | ---: |
| Nexus 3.94 | 5 | 213.796 ms | 207.789 ms | 18.709 MiB/s |
| kkRepo | 5 | 211.981 ms | 204.140 ms | 18.870 MiB/s |

kkRepo/Nexus cold throughput was `1.009x`, above the `0.90x` gate. All ten responses were 200 with
the exact 4 MiB SHA-256.

### S3-Compatible And Multi-Replica Evidence

The same candidate image was also run with PostgreSQL 17.10 and MinIO
`RELEASE.2025-04-22T22-12-26Z` through the AWS S3-compatible adapter:

- one-replica cold fill: `313.645 ms`, TTFB `302.145 ms`, `13.370 MiB/s`;
- subsequent warm GET: `29.051 ms`;
- two replicas concurrently missing the same 4 MiB file: both returned 200 and the exact SHA-256,
  while the fixture recorded one resolve and one full-body transfer; the durable row was published
  as `READY` with fencing token `1`.

This confirms the production storage path and database singleflight semantics. The S3 numbers are
absolute local-MinIO observations, not a Nexus comparison.

## Real Client Confirmation

The candidate also passed these non-timed client checks:

- `huggingface_hub` 1.27.0 and 0.34.6: `hf_hub_download` and `snapshot_download`;
- `hf` CLI 1.27.0: single-file and filtered snapshot download;
- Transformers 5.15.0: `AutoConfig` and `AutoTokenizer`;
- Transformers 4.49.0 with PyTorch 2.2.2: `AutoModel.from_pretrained`, yielding a
  `BertModel` with 87,929 parameters;
- Diffusers 0.35.2: complete 15-file snapshot and `StableDiffusionPipeline.from_pretrained`;
- installed Xet support without `HF_HUB_DISABLE_XET`: downloads remained on kkRepo and the
  controlled upstream recorded no client-side Xet-token call.

The public smoke used `hf-internal-testing/tiny-random-bert` and
`hf-internal-testing/tiny-stable-diffusion-pipe`. Nexus 3.94 accepted the legacy 0.34.6 snapshot
flow but returned 400 on the current 1.27.0 tree/paths flow; kkRepo supports both lines.

## Raw Data And Limits

The complete warm result, including all 50 per-round measurements and correctness preflights, is
[`huggingface-models-nexus-warm-2026-08-17.json`](../../perf-data/huggingface-models-nexus-warm-2026-08-17.json)
(SHA-256 `b271d188dfb57b4ba3483e20e49dcfd96cb1e88f6db8b2d76fe75ece6941cd93`).
Cold and S3/multi-replica samples are in
[`huggingface-models-cold-s3-2026-08-17.json`](../../perf-data/huggingface-models-cold-s3-2026-08-17.json)
(SHA-256 `74389375dc1f2b8192971b164077974044dd74789da5d72610c799bbaf93aa6f`).

These measurements establish protocol regression gates, not a production SLA. TLS, reverse proxy,
remote cloud object storage, cross-zone databases, mixed cleanup/scan load, 256 MiB/5 GiB files,
and million-row capacity must be tested against the intended deployment before setting limits.

## Reproduce

Start the deterministic fixture, point fresh Nexus and kkRepo `huggingface-proxy` repositories at
it, populate the immutable file once for the warm run, and execute:

```bash
python3 scripts/perf/compare-huggingface-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:59090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --file-sha256 74a18e3f48369ee8c8e7cd03bd8b786591b0c19e2ee4df6ec97e74bef0c849d8 \
  --file-size 4194304 \
  --requests 500 --concurrency 16 --warmups 64 --rounds 5 \
  --skip-cold --enforce-gates \
  --output /tmp/huggingface-performance.json
```

Use newly created repository pairs for cold samples. Keep credentials outside the repository and
check in only sanitized output.
