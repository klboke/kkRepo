#!/usr/bin/env python3
"""Require every protocol and its cleanup assertions on both runtimes/databases."""

import argparse
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("client_plan", Path(__file__).with_name("client-e2e-plan.py"))
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


def verify(root):
    rows = []
    for runtime in ("jvm", "native"):
        for database in ("mysql", "postgresql"):
            seen = set()
            for shard in plan.SHARDS:
                directory = root / f"full-{runtime}-client-live-compat-{database}-{shard}-logs" / "client-e2e"
                entries = [line.split("\t") for line in (directory / "protocol-durations.tsv").read_text().splitlines()]
                actual = [test for test, _ in entries]
                if actual != plan.selected_tests(shard):
                    raise ValueError(f"Unexpected protocol coverage in {directory}: {actual}")
                for test, duration in entries:
                    if test in seen:
                        raise ValueError(f"Duplicate coverage: {runtime}/{database}/{test}")
                    seen.add(test)
                    cleanup = json.loads((directory / f"cleanup-{test}.json").read_text())
                    if test == "gitlfs":
                        if cleanup.get("automaticCleanupRejected") is not True:
                            raise ValueError(f"LFS cleanup must be disabled: {runtime}/{database}")
                        rows.append(f"| {runtime} | {database} | {test} | {int(duration)} |")
                        continue
                    dry_run = cleanup["tryRun"]["run"]
                    execute = cleanup["executeRun"]["run"]
                    if (dry_run["state"] != "SUCCEEDED" or dry_run["matchedSubjects"] < 1
                            or execute["state"] != "SUCCEEDED" or execute["deletedSubjects"] < 1
                            or execute["failedSubjects"] != 0):
                        raise ValueError(f"Incomplete cleanup coverage: {runtime}/{database}/{test}")
                    rows.append(f"| {runtime} | {database} | {test} | {int(duration)} |")
            if seen != set(plan.ALL_TESTS):
                raise ValueError(f"Incomplete client coverage: {runtime}/{database}")
    return "\n".join([
        "### Real-client coverage", "",
        f"Verified {len(rows)} protocol/runtime/database combinations including Cleanup Try Run/Execute, or explicit rejection for Git LFS.", "",
        "| Runtime | Database | Protocol | Seconds (including cleanup) |", "| --- | --- | --- | ---: |", *rows, "",
    ])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(verify(args.directory))
