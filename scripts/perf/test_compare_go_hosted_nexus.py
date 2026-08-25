from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).with_name("compare-go-hosted-nexus.py")
SPEC = importlib.util.spec_from_file_location("compare_go_hosted_nexus", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
BENCHMARK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BENCHMARK
SPEC.loader.exec_module(BENCHMARK)


class CompareGoHostedNexusTest(unittest.TestCase):
    def test_adaptive_warmup_reaches_request_and_duration_thresholds(self) -> None:
        clock = [0.0]
        calls: list[int] = []

        def measure(*args: object) -> None:
            calls.append(args[2])
            clock[0] += 0.4

        with (
            mock.patch.object(BENCHMARK, "measure_get", side_effect=measure),
            mock.patch.object(BENCHMARK.time, "perf_counter", side_effect=lambda: clock[0]),
        ):
            result = BENCHMARK.warmup_get(
                BENCHMARK.Target("target", "http://hosted", "http://group", None),
                BENCHMARK.Scenario("list", "hosted", "module/@v/list", "list"),
                minimum_requests=5,
                minimum_seconds=1.0,
                concurrency=2,
                timeout=10.0,
            )

        self.assertEqual([5, 5, 5], calls)
        self.assertEqual(15, result.requests)
        self.assertEqual(1.2, result.wall_seconds)

    def test_adaptive_warmup_can_be_disabled(self) -> None:
        with mock.patch.object(BENCHMARK, "measure_get") as measure:
            result = BENCHMARK.warmup_get(
                BENCHMARK.Target("target", "http://hosted", "http://group", None),
                BENCHMARK.Scenario("list", "hosted", "module/@v/list", "list"),
                minimum_requests=0,
                minimum_seconds=0,
                concurrency=16,
                timeout=10.0,
            )

        measure.assert_not_called()
        self.assertEqual(0, result.requests)

    def test_defaults_capture_pre_warm_and_sustained_warmup(self) -> None:
        required = [
            "benchmark",
            "--nexus-hosted-url",
            "http://nexus/hosted",
            "--nexus-group-url",
            "http://nexus/group",
            "--kkrepo-hosted-url",
            "http://kkrepo/hosted",
            "--kkrepo-group-url",
            "http://kkrepo/group",
        ]
        with mock.patch.object(sys, "argv", required):
            args = BENCHMARK.parse_args()

        self.assertEqual(250, args.prewarm_requests)
        self.assertEqual(2000, args.warmups)
        self.assertEqual(5.0, args.warmup_seconds)

    def test_markdown_separates_steady_state_from_pre_warm(self) -> None:
        value = BENCHMARK.Measurement(250, 16, 1000, 1.0, 250.0, 0.0, 1, 2, 3, 4, 5, 2.5)
        summary = BENCHMARK.comparison_summary(value, value)
        rendered = BENCHMARK.markdown({
            "generated_at": "2026-08-25T00:00:00Z",
            "configuration": {"rounds": 3},
            "summaries": {"hosted list": summary},
            "prewarm_summaries": {"hosted list": summary},
        })

        self.assertIn("## Steady-state results", rendered)
        self.assertIn("## Pre-warm samples", rendered)
        self.assertIn("not process-cold", rendered)


if __name__ == "__main__":
    unittest.main()
