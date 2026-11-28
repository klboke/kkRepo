import pathlib
import runpy
import unittest


forwarded_headers = runpy.run_path(str(pathlib.Path(__file__).with_name("verify-ohpm-s3.py")))["forwarded_headers"]


class GatewayHeadersTest(unittest.TestCase):
    def test_forwarded_fields_and_framing(self):
        headers = [("ETag", '"abc"'), ("x-amz-meta-description", "part one\tpart two"),
                   ("Content-Length", "1234"), ("Connection", "keep-alive")]
        self.assertEqual(forwarded_headers(headers, "GET", 12), headers[:2] + [("Content-Length", "12")])
        self.assertEqual(forwarded_headers(headers, "HEAD", 0), headers[:2] + [("Content-Length", "1234")])
        self.assertEqual(forwarded_headers([], "HEAD", 0), [("Content-Length", "0")])

    def test_invalid_upstream_fields_are_rejected(self):
        for field in [("Bad Name", "value"), ("ETag", "one\rtwo"), ("ETag", "one\ntwo"),
                      ("Content-Length", "-10"), ("Content-Length", "12, 13")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                forwarded_headers([field], "HEAD", 0)
