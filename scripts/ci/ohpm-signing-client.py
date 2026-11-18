#!/usr/bin/env python3
import errno
import os
import pty
import select
import signal
import subprocess
import sys
import time


def main():
    passphrase = sys.stdin.buffer.readline().rstrip(b"\r\n")
    if not passphrase or not sys.argv[1:]:
        raise ValueError("Provide a passphrase on stdin and an OHPM command")
    master, slave = pty.openpty()
    process = subprocess.Popen(
        sys.argv[1:], stdin=slave, stdout=slave, stderr=slave, start_new_session=True
    )
    os.close(slave)
    output = bytearray()
    prompt = b"what is your passphrase of the private key:"
    answered = False
    deadline = time.monotonic() + 50
    try:
        while time.monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.1)
            if readable:
                try:
                    chunk = os.read(master, 65536)
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    break
                if not chunk:
                    break
                output.extend(chunk)
                if not answered and prompt in output:
                    os.write(master, passphrase)
                    time.sleep(0.1)
                    os.write(master, b"\r")
                    answered = True
            elif process.poll() is not None:
                break
        if process.poll() is None:
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                raise TimeoutError("OHPM signing command timed out") from None
        sys.stdout.buffer.write(bytes(output).replace(passphrase, b"<redacted>"))
        return process.returncode
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        os.close(master)


if __name__ == "__main__":
    sys.exit(main())
