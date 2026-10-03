#!/usr/bin/env python3
"""Partition the complete real-client suite without changing protocol coverage."""

import argparse

# Preserve the ordering of the unsharded/local suite.
ALL_TESTS = "raw maven npm pypi go helm cargo pub composer nuget rubygems yum apt alpine r conda conan gitlfs terraform swift ansible docker-oci".split()
SHARDS = {
    "core": "raw maven npm pypi go helm cargo pub composer nuget rubygems docker-oci".split(),
    "system": "yum apt alpine r conda conan gitlfs terraform ansible".split(),
    "swift": ["swift"],
}


def selected_tests(shard):
    partition = [test for tests in SHARDS.values() for test in tests]
    if len(partition) != len(set(partition)) or set(partition) != set(ALL_TESTS):
        raise ValueError("client shards must cover every protocol exactly once")
    return ALL_TESTS if shard == "all" else SHARDS[shard]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shard", choices=["all", *SHARDS], default="all", nargs="?")
    args = parser.parse_args()
    print(",".join(selected_tests(args.shard)))
