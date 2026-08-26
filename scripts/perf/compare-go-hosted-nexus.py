#!/usr/bin/env python3
"""Compare Go hosted and hosted-first group paths on Nexus and kkRepo.

The runner creates identical deterministic module fixtures in both hosted
repositories, verifies client-visible responses before timing, alternates the
target order, records a pre-warm sample, warms both targets to request-count
and duration thresholds, and reports steady-state medians.  It also publishes
unique modules to measure the complete hosted validation/persistence path.

This is a same-host directional benchmark, not a production SLA.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import http.client
import io
import json
import math
import pathlib
import platform
import re
import ssl
import statistics
import threading
import time
import urllib.parse
import zipfile
from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class Target:
    name: str
    hosted_url: str
    group_url: str
    authorization: str | None


@dataclass(frozen=True)
class Scenario:
    name: str
    repository: str
    route: str
    validation: str


@dataclass(frozen=True)
class Measurement:
    requests: int
    concurrency: int
    transfer_bytes: int
    wall_seconds: float
    requests_per_second: float
    mebibytes_per_second: float
    minimum_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float
    mean_ms: float


@dataclass(frozen=True)
class Warmup:
    requests: int
    wall_seconds: float


class RequestFailure(RuntimeError):
    pass


def authorization(value: str | None) -> str | None:
    if not value:
        return None
    return "Basic " + base64.b64encode(value.encode("utf-8")).decode("ascii")


def connection(parts: urllib.parse.SplitResult, timeout: float) -> http.client.HTTPConnection:
    if parts.scheme == "https":
        return http.client.HTTPSConnection(
            parts.hostname,
            parts.port or 443,
            timeout=timeout,
            context=ssl.create_default_context(),
        )
    if parts.scheme == "http":
        return http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    raise ValueError(f"unsupported URL scheme: {parts.scheme}")


def request_route(base_url: str, relative: str) -> tuple[urllib.parse.SplitResult, str]:
    base = urllib.parse.urlsplit(base_url.rstrip("/") + "/")
    relative_parts = urllib.parse.urlsplit(relative.lstrip("/"))
    route = base.path.rstrip("/") + "/" + relative_parts.path
    if relative_parts.query:
        route += "?" + relative_parts.query
    return base, route


def repository_url(target: Target, scenario: Scenario) -> str:
    if scenario.repository == "hosted":
        return target.hosted_url
    if scenario.repository == "group":
        return target.group_url
    raise ValueError(f"unknown repository kind: {scenario.repository}")


def headers(target: Target) -> dict[str, str]:
    result = {
        "Accept": "*/*",
        "User-Agent": "kkrepo-go-hosted-performance-comparison/1",
    }
    if target.authorization:
        result["Authorization"] = target.authorization
    return result


def one_get(
    client: http.client.HTTPConnection,
    target: Target,
    route: str,
) -> tuple[float, bytes, dict[str, str]]:
    started = time.perf_counter_ns()
    client.request("GET", route, headers=headers(target))
    response = client.getresponse()
    body = response.read()
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != 200:
        raise RequestFailure(
            f"{target.name} GET {route} returned HTTP {response.status}: {body[:300]!r}"
        )
    if not body:
        raise RequestFailure(f"{target.name} GET {route} returned an empty body")
    return elapsed_ms, body, {
        name.lower(): value for name, value in response.getheaders()
    }


def zip_manifest(body: bytes) -> list[dict[str, Any]]:
    manifest: list[dict[str, Any]] = []
    try:
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                value = archive.read(info)
                manifest.append({
                    "name": info.filename,
                    "size": len(value),
                    "sha256": hashlib.sha256(value).hexdigest(),
                })
    except (OSError, zipfile.BadZipFile) as error:
        raise RequestFailure("module archive is not a readable ZIP") from error
    return sorted(manifest, key=lambda item: item["name"])


def canonical(body: bytes, scenario: Scenario) -> Any:
    if scenario.validation == "list":
        try:
            return sorted(line for line in body.decode("utf-8").splitlines() if line)
        except UnicodeDecodeError as error:
            raise RequestFailure(f"{scenario.name} is not UTF-8") from error
    if scenario.validation in {"info", "latest"}:
        try:
            value = json.loads(body)
            version = value["Version"]
            timestamp = value["Time"]
            dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            return {"Version": version}
        except (KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RequestFailure(f"{scenario.name} is not valid Go version JSON") from error
    if scenario.validation == "mod":
        return {
            "size": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
        }
    if scenario.validation == "zip":
        return zip_manifest(body)
    raise ValueError(f"unknown validation: {scenario.validation}")


def preflight(target: Target, scenario: Scenario, timeout: float) -> Any:
    parts, route = request_route(repository_url(target, scenario), scenario.route)
    client = connection(parts, timeout)
    try:
        _, body, _ = one_get(client, target, route)
        return canonical(body, scenario)
    finally:
        client.close()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def measurement(timings: list[float], transfer_bytes: int, wall: float, workers: int) -> Measurement:
    return Measurement(
        requests=len(timings),
        concurrency=workers,
        transfer_bytes=transfer_bytes,
        wall_seconds=round(wall, 6),
        requests_per_second=round(len(timings) / wall, 2),
        mebibytes_per_second=round(transfer_bytes / wall / 1024 / 1024, 2),
        minimum_ms=round(min(timings), 3),
        p50_ms=round(percentile(timings, 0.50), 3),
        p95_ms=round(percentile(timings, 0.95), 3),
        p99_ms=round(percentile(timings, 0.99), 3),
        maximum_ms=round(max(timings), 3),
        mean_ms=round(statistics.fmean(timings), 3),
    )


def measure_get(
    target: Target,
    scenario: Scenario,
    requests: int,
    concurrency: int,
    timeout: float,
) -> Measurement:
    workers = max(1, min(concurrency, requests))
    assignments = [requests // workers] * workers
    for index in range(requests % workers):
        assignments[index] += 1
    barrier = threading.Barrier(workers)

    def worker(count: int) -> tuple[list[float], int]:
        parts, route = request_route(repository_url(target, scenario), scenario.route)
        client = connection(parts, timeout)
        timings: list[float] = []
        transferred = 0
        try:
            barrier.wait(timeout=timeout)
            for _ in range(count):
                elapsed, body, _ = one_get(client, target, route)
                timings.append(elapsed)
                transferred += len(body)
            return timings, transferred
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    transferred = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(worker, count) for count in assignments]
        for future in futures:
            worker_timings, worker_bytes = future.result()
            timings.extend(worker_timings)
            transferred += worker_bytes
    return measurement(timings, transferred, time.perf_counter() - started, workers)


def warmup_get(
    target: Target,
    scenario: Scenario,
    minimum_requests: int,
    minimum_seconds: float,
    concurrency: int,
    timeout: float,
) -> Warmup:
    """Warm a read path until both configured thresholds have been reached."""
    batch_requests = min(
        250,
        max(concurrency, minimum_requests if minimum_requests > 0 else 250),
    )
    started = time.perf_counter()
    completed = 0
    while completed < minimum_requests or time.perf_counter() - started < minimum_seconds:
        remaining = minimum_requests - completed
        requests = min(batch_requests, remaining) if remaining > 0 else batch_requests
        measure_get(
            target,
            scenario,
            requests,
            min(concurrency, requests),
            timeout,
        )
        completed += requests
    return Warmup(completed, round(time.perf_counter() - started, 6))


def zip_entry(name: str, data: bytes, compression: int) -> tuple[zipfile.ZipInfo, bytes]:
    info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    info.compress_type = compression
    info.external_attr = 0o100644 << 16
    return info, data


def module_archive(module: str, version: str, payload_bytes: int) -> bytes:
    root = f"{module}@{version}/"
    pattern = bytes((index * 31 + 17) % 256 for index in range(256))
    payload = (pattern * math.ceil(payload_bytes / len(pattern)))[:payload_bytes]
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=True) as archive:
        entries = [
            zip_entry(root + "go.mod", f"module {module}\n\ngo 1.22\n".encode(), zipfile.ZIP_DEFLATED),
            zip_entry(
                root + "perf.go",
                b"package perf\n\nconst Fixture = \"kkrepo-go-hosted-performance\"\n",
                zipfile.ZIP_DEFLATED,
            ),
            zip_entry(root + "testdata/payload.bin", payload, zipfile.ZIP_STORED),
        ]
        for info, data in entries:
            archive.writestr(info, data)
    return output.getvalue()


def publish(
    client: http.client.HTTPConnection,
    target: Target,
    route: str,
    body: bytes,
) -> float:
    request_headers = headers(target)
    request_headers["Content-Type"] = "application/zip"
    started = time.perf_counter_ns()
    client.request("PUT", route, body=body, headers=request_headers)
    response = client.getresponse()
    response_body = response.read()
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != 201:
        raise RequestFailure(
            f"{target.name} PUT {route} returned HTTP {response.status}: "
            f"{response_body[:300]!r}"
        )
    return elapsed_ms


def measure_publish(
    target: Target,
    fixtures: list[tuple[str, str, bytes]],
    concurrency: int,
    timeout: float,
) -> Measurement:
    workers = max(1, min(concurrency, len(fixtures)))
    assignments: list[list[tuple[str, str, bytes]]] = [[] for _ in range(workers)]
    for index, fixture in enumerate(fixtures):
        assignments[index % workers].append(fixture)
    barrier = threading.Barrier(workers)

    def worker(items: list[tuple[str, str, bytes]]) -> tuple[list[float], int]:
        parts, route = request_route(target.hosted_url, items[0][1] + ".zip")
        client = connection(parts, timeout)
        timings: list[float] = []
        transferred = 0
        try:
            barrier.wait(timeout=timeout)
            for _, version, body in items:
                _, item_route = request_route(target.hosted_url, version + ".zip")
                timings.append(publish(client, target, item_route, body))
                transferred += len(body)
            return timings, transferred
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    transferred = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(worker, items) for items in assignments]
        for future in futures:
            worker_timings, worker_bytes = future.result()
            timings.extend(worker_timings)
            transferred += worker_bytes
    return measurement(timings, transferred, time.perf_counter() - started, workers)


def publish_fixtures(
    target: Target,
    fixtures: list[tuple[str, str, bytes]],
    concurrency: int,
    timeout: float,
) -> None:
    measure_publish(target, fixtures, concurrency, timeout)


def verify_published(
    target: Target,
    fixtures: list[tuple[str, str, bytes]],
    timeout: float,
) -> None:
    for module, version, _ in (fixtures[0], fixtures[-1]):
        scenario = Scenario(
            "published module",
            "hosted",
            f"{module}/@v/{version}.mod",
            "mod",
        )
        preflight(target, scenario, timeout)


def median_measurement(values: list[Measurement]) -> Measurement:
    def median(name: str) -> float:
        return round(statistics.median(float(getattr(value, name)) for value in values), 3)

    return Measurement(
        requests=values[0].requests,
        concurrency=values[0].concurrency,
        transfer_bytes=int(statistics.median(value.transfer_bytes for value in values)),
        wall_seconds=median("wall_seconds"),
        requests_per_second=round(statistics.median(value.requests_per_second for value in values), 2),
        mebibytes_per_second=round(statistics.median(value.mebibytes_per_second for value in values), 2),
        minimum_ms=median("minimum_ms"),
        p50_ms=median("p50_ms"),
        p95_ms=median("p95_ms"),
        p99_ms=median("p99_ms"),
        maximum_ms=median("maximum_ms"),
        mean_ms=median("mean_ms"),
    )


def ratio(candidate: float, reference: float) -> float:
    return round(candidate / reference, 3) if reference else float("inf")


def comparison_summary(nexus: Measurement, candidate: Measurement) -> dict[str, Any]:
    return {
        "Nexus": asdict(nexus),
        "kkRepo": asdict(candidate),
        "throughput_ratio": ratio(
            candidate.requests_per_second, nexus.requests_per_second
        ),
        "p50_latency_ratio": ratio(candidate.p50_ms, nexus.p50_ms),
        "p95_latency_ratio": ratio(candidate.p95_ms, nexus.p95_ms),
    }


def append_summary_table(lines: list[str], summaries: dict[str, Any]) -> None:
    lines.extend([
        "| Scenario | Nexus req/s | kkRepo req/s | ratio | Nexus p50 ms | kkRepo p50 ms | Nexus p95 ms | kkRepo p95 ms |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for name, result in summaries.items():
        nexus = result["Nexus"]
        candidate = result["kkRepo"]
        lines.append(
            f"| {name} | {nexus['requests_per_second']:.2f} | "
            f"{candidate['requests_per_second']:.2f} | {result['throughput_ratio']:.3f}x | "
            f"{nexus['p50_ms']:.3f} | {candidate['p50_ms']:.3f} | "
            f"{nexus['p95_ms']:.3f} | {candidate['p95_ms']:.3f} |"
        )


def markdown(report: dict[str, Any]) -> str:
    publication_note = (
        " Publication rows are not pre-warmed."
        if "hosted publish" in report["summaries"]
        else ""
    )
    lines = [
        "# Go hosted Nexus / kkRepo performance comparison",
        "",
        f"Generated: `{report['generated_at']}`",
        "",
        "## Steady-state results",
        "",
        f"Read rows are the median of `{report['configuration']['rounds']}` independent rounds "
        f"after both targets reached the configured warmup thresholds.{publication_note}",
        "",
    ]
    append_summary_table(lines, report["summaries"])
    if report["prewarm_summaries"]:
        lines.extend([
            "",
            "## Pre-warm samples",
            "",
            "These samples run after correctness preflight but before sustained warmup. They are "
            "not process-cold measurements unless both services were restarted externally.",
            "",
        ])
        append_summary_table(lines, report["prewarm_summaries"])
    lines.extend([
        "",
        "> Ratios above 1.0 mean kkRepo has higher throughput. Results are directional local "
        "measurements; TLS, remote object storage, database HA, and load balancing are outside "
        "this run.",
        "",
    ])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nexus-hosted-url", required=True)
    parser.add_argument("--nexus-group-url", required=True)
    parser.add_argument("--kkrepo-hosted-url", required=True)
    parser.add_argument("--kkrepo-group-url", required=True)
    parser.add_argument("--nexus-auth", help="username:password")
    parser.add_argument("--kkrepo-auth", help="username:password")
    parser.add_argument("--module", default="example.com/kkrepo/go-performance")
    parser.add_argument("--version-count", type=int, default=12)
    parser.add_argument("--payload-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--requests", type=int, default=250)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--prewarm-requests", type=int, default=250)
    parser.add_argument(
        "--warmups",
        type=int,
        default=2000,
        help="minimum warmup requests per target and read scenario",
    )
    parser.add_argument(
        "--warmup-seconds",
        type=float,
        default=5.0,
        help="minimum warmup duration per target and read scenario",
    )
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--publish-requests", type=int, default=24)
    parser.add_argument("--publish-concurrency", type=int, default=8)
    parser.add_argument("--run-id", default=dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S"))
    parser.add_argument("--skip-prepare", action="store_true")
    parser.add_argument("--skip-publish", action="store_true")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    positive = [
        args.version_count,
        args.payload_bytes,
        args.requests,
        args.concurrency,
        args.rounds,
        args.publish_requests,
        args.publish_concurrency,
    ]
    if (
        any(value < 1 for value in positive)
        or args.prewarm_requests < 0
        or args.warmups < 0
        or args.warmup_seconds < 0
    ):
        raise SystemExit(
            "counts, sizes, concurrency, and rounds must be positive; "
            "pre-warm and warmup thresholds cannot be negative"
        )
    run_id = re.sub(r"[^a-zA-Z0-9-]", "-", args.run_id).strip("-").lower()
    if not run_id:
        raise SystemExit("--run-id must contain at least one letter or digit")

    targets = [
        Target(
            "Nexus",
            args.nexus_hosted_url,
            args.nexus_group_url,
            authorization(args.nexus_auth),
        ),
        Target(
            "kkRepo",
            args.kkrepo_hosted_url,
            args.kkrepo_group_url,
            authorization(args.kkrepo_auth),
        ),
    ]
    versions = [f"v1.0.{index}" for index in range(args.version_count)]
    prepared = [
        (args.module, version, module_archive(args.module, version, args.payload_bytes))
        for version in versions
    ]
    if not args.skip_prepare:
        for target in targets:
            publish_fixtures(target, prepared, args.publish_concurrency, args.timeout)
            verify_published(target, prepared, args.timeout)

    selected = versions[-1]
    scenarios = [
        Scenario("hosted list", "hosted", f"{args.module}/@v/list", "list"),
        Scenario("hosted info", "hosted", f"{args.module}/@v/{selected}.info", "info"),
        Scenario("hosted mod", "hosted", f"{args.module}/@v/{selected}.mod", "mod"),
        Scenario("hosted zip", "hosted", f"{args.module}/@v/{selected}.zip", "zip"),
        Scenario("hosted latest", "hosted", f"{args.module}/@latest", "latest"),
        Scenario("group list", "group", f"{args.module}/@v/list", "list"),
        Scenario("group latest", "group", f"{args.module}/@latest", "latest"),
        Scenario("group zip", "group", f"{args.module}/@v/{selected}.zip", "zip"),
    ]

    correctness: dict[str, Any] = {}
    for scenario in scenarios:
        values = {target.name: preflight(target, scenario, args.timeout) for target in targets}
        if values["Nexus"] != values["kkRepo"]:
            raise RequestFailure(f"correctness mismatch for {scenario.name}: {values}")
        correctness[scenario.name] = values["kkRepo"]

    samples: dict[str, dict[str, list[Measurement]]] = {
        scenario.name: {target.name: [] for target in targets} for scenario in scenarios
    }
    prewarm_samples: dict[str, dict[str, Measurement]] = {}
    warmup_results: dict[str, dict[str, Warmup]] = {}
    raw_results: list[dict[str, Any]] = []
    for scenario_index, scenario in enumerate(scenarios):
        ordered = targets if scenario_index % 2 == 0 else list(reversed(targets))
        if args.prewarm_requests:
            prewarm_samples[scenario.name] = {}
            for target in ordered:
                value = measure_get(
                    target,
                    scenario,
                    args.prewarm_requests,
                    min(args.concurrency, args.prewarm_requests),
                    args.timeout,
                )
                prewarm_samples[scenario.name][target.name] = value
                raw_results.append({
                    "phase": "pre-warm",
                    "round": 1,
                    "scenario": scenario.name,
                    "target": target.name,
                    "measurement": asdict(value),
                })
        warmup_results[scenario.name] = {}
        for target in ordered:
            warmup_results[scenario.name][target.name] = warmup_get(
                target,
                scenario,
                args.warmups,
                args.warmup_seconds,
                args.concurrency,
                args.timeout,
            )
        for round_index in range(args.rounds):
            ordered = targets if (scenario_index + round_index) % 2 == 0 else list(reversed(targets))
            for target in ordered:
                value = measure_get(
                    target, scenario, args.requests, args.concurrency, args.timeout
                )
                samples[scenario.name][target.name].append(value)
                raw_results.append({
                    "phase": "steady-state",
                    "round": round_index + 1,
                    "scenario": scenario.name,
                    "target": target.name,
                    "measurement": asdict(value),
                })

    if not args.skip_publish:
        publish_name = "hosted publish"
        samples[publish_name] = {target.name: [] for target in targets}
        for round_index in range(args.rounds):
            fixtures = []
            for index in range(args.publish_requests):
                module = f"example.com/kkrepo/go-perf-{run_id}-{round_index}-{index}"
                version = "v1.0.0"
                fixtures.append(
                    (module, version, module_archive(module, version, args.payload_bytes))
                )
            ordered = targets if round_index % 2 == 0 else list(reversed(targets))
            for target in ordered:
                value = measure_publish(
                    target, fixtures, args.publish_concurrency, args.timeout
                )
                verify_published(target, fixtures, args.timeout)
                samples[publish_name][target.name].append(value)
                raw_results.append({
                    "phase": "publication",
                    "round": round_index + 1,
                    "scenario": publish_name,
                    "target": target.name,
                    "measurement": asdict(value),
                })

    summaries: dict[str, Any] = {}
    for name, target_samples in samples.items():
        nexus = median_measurement(target_samples["Nexus"])
        candidate = median_measurement(target_samples["kkRepo"])
        summaries[name] = comparison_summary(nexus, candidate)

    prewarm_summaries: dict[str, Any] = {}
    for name, target_samples in prewarm_samples.items():
        prewarm_summaries[name] = comparison_summary(
            target_samples["Nexus"], target_samples["kkRepo"]
        )

    report: dict[str, Any] = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": {
            "platform": platform.platform(),
            "processor": platform.processor(),
            "python": platform.python_version(),
        },
        "configuration": {
            "module": args.module,
            "versions": versions,
            "payload_bytes": args.payload_bytes,
            "requests": args.requests,
            "concurrency": args.concurrency,
            "prewarm_requests": args.prewarm_requests,
            "warmups": args.warmups,
            "warmup_seconds": args.warmup_seconds,
            "rounds": args.rounds,
            "publish_requests": args.publish_requests,
            "publish_concurrency": args.publish_concurrency,
            "skip_publish": args.skip_publish,
            "run_id": run_id,
            "nexus_hosted_url": args.nexus_hosted_url,
            "nexus_group_url": args.nexus_group_url,
            "kkrepo_hosted_url": args.kkrepo_hosted_url,
            "kkrepo_group_url": args.kkrepo_group_url,
        },
        "correctness": correctness,
        "summaries": summaries,
        "prewarm_summaries": prewarm_summaries,
        "warmup_results": {
            scenario: {
                target: asdict(result) for target, result in target_results.items()
            }
            for scenario, target_results in warmup_results.items()
        },
        "raw_results": raw_results,
    }
    rendered = markdown(report)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if args.output.suffix.lower() == ".json":
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        else:
            args.output.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
