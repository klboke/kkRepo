#!/usr/bin/env python3
"""Verify the packaged JVM/Native runtime sends an IAM token over verified PostgreSQL TLS.

This is a local wire probe with synthetic AWS credentials, not an AWS authorization test.
The probe deliberately rejects the connection after inspecting the authentication message.
"""
import argparse
import os
from pathlib import Path
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import urllib.parse


def receive(connection, size):
    data = b""
    while len(data) < size:
        part = connection.recv(size - len(data))
        if not part:
            raise RuntimeError("Incomplete PostgreSQL message")
        data += part
    return data


def probe(listener, tls, verified, failures):
    while True:
        try:
            raw, _ = listener.accept()
        except OSError:
            return
        try:
            with raw:
                raw.settimeout(10)
                assert receive(raw, 8) == struct.pack("!II", 8, 80877103)
                raw.sendall(b"S")
                with tls.wrap_socket(raw, server_side=True) as connection:
                    size = struct.unpack("!I", receive(connection, 4))[0]
                    startup = receive(connection, size - 4)
                    assert b"user\x00iam_probe\x00" in startup
                    connection.sendall(b"R" + struct.pack("!II", 8, 3))
                    assert receive(connection, 1) == b"p"
                    size = struct.unpack("!I", receive(connection, 4))[0]
                    token = receive(connection, size - 4).rstrip(b"\x00").decode()
                    endpoint = urllib.parse.urlsplit("https://" + token)
                    query = urllib.parse.parse_qs(endpoint.query)
                    assert endpoint.hostname == "localhost"
                    assert endpoint.port == listener.getsockname()[1]
                    assert query["Action"] == ["connect"]
                    assert query["DBUser"] == ["iam_probe"]
                    assert query["X-Amz-Expires"] == ["900"]
                    assert query["X-Amz-Security-Token"] == ["iam-probe-session"]
                    assert query["X-Amz-Credential"][0].startswith("iam-probe-access/")
                    assert query["X-Amz-Credential"][0].endswith("/us-east-1/rds-db/aws4_request")
                    assert len(query["X-Amz-Signature"][0]) == 64
                    verified.set()
                    error = b"SFATAL\x00C28000\x00MLocal IAM wire probe complete\x00\x00"
                    connection.sendall(b"E" + struct.pack("!I", len(error) + 4) + error)
        except Exception as error:
            # Never print the captured authentication message.
            failures.append(type(error).__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--jar")
    target.add_argument("--image")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="kkrepo-iam-") as temporary:
        work = Path(temporary)
        work.chmod(0o755)
        certificate = work / "server.crt"
        key = work / "server.key"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                        "-keyout", str(key), "-out", str(certificate)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(certificate, key)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            verified = threading.Event()
            failures = []
            threading.Thread(target=probe, args=(listener, tls, verified, failures), daemon=True).start()
            ca_path = "/iam-probe/server.crt" if args.image else str(certificate)
            environment = {
                "KKREPO_DATABASE_TYPE": "postgresql", "KKREPO_DATABASE_AUTH": "iam",
                "KKREPO_DATABASE_IAM_REGION": "us-east-1",
                "SPRING_DATASOURCE_URL": f"jdbc:postgresql://localhost:{port}/kkrepo?sslmode=verify-full&sslrootcert={ca_path}",
                "SPRING_DATASOURCE_USERNAME": "iam_probe", "SPRING_DATASOURCE_PASSWORD": "",
                "KKREPO_HIKARI_CONNECTION_TIMEOUT_MS": "2000",
                "SPRING_DATASOURCE_HIKARI_INITIALIZATIONFAILTIMEOUT": "1",
                "AWS_ACCESS_KEY_ID": "iam-probe-access", "AWS_SECRET_ACCESS_KEY": "iam-probe-secret",
                "AWS_SESSION_TOKEN": "iam-probe-session", "AWS_EC2_METADATA_DISABLED": "true",
                "KKREPO_CREDENTIAL_SECRET": "0123456789abcdef0123456789abcdef",
                "KKREPO_API_KEY_PAYLOAD_SECRET": "abcdef0123456789abcdef0123456789",
            }
            container = "kkrepo-iam-probe-" + str(os.getpid())
            if args.image:
                command = ["docker", "run", "--rm", "--name", container, "--network", "host",
                           "-v", f"{work}:/iam-probe:ro"]
                for name, value in environment.items():
                    command += ["-e", f"{name}={value}"]
                command += [args.image]
            else:
                command = ["java", "-jar", args.jar]
            try:
                result = subprocess.run(command, env={**os.environ, **environment},
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
                if not verified.is_set() or failures or result.returncode == 0:
                    # Runtime logs may contain signed requests when debug logging is enabled.
                    for line in result.stdout.decode(errors="replace").splitlines():
                        if ("Caused by:" in line or "Reason:" in line) and "X-Amz-" not in line:
                            print(line, file=sys.stderr)
                    raise RuntimeError(f"IAM wire probe failed: verified={verified.is_set()}, "
                                       f"protocol_errors={failures}, exit={result.returncode}")
            finally:
                if args.image:
                    subprocess.run(["docker", "rm", "-f", container],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("Packaged runtime sent an IAM token over verified PostgreSQL TLS")


if __name__ == "__main__":
    main()
