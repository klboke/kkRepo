#!/usr/bin/env python3
"""Compare warmed APT repository read paths on Nexus and kkRepo.

The two base URLs must point at hosted repositories containing the same package
bytes. The benchmark alternates target order per scenario, uses a fresh pool of
persistent HTTP connections for every run, and records every request latency.
It is a local directional benchmark, not a production SLA.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import http.client
import json
import math
import pathlib
import platform
import ssl
import statistics
import threading
import time
import urllib.parse
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class Target:
    name: str
    base_url: str
    authorization: str | None


@dataclass(frozen=True)
class Scenario:
    name: str
    path: str
    method: str = "GET"
    range_header: str | None = None
    expected_status: int = 200


@dataclass(frozen=True)
class Measurement:
    requests: int
    concurrency: int
    response_bytes: int
    wall_seconds: float
    requests_per_second: float
    mebibytes_per_second: float
    minimum_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float
    mean_ms: float


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


def request_path(target: Target, relative: str) -> tuple[urllib.parse.SplitResult, str]:
    base = urllib.parse.urlsplit(target.base_url.rstrip("/") + "/")
    relative_parts = urllib.parse.urlsplit(relative.lstrip("/"))
    path = base.path.rstrip("/") + "/" + relative_parts.path
    if relative_parts.query:
        path += "?" + relative_parts.query
    return base, path


def one_request(
    client: http.client.HTTPConnection,
    target: Target,
    path: str,
    scenario: Scenario,
) -> tuple[float, int, str]:
    headers = {
        "Accept": "*/*",
        "User-Agent": "kkrepo-apt-performance-comparison/1",
    }
    if target.authorization:
        headers["Authorization"] = target.authorization
    if scenario.range_header:
        headers["Range"] = scenario.range_header
    started = time.perf_counter_ns()
    client.request(scenario.method, path, headers=headers)
    response = client.getresponse()
    body = response.read()
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != scenario.expected_status:
        raise RequestFailure(
            f"{target.name} {scenario.method} {path} returned HTTP {response.status}: "
            f"{body[:300]!r}"
        )
    if scenario.method != "HEAD" and response.status != 304 and not body:
        raise RequestFailure(f"{target.name} {scenario.name} returned an empty body")
    return elapsed_ms, len(body), hashlib.sha256(body).hexdigest()


def preflight(target: Target, scenario: Scenario, timeout: float) -> tuple[int, str]:
    parts, path = request_path(target, scenario.path)
    client = connection(parts, timeout)
    try:
        _, size, digest = one_request(client, target, path, scenario)
        return size, digest
    finally:
        client.close()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def measure(
    target: Target,
    scenario: Scenario,
    requests: int,
    concurrency: int,
    timeout: float,
) -> Measurement:
    safe_concurrency = max(1, min(concurrency, requests))
    assignments = [requests // safe_concurrency] * safe_concurrency
    for index in range(requests % safe_concurrency):
        assignments[index] += 1
    barrier = threading.Barrier(safe_concurrency)

    def worker(count: int) -> tuple[list[float], int]:
        parts, path = request_path(target, scenario.path)
        client = connection(parts, timeout)
        timings: list[float] = []
        total_bytes = 0
        try:
            barrier.wait(timeout=timeout)
            for _ in range(count):
                elapsed, size, _ = one_request(client, target, path, scenario)
                timings.append(elapsed)
                total_bytes += size
            return timings, total_bytes
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    total_bytes = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=safe_concurrency) as executor:
        futures = [executor.submit(worker, count) for count in assignments]
        for future in futures:
            worker_timings, worker_bytes = future.result()
            timings.extend(worker_timings)
            total_bytes += worker_bytes
    wall = time.perf_counter() - started
    return Measurement(
        requests=len(timings),
        concurrency=safe_concurrency,
        response_bytes=total_bytes,
        wall_seconds=round(wall, 6),
        requests_per_second=round(len(timings) / wall, 2),
        mebibytes_per_second=round(total_bytes / wall / 1024 / 1024, 2),
        minimum_ms=round(min(timings), 3),
        p50_ms=round(percentile(timings, 0.50), 3),
        p95_ms=round(percentile(timings, 0.95), 3),
        p99_ms=round(percentile(timings, 0.99), 3),
        maximum_ms=round(max(timings), 3),
        mean_ms=round(statistics.fmean(timings), 3),
    )


def median_measurement(measurements: list[Measurement]) -> Measurement:
    if not measurements:
        raise ValueError("at least one measurement is required")

    def median(field: str) -> float:
        return round(statistics.median(
            float(getattr(measurement, field)) for measurement in measurements), 3)

    first = measurements[0]
    return Measurement(
        requests=first.requests,
        concurrency=first.concurrency,
        response_bytes=int(statistics.median(
            measurement.response_bytes for measurement in measurements)),
        wall_seconds=median("wall_seconds"),
        requests_per_second=round(statistics.median(
            measurement.requests_per_second for measurement in measurements), 2),
        mebibytes_per_second=round(statistics.median(
            measurement.mebibytes_per_second for measurement in measurements), 2),
        minimum_ms=median("minimum_ms"),
        p50_ms=median("p50_ms"),
        p95_ms=median("p95_ms"),
        p99_ms=median("p99_ms"),
        maximum_ms=median("maximum_ms"),
        mean_ms=median("mean_ms"),
    )


def markdown(report: dict[str, object]) -> str:
    lines = [
        "# APT Nexus / kkRepo performance comparison",
        "",
        f"Generated: `{report['generated_at']}`",
        f"\nEach row is the median of `{report['configuration']['rounds']}` independent rounds.",
        "",
        "| Scenario | Target | req/s | MiB/s | p50 ms | p95 ms | p99 ms |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    results = report["results"]
    assert isinstance(results, list)
    for result in results:
        assert isinstance(result, dict)
        measurement = result["measurement"]
        assert isinstance(measurement, dict)
        lines.append(
            "| {scenario} | {target} | {requests_per_second:.2f} | "
            "{mebibytes_per_second:.2f} | {p50_ms:.3f} | {p95_ms:.3f} | {p99_ms:.3f} |".format(
                scenario=result["scenario"], target=result["target"], **measurement
            )
        )
    lines.extend([
        "",
        "Ratios above 1.0 mean kkRepo has higher throughput; latency ratios below 1.0 mean "
        "kkRepo has lower latency.",
        "",
        "| Scenario | throughput ratio | p50 latency ratio | p95 latency ratio |",
        "| --- | ---: | ---: | ---: |",
    ])
    comparisons = report["comparisons"]
    assert isinstance(comparisons, list)
    for item in comparisons:
        assert isinstance(item, dict)
        lines.append(
            f"| {item['scenario']} | {item['throughput_ratio']:.3f} | "
            f"{item['p50_latency_ratio']:.3f} | {item['p95_latency_ratio']:.3f} |"
        )
    lines.extend([
        "",
        "> Directional local result only. Both targets must use the same fixture bytes and be "
        "warmed independently. Networked object storage, production databases, TLS termination, "
        "and multi-node load balancing are outside this run.",
        "",
    ])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nexus-base-url", required=True)
    parser.add_argument("--kkrepo-base-url", required=True)
    parser.add_argument("--nexus-auth", help="username:password; omitted for anonymous reads")
    parser.add_argument("--kkrepo-auth", help="username:password; omitted for anonymous reads")
    parser.add_argument("--distribution", default="stable")
    parser.add_argument("--component", default="main")
    parser.add_argument("--architecture", default="amd64")
    parser.add_argument("--package-path", required=True)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--warmups", type=int, default=32)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.warmups < 0 or args.rounds < 1:
        raise SystemExit(
            "requests/concurrency/rounds must be positive and warmups must be non-negative"
        )
    targets = [
        Target("Nexus", args.nexus_base_url, authorization(args.nexus_auth)),
        Target("kkRepo", args.kkrepo_base_url, authorization(args.kkrepo_auth)),
    ]
    metadata = f"dists/{args.distribution}/{args.component}/binary-{args.architecture}"
    scenarios = [
        Scenario("InRelease", f"dists/{args.distribution}/InRelease"),
        Scenario("Packages.gz", f"{metadata}/Packages.gz"),
        Scenario("package GET", args.package_path),
        Scenario("package Range 64KiB", args.package_path, range_header="bytes=0-65535", expected_status=206),
        Scenario("package HEAD", args.package_path, method="HEAD"),
    ]

    preflights: dict[str, dict[str, tuple[int, str]]] = {}
    for scenario in scenarios:
        preflights[scenario.name] = {
            target.name: preflight(target, scenario, args.timeout) for target in targets
        }
    nexus_package = preflights["package GET"]["Nexus"]
    kkrepo_package = preflights["package GET"]["kkRepo"]
    if nexus_package != kkrepo_package:
        raise SystemExit(
            "package fixture differs between targets: "
            f"Nexus={nexus_package}, kkRepo={kkrepo_package}"
        )

    raw_results: list[dict[str, object]] = []
    samples: dict[str, dict[str, list[Measurement]]] = {
        scenario.name: {target.name: [] for target in targets} for scenario in scenarios
    }
    for scenario_index, scenario in enumerate(scenarios):
        for round_index in range(args.rounds):
            ordered_targets = (
                targets
                if (scenario_index + round_index) % 2 == 0
                else list(reversed(targets))
            )
            for target in ordered_targets:
                if args.warmups:
                    measure(
                        target,
                        scenario,
                        args.warmups,
                        min(args.concurrency, args.warmups),
                        args.timeout,
                    )
                measurement = measure(
                    target, scenario, args.requests, args.concurrency, args.timeout
                )
                samples[scenario.name][target.name].append(measurement)
                raw_results.append({
                    "round": round_index + 1,
                    "scenario": scenario.name,
                    "target": target.name,
                    "measurement": asdict(measurement),
                })

    results: list[dict[str, object]] = []
    by_scenario: dict[str, dict[str, Measurement]] = {}
    for scenario in scenarios:
        by_scenario[scenario.name] = {}
        for target in targets:
            aggregate = median_measurement(samples[scenario.name][target.name])
            by_scenario[scenario.name][target.name] = aggregate
            results.append({
                "scenario": scenario.name,
                "target": target.name,
                "measurement": asdict(aggregate),
            })

    comparisons: list[dict[str, object]] = []
    for scenario in scenarios:
        nexus = by_scenario[scenario.name]["Nexus"]
        candidate = by_scenario[scenario.name]["kkRepo"]
        comparisons.append({
            "scenario": scenario.name,
            "throughput_ratio": round(
                candidate.requests_per_second / nexus.requests_per_second, 3
            ),
            "p50_latency_ratio": round(candidate.p50_ms / nexus.p50_ms, 3),
            "p95_latency_ratio": round(candidate.p95_ms / nexus.p95_ms, 3),
        })

    report: dict[str, object] = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": {
            "platform": platform.platform(),
            "processor": platform.processor(),
            "python": platform.python_version(),
        },
        "configuration": {
            "requests": args.requests,
            "concurrency": args.concurrency,
            "warmups": args.warmups,
            "rounds": args.rounds,
            "nexus_base_url": args.nexus_base_url,
            "kkrepo_base_url": args.kkrepo_base_url,
            "package_path": args.package_path,
        },
        "preflights": preflights,
        "results": results,
        "raw_results": raw_results,
        "comparisons": comparisons,
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
