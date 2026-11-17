"""Validate the E2E fixture with the strict checks used by Python 3.13+ clients."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "check_custom_ca", Path(__file__).with_name("check-custom-ca.py"))
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class CustomCaCertificateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory(prefix="kkrepo-ca-certificate-test-")
        cls.addClassCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        cls.ca, _, cls.cert = PROBE.certificate(directory, "server")
        cls.other_ca, _, _ = PROBE.certificate(directory, "other")

    def verify(self, ca, hostname="localhost"):
        return subprocess.run(
            ["openssl", "verify", "-x509_strict", "-purpose", "sslserver",
             "-verify_hostname", hostname, "-CAfile", str(ca), str(self.cert)],
            capture_output=True, text=True)

    def test_generated_chain_passes_strict_verification(self):
        result = self.verify(self.ca)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_wrong_hostname_is_rejected(self):
        result = self.verify(self.ca, "wrong.example")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("hostname mismatch", result.stdout + result.stderr)

    def test_unrelated_ca_is_rejected(self):
        result = self.verify(self.other_ca)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
