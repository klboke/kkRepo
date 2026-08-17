#!/usr/bin/env python3
"""Correctness-first same-host Hugging Face Models comparison against Nexus 3.94."""

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
import shlex
import ssl
import statistics
import subprocess
import threading
import time
import urllib.parse
from dataclasses import asdict, dataclass
from typing import Any


@dataclass
class Target:
    name: str
    base_url: str
    authorization: str | None
    metadata_etag: str | None = None


@dataclass(frozen=True)
class Scenario:
    name: str
    route: str
    validation: str
    method: str = "GET"
    expected_status: int = 200
    range_header: str | None = None
    conditional: bool = False
    gate: str = "metadata"


@dataclass(frozen=True)
class Measurement:
    requests: int
    errors: int
    success_rate: float
    concurrency: int
    response_bytes: int
    wall_seconds: float
    requests_per_second: float
    mebibytes_per_second: float
    ttfb_p50_ms: float
    ttfb_p95_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float


class RequestFailure(RuntimeError):
    pass


def authorization(value: str | None) -> str | None:
    if not value:
        return None
    return "Basic " + base64.b64encode(value.encode()).decode("ascii")


def connection(parts: urllib.parse.SplitResult, timeout: float) -> http.client.HTTPConnection:
    if parts.scheme == "https":
        return http.client.HTTPSConnection(
            parts.hostname, parts.port or 443, timeout=timeout, context=ssl.create_default_context()
        )
    if parts.scheme == "http":
        return http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    raise ValueError(f"unsupported URL scheme: {parts.scheme}")


def request_route(target: Target, relative: str) -> tuple[urllib.parse.SplitResult, str]:
    base = urllib.parse.urlsplit(target.base_url.rstrip("/") + "/")
    relative_parts = urllib.parse.urlsplit(relative.lstrip("/"))
    route = base.path.rstrip("/") + "/" + relative_parts.path
    if relative_parts.query:
        route += "?" + relative_parts.query
    return base, route


def one_request(
    client: http.client.HTTPConnection,
    target: Target,
    route: str,
    scenario: Scenario,
) -> tuple[float, float, bytes, dict[str, str]]:
    headers = {"Accept": "*/*", "User-Agent": "kkrepo-huggingface-performance/1"}
    if target.authorization:
        headers["Authorization"] = target.authorization
    if scenario.range_header:
        headers["Range"] = scenario.range_header
    if scenario.conditional:
        if not target.metadata_etag:
            raise RequestFailure(f"{target.name} did not expose an ETag")
        headers["If-None-Match"] = target.metadata_etag
    started = time.perf_counter_ns()
    client.request(scenario.method, route, headers=headers)
    response = client.getresponse()
    ttfb = (time.perf_counter_ns() - started) / 1_000_000
    body = response.read()
    elapsed = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != scenario.expected_status:
        raise RequestFailure(
            f"{target.name} {scenario.method} {route} returned HTTP {response.status}: "
            f"{body[:300]!r}"
        )
    response_headers = {name.lower(): value for name, value in response.getheaders()}
    return elapsed, ttfb, body, response_headers


def assert_local_response(target: Target, scenario: Scenario, headers: dict[str, str]) -> None:
    forbidden = ["location", "x-xet-hash"]
    present = [name for name in forbidden if name in headers]
    link = headers.get("link", "").lower()
    if present or "xet-" in link or "huggingface.co" in link:
        raise RequestFailure(
            f"{target.name} {scenario.name} exposed upstream/Xet routing: {present}, {link!r}"
        )


def canonical(
    target: Target,
    body: bytes,
    headers: dict[str, str],
    scenario: Scenario,
    commit: str,
    file_sha256: str,
    file_size: int,
) -> Any:
    assert_local_response(target, scenario, headers)
    if scenario.validation == "model":
        if not body:
            raise RequestFailure("model info returned an empty body")
        value = json.loads(body)
        if value.get("sha", "").lower() != commit.lower():
            raise RequestFailure("model info commit did not match the fixture")
        siblings = sorted(
            item.get("rfilename") or item.get("path")
            for item in value.get("siblings", [])
            if item.get("rfilename") or item.get("path")
        )
        if siblings != ["config.json", "model.safetensors"]:
            raise RequestFailure(f"unexpected model file set: {siblings}")
        if target.name == "kkrepo" and (
            b"xetHash" in body or b"xet_hash" in body or b"xet-read-token" in body
        ):
            raise RequestFailure("model metadata exposed Xet routing hints")
        return {"sha": commit.lower(), "siblings": siblings}
    if scenario.validation == "bytes":
        actual = hashlib.sha256(body).hexdigest()
        if len(body) != file_size or actual != file_sha256:
            raise RequestFailure(
                f"full file mismatch: size={len(body)}, sha256={actual}"
            )
        return {"size": len(body), "sha256": actual}
    if scenario.validation == "range":
        actual = hashlib.sha256(body).hexdigest()
        if len(body) != 65536:
            raise RequestFailure(f"Range returned {len(body)} bytes instead of 65536")
        return {"size": len(body), "sha256": actual}
    if scenario.validation == "head":
        if body:
            raise RequestFailure("HEAD returned a body")
        if headers.get("x-repo-commit", "").lower() != commit.lower():
            raise RequestFailure("HEAD did not expose the pinned commit")
        linked = headers.get("x-linked-etag", "").strip('"').lower()
        if linked != file_sha256 or headers.get("x-linked-size") != str(file_size):
            raise RequestFailure("HEAD linked identity did not match the fixture")
        return {"commit": commit.lower(), "sha256": linked, "size": file_size}
    if scenario.validation == "empty":
        if body:
            raise RequestFailure("empty response unexpectedly returned a body")
        return scenario.expected_status
    raise RequestFailure(f"unknown validation mode: {scenario.validation}")


def preflight(
    target: Target,
    scenario: Scenario,
    timeout: float,
    commit: str,
    file_sha256: str,
    file_size: int,
) -> Any:
    parts, route = request_route(target, scenario.route)
    client = connection(parts, timeout)
    try:
        _, _, body, headers = one_request(client, target, route, scenario)
        if scenario.validation == "model":
            target.metadata_etag = headers.get("etag")
            if not target.metadata_etag:
                raise RequestFailure(f"{target.name} model info did not expose an ETag")
        return canonical(target, body, headers, scenario, commit, file_sha256, file_size)
    finally:
        client.close()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def measure(
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

    def worker(count: int) -> tuple[list[float], list[float], int]:
        parts, route = request_route(target, scenario.route)
        client = connection(parts, timeout)
        timings: list[float] = []
        ttfbs: list[float] = []
        transferred = 0
        try:
            barrier.wait(timeout=timeout)
            for _ in range(count):
                elapsed, ttfb, body, _ = one_request(client, target, route, scenario)
                timings.append(elapsed)
                ttfbs.append(ttfb)
                transferred += len(body)
            return timings, ttfbs, transferred
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    ttfbs: list[float] = []
    transferred = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(worker, count) for count in assignments]
        for future in futures:
            worker_timings, worker_ttfbs, worker_bytes = future.result()
            timings.extend(worker_timings)
            ttfbs.extend(worker_ttfbs)
            transferred += worker_bytes
    wall = time.perf_counter() - started
    return Measurement(
        requests=len(timings),
        errors=0,
        success_rate=1.0,
        concurrency=workers,
        response_bytes=transferred,
        wall_seconds=round(wall, 6),
        requests_per_second=round(len(timings) / wall, 2),
        mebibytes_per_second=round(transferred / wall / 1024 / 1024, 2),
        ttfb_p50_ms=round(percentile(ttfbs, 0.50), 3),
        ttfb_p95_ms=round(percentile(ttfbs, 0.95), 3),
        p50_ms=round(percentile(timings, 0.50), 3),
        p95_ms=round(percentile(timings, 0.95), 3),
        p99_ms=round(percentile(timings, 0.99), 3),
        maximum_ms=round(max(timings), 3),
    )


def median_measurement(values: list[Measurement]) -> Measurement:
    def median(name: str) -> float:
        return round(statistics.median(float(getattr(value, name)) for value in values), 3)

    return Measurement(
        requests=values[0].requests,
        errors=0,
        success_rate=1.0,
        concurrency=values[0].concurrency,
        response_bytes=int(statistics.median(value.response_bytes for value in values)),
        wall_seconds=median("wall_seconds"),
        requests_per_second=median("requests_per_second"),
        mebibytes_per_second=median("mebibytes_per_second"),
        ttfb_p50_ms=median("ttfb_p50_ms"),
        ttfb_p95_ms=median("ttfb_p95_ms"),
        p50_ms=median("p50_ms"),
        p95_ms=median("p95_ms"),
        p99_ms=median("p99_ms"),
        maximum_ms=median("maximum_ms"),
    )


def cold_fill(
    target: Target,
    scenario: Scenario,
    timeout: float,
    commit: str,
    file_sha256: str,
    file_size: int,
) -> dict[str, Any]:
    parts, route = request_route(target, scenario.route)
    client = connection(parts, timeout)
    try:
        elapsed, ttfb, body, headers = one_request(client, target, route, scenario)
        canonical(target, body, headers, scenario, commit, file_sha256, file_size)
        return {
            "milliseconds": round(elapsed, 3),
            "ttfb_milliseconds": round(ttfb, 3),
            "mebibytes_per_second": round(file_size / (elapsed / 1000) / 1024 / 1024, 3),
            "bytes": file_size,
            "sha256": file_sha256,
        }
    finally:
        client.close()


def run_client(command: str, timeout: float) -> float:
    arguments = shlex.split(command)
    if not arguments:
        raise RequestFailure("client command is empty")
    started = time.perf_counter()
    subprocess.run(arguments, check=True, timeout=timeout)
    return round((time.perf_counter() - started) * 1000, 3)


def ratio(left: float, right: float) -> float:
    return round(left / right, 3) if right else float("inf")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nexus-base-url", required=True)
    parser.add_argument("--kkrepo-base-url", required=True)
    parser.add_argument("--nexus-auth")
    parser.add_argument("--kkrepo-auth")
    parser.add_argument("--repo-id", default="kkrepo/hf-benchmark")
    parser.add_argument("--commit", default="0123456789abcdef0123456789abcdef01234567")
    parser.add_argument("--filename", default="model.safetensors")
    parser.add_argument("--file-sha256", required=True)
    parser.add_argument("--file-size", required=True, type=int)
    parser.add_argument("--requests", type=int, default=250)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--warmups", type=int, default=32)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--nexus-client-command")
    parser.add_argument("--kkrepo-client-command")
    parser.add_argument("--enforce-gates", action="store_true")
    parser.add_argument("--skip-cold", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    if not args.file_sha256.lower().isalnum() or len(args.file_sha256) != 64:
        raise SystemExit("--file-sha256 must be a 64-character digest")
    nexus = Target("nexus", args.nexus_base_url, authorization(args.nexus_auth))
    kkrepo = Target("kkrepo", args.kkrepo_base_url, authorization(args.kkrepo_auth))
    model_route = f"api/models/{args.repo_id}/revision/{args.commit}"
    file_route = f"{args.repo_id}/resolve/{args.commit}/{args.filename}"
    scenarios = [
        Scenario("model info GET", model_route, "model", gate="metadata"),
        Scenario("model info 304", model_route, "empty", expected_status=304,
                 conditional=True, gate="metadata"),
        Scenario("model file HEAD", file_route, "head", method="HEAD", gate="file"),
        Scenario("model file GET", file_route, "bytes", gate="file"),
        Scenario("model file Range 64 KiB", file_route, "range", expected_status=206,
                 range_header="bytes=0-65535", gate="file"),
    ]

    result: dict[str, Any] = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "method": {
            "requests": args.requests,
            "concurrency": args.concurrency,
            "warmups": args.warmups,
            "rounds": args.rounds,
            "alternating_order": True,
        },
        "fixture": {
            "repo_id": args.repo_id,
            "commit": args.commit,
            "filename": args.filename,
            "size": args.file_size,
            "sha256": args.file_sha256.lower(),
        },
    }

    full_scenario = scenarios[3]
    if not args.skip_cold:
        result["cold_fill"] = {}
        for target in (nexus, kkrepo):
            result["cold_fill"][target.name] = cold_fill(
                target, full_scenario, args.timeout, args.commit,
                args.file_sha256.lower(), args.file_size
            )

    correctness: dict[str, Any] = {}
    for scenario in scenarios:
        values = {}
        for target in (nexus, kkrepo):
            values[target.name] = preflight(
                target, scenario, args.timeout, args.commit,
                args.file_sha256.lower(), args.file_size
            )
        if values["nexus"] != values["kkrepo"]:
            raise RequestFailure(f"correctness mismatch for {scenario.name}: {values}")
        correctness[scenario.name] = values["kkrepo"]
    result["correctness"] = correctness

    for scenario in scenarios:
        for target in (nexus, kkrepo):
            measure(target, scenario, args.warmups, min(args.concurrency, args.warmups), args.timeout)

    rounds: dict[str, dict[str, list[Measurement]]] = {
        scenario.name: {"nexus": [], "kkrepo": []} for scenario in scenarios
    }
    for round_index in range(args.rounds):
        order = (nexus, kkrepo) if round_index % 2 == 0 else (kkrepo, nexus)
        for scenario in scenarios:
            for target in order:
                rounds[scenario.name][target.name].append(measure(
                    target, scenario, args.requests, args.concurrency, args.timeout
                ))

    summaries: dict[str, Any] = {}
    gate_failures: list[str] = []
    for scenario in scenarios:
        nexus_summary = median_measurement(rounds[scenario.name]["nexus"])
        kkrepo_summary = median_measurement(rounds[scenario.name]["kkrepo"])
        throughput_ratio = ratio(
            kkrepo_summary.requests_per_second, nexus_summary.requests_per_second
        )
        p95_ratio = ratio(kkrepo_summary.p95_ms, nexus_summary.p95_ms)
        minimum_throughput = 0.80 if scenario.gate == "metadata" else 0.90
        maximum_p95 = 1.25 if scenario.gate == "metadata" else 1.15
        if throughput_ratio < minimum_throughput:
            gate_failures.append(
                f"{scenario.name}: throughput {throughput_ratio}x < {minimum_throughput}x"
            )
        if p95_ratio > maximum_p95:
            gate_failures.append(
                f"{scenario.name}: p95 {p95_ratio}x > {maximum_p95}x"
            )
        summaries[scenario.name] = {
            "nexus": asdict(nexus_summary),
            "kkrepo": asdict(kkrepo_summary),
            "throughput_ratio": throughput_ratio,
            "p95_ratio": p95_ratio,
            "rounds": {
                name: [asdict(value) for value in values]
                for name, values in rounds[scenario.name].items()
            },
        }
    if "cold_fill" in result:
        cold_ratio = ratio(
            result["cold_fill"]["kkrepo"]["mebibytes_per_second"],
            result["cold_fill"]["nexus"]["mebibytes_per_second"],
        )
        result["cold_fill"]["throughput_ratio"] = cold_ratio
        if cold_ratio < 0.90:
            gate_failures.append(f"cold fill: throughput {cold_ratio}x < 0.9x")
    result["scenarios"] = summaries

    if args.nexus_client_command and args.kkrepo_client_command:
        client_samples = {"nexus": [], "kkrepo": []}
        for round_index in range(args.rounds):
            commands = (
                (("nexus", args.nexus_client_command), ("kkrepo", args.kkrepo_client_command))
                if round_index % 2 == 0
                else (("kkrepo", args.kkrepo_client_command), ("nexus", args.nexus_client_command))
            )
            for name, command in commands:
                client_samples[name].append(run_client(command, args.timeout))
        result["client"] = client_samples

    result["gate_failures"] = gate_failures
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        pathlib.Path(args.output).write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    if args.enforce_gates and gate_failures:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
