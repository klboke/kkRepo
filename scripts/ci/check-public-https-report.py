"""Require all three live public HTTPS client checks to execute successfully."""
import sys
import xml.etree.ElementTree as ET

report = ET.parse(sys.argv[1]).getroot()
counts = {key: int(report.attrib.get(key, "0")) for key in ("tests", "skipped", "failures", "errors")}
if counts != {"tests": 3, "skipped": 0, "failures": 0, "errors": 0}:
    raise SystemExit(f"Public HTTPS acceptance requires three passing tests with zero skips: {counts}")
print("Public HTTPS acceptance: three executed tests, zero skips, zero failures")
