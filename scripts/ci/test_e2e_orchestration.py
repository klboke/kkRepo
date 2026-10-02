"""Regression checks for coverage preservation, disk guards and image reuse."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent
EXPECTED = "raw maven npm pypi go helm cargo pub composer nuget rubygems yum apt alpine r conda conan terraform swift ansible docker-oci".split()


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OrchestrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, PATH=f"{self.root}:{os.environ['PATH']}",
                        TEST_ROOT=str(self.root), ARTIFACT_DIR=str(self.root),
                        SCRIPT_DIR=str(SCRIPTS), STAMP="20261002000000")

    def stub(self, name, body):
        path = self.root / name
        path.write_text("#!/usr/bin/env bash\nset -eu\n" + body)
        path.chmod(0o755)

    def run_script(self, name, *args):
        return subprocess.run(["bash", str(SCRIPTS / name), *args], env=self.env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def dispatch(self, shard, override="all"):
        # Execute the production dispatcher with protocol/network operations
        # replaced by markers. This catches lost cleanup calls and selectors.
        source = (SCRIPTS / "run-client-e2e.sh").read_text()
        dispatcher = source.split("run_selected_tests() {", 1)[1].split("\nneed curl", 1)[0]
        prelude = "set -euo pipefail\n"
        for name in EXPECTED:
            function = "test_" + name.replace("-", "_")
            prelude += f'{function}() {{ echo "test:{name}"; SWIFT_CLEANUP_FIXTURE_AVAILABLE=true; }}\n'
        prelude += '''log() { :; }
register_cleanup_fixture() { echo "register:$4"; }
run_registered_cleanup() { echo cleanup; }
'''
        self.stub("dart", ":\n")
        self.stub("conan", ":\n")
        env = dict(self.env, CLIENT_E2E_SHARD=shard, CLIENT_E2E_TESTS=override,
                   CONDA_HOSTED_REPOSITORY="conda-hosted", APT_HOSTED_REPOSITORY="apt-hosted",
                   ALPINE_HOSTED_REPOSITORY="alpine-hosted", R_HOSTED_REPOSITORY="r-hosted",
                   CONAN_HOSTED_REPOSITORY="conan-hosted", CONAN_BIN="conan", ANSIBLE_GALAXY_BINS="fixture")
        return subprocess.run(["bash"], input=prelude + "run_selected_tests() {" + dispatcher + "\nrun_selected_tests\n",
                              env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_original_suite_order_and_cleanup_are_preserved(self):
        result = self.dispatch("all")
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual([x[5:] for x in lines if x.startswith("test:")], EXPECTED)
        self.assertEqual([x[9:] for x in lines if x.startswith("register:")], EXPECTED)
        self.assertEqual(lines.count("cleanup"), len(EXPECTED))

    def test_shards_execute_every_original_protocol_and_cleanup_once(self):
        collected = []
        for shard in ("core", "system", "swift"):
            result = self.dispatch(shard)
            self.assertEqual(result.returncode, 0, result.stderr)
            lines = result.stdout.splitlines()
            protocols = [x[5:] for x in lines if x.startswith("test:")]
            self.assertEqual([x[9:] for x in lines if x.startswith("register:")], protocols)
            self.assertEqual(lines.count("cleanup"), len(protocols))
            collected.extend(protocols)
        self.assertCountEqual(collected, EXPECTED)

    def test_unknown_shard_fails_instead_of_silently_running_nothing(self):
        result = self.dispatch("typo")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("test:", result.stdout)

    def test_explicit_protocol_selection_still_works(self):
        result = self.dispatch("core", "swift")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("test:swift", result.stdout)
        self.assertNotIn("test:maven", result.stdout)

    def disk_stubs(self, before, after):
        self.env.update(DISK_BEFORE=str(before * 1024 * 1024), DISK_AFTER=str(after * 1024 * 1024))
        self.stub("df", '''available="$DISK_BEFORE"
[[ ! -f "$TEST_ROOT/cleaned" ]] || available="$DISK_AFTER"
printf 'Filesystem 1024-blocks Used Available Capacity Mounted\\n'
printf '/dev/test 150000000 1 %s 1%% /\\n' "$available"
''')
        self.stub("sudo", 'printf "%s\\n" "$*" >> "$TEST_ROOT/cleanup-commands"\ntouch "$TEST_ROOT/cleaned"\n')
        self.stub("docker", 'echo "must not prune docker" >&2\nexit 99\n')

    def test_sufficient_disk_never_invokes_cleanup(self):
        self.disk_stubs(80, 98)
        result = self.run_script("prepare-e2e-disk.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / "cleanup-commands").exists())

    def test_low_disk_cleans_only_preinstalled_tools(self):
        self.disk_stubs(20, 38)
        result = self.run_script("prepare-e2e-disk.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("/usr/local/lib/android", (self.root / "cleanup-commands").read_text())

    def test_insufficient_disk_after_cleanup_fails_early(self):
        self.disk_stubs(5, 23)
        self.assertNotEqual(self.run_script("prepare-e2e-disk.sh").returncode, 0)

    def test_invalid_disk_budget_cannot_trigger_deletion(self):
        self.disk_stubs(80, 98)
        self.env["E2E_MIN_FREE_DISK_GIB"] = "0"
        self.assertEqual(self.run_script("prepare-e2e-disk.sh").returncode, 2)
        self.assertFalse((self.root / "cleanup-commands").exists())

    def test_cached_image_must_match_expected_identity(self):
        identity = "sha256:" + "a" * 64
        (self.root / "image.id").write_text(identity)
        (self.root / "image.tar").touch()
        self.env["IMAGE_ID"] = identity
        self.stub("docker", '''case "$1" in
load) echo loaded ;;
image) echo "$IMAGE_ID" ;;
*) exit 99 ;;
esac
''')
        self.assertEqual(self.run_script("swift-e2e-image-cache.sh", "load", "5.7", str(self.root)).returncode, 0)
        self.env["IMAGE_ID"] = "sha256:" + "b" * 64
        self.assertNotEqual(self.run_script("swift-e2e-image-cache.sh", "load", "5.7", str(self.root)).returncode, 0)

    def test_sdk_image_identity_is_checked_before_execution(self):
        (self.root / "image.id").write_text("sha256:" + "a" * 64)
        (self.root / "image.tar").touch()
        self.stub("docker", '''case "$1" in
load) : ;;
image) printf 'sha256:%064d\\n' 0 ;;
run) touch "$TEST_ROOT/executed-unverified-image" ;;
*) exit 99 ;;
esac
''')
        result = self.run_script("client-e2e-image-cache.sh", "load", "core", str(self.root))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "executed-unverified-image").exists())

    def test_sdk_container_preserves_host_paths_and_test_environment(self):
        project = self.root / "project with spaces"
        script = project / "scripts/ci/run-client-e2e-container.sh"
        script.parent.mkdir(parents=True)
        script.write_text((SCRIPTS / script.name).read_text())
        runner = self.root / "runner with spaces"
        self.env.update(RUNNER_TEMP=str(runner), CLIENT_E2E_IMAGE="kkrepo/client-e2e:swift-v1",
                        SWIFT_E2E_REQUIRE_5_7_5_9_6="true", SWIFT_E2E_BINS="fixture",
                        LIVE_COMPAT_START_TIMEOUT_SECONDS="360",
                        GITHUB_TOKEN="must-not-be-forwarded")
        self.stub("docker", '''if [[ "$1" == image ]]; then
  printf 'sha256:%064d\\n' 0
else
  python3 - "$@" <<'PY'
import json, os, pathlib, sys
pathlib.Path(os.environ["TEST_ROOT"], "docker-args.json").write_text(json.dumps(sys.argv[1:]))
PY
fi
''')
        result = subprocess.run(["bash", str(script), "scripts/ci/run-live-compat.sh", "swift"],
                                env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / "docker-args.json").read_text())
        self.assertIn(f"type=bind,source={project},target={project}", args)
        self.assertIn(f"type=bind,source={runner},target={runner}", args)
        self.assertIn("type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock", args)
        self.assertIn("host", args)
        self.assertIn("LIVE_COMPAT_START_TIMEOUT_SECONDS", args)
        self.assertIn("SWIFT_E2E_BINS", args)
        self.assertIn("SWIFT_E2E_REQUIRE_5_7_5_9_6", args)
        self.assertIn(f"CLIENT_E2E_WORK_DIR={runner}/client-e2e-runtime/work", args)
        self.assertNotIn("GITHUB_TOKEN", args)
        self.assertNotIn("must-not-be-forwarded", args)
        self.assertNotIn("PATH", args)
        self.assertEqual(args[-2:], ["scripts/ci/run-live-compat.sh", "swift"])

    def test_coverage_gate_rejects_missing_protocols_and_failed_cleanup(self):
        verifier = load("verify-client-e2e-coverage")
        for runtime in ("jvm", "native"):
            for database in ("mysql", "postgresql"):
                for shard, tests in verifier.plan.SHARDS.items():
                    directory = self.root / f"full-{runtime}-client-live-compat-{database}-{shard}-logs" / "client-e2e"
                    directory.mkdir(parents=True)
                    (directory / "protocol-durations.tsv").write_text("".join(f"{test}\t1\n" for test in tests))
                    for test in tests:
                        (directory / f"cleanup-{test}.json").write_text(json.dumps({
                            "tryRun": {"run": {"state": "SUCCEEDED", "matchedSubjects": 1}},
                            "executeRun": {"run": {"state": "SUCCEEDED", "deletedSubjects": 1, "failedSubjects": 0}},
                        }))
        self.assertIn("Verified 84", verifier.verify(self.root))
        missing = self.root / "full-native-client-live-compat-postgresql-swift-logs" / "client-e2e" / "protocol-durations.tsv"
        missing.write_text("")
        with self.assertRaises(ValueError):
            verifier.verify(self.root)
        missing.write_text("swift\t1\n")
        cleanup = missing.with_name("cleanup-swift.json")
        record = json.loads(cleanup.read_text())
        record["executeRun"]["run"]["failedSubjects"] = 1
        cleanup.write_text(json.dumps(record))
        with self.assertRaises(ValueError):
            verifier.verify(self.root)


if __name__ == "__main__":
    unittest.main()
