#!/usr/bin/env python3
"""Drive pnpm's interactive classic-login fallback through a pseudo-terminal."""

from __future__ import annotations

import errno
import os
import pty
import select
import signal
import sys
import time


def usage() -> None:
    print(
        "usage: run-pnpm-login.py <version> <registry> <username> "
        "<password> <email> <timeout-seconds>",
        file=sys.stderr,
    )


def main() -> int:
    if len(sys.argv) != 7:
        usage()
        return 2

    version, registry, username, password, email, timeout_text = sys.argv[1:]
    try:
        timeout_seconds = int(timeout_text)
    except ValueError:
        usage()
        return 2
    if timeout_seconds <= 0:
        usage()
        return 2

    command = [
        "npx",
        "--yes",
        f"pnpm@{version}",
        "login",
        "--registry",
        registry,
    ]
    prompts = [
        (b"Username:", f"{username}\n".encode()),
        (b"Password:", f"{password}\n".encode()),
        (b"Email (this IS public):", f"{email}\n".encode()),
    ]

    child_pid, master_fd = pty.fork()
    if child_pid == 0:
        os.execvp(command[0], command)

    deadline = time.monotonic() + timeout_seconds
    prompt_index = 0
    transcript = bytearray()
    wait_status: int | None = None

    try:
        while wait_status is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                print(
                    f"pnpm login timed out after {timeout_seconds} seconds",
                    file=sys.stderr,
                )
                os.killpg(child_pid, signal.SIGTERM)
                _, wait_status = os.waitpid(child_pid, 0)
                return 124

            readable, _, _ = select.select([master_fd], [], [], min(0.2, remaining))
            if readable:
                try:
                    chunk = os.read(master_fd, 4096)
                except OSError as exc:
                    if exc.errno != errno.EIO:
                        raise
                    chunk = b""
                if chunk:
                    os.write(sys.stdout.fileno(), chunk)
                    transcript.extend(chunk)
                    if len(transcript) > 65536:
                        del transcript[:-32768]
                    if prompt_index < len(prompts):
                        marker, response = prompts[prompt_index]
                        if marker.lower() in transcript.lower():
                            os.write(master_fd, response)
                            prompt_index += 1
                            transcript.clear()

            waited_pid, status = os.waitpid(child_pid, os.WNOHANG)
            if waited_pid == child_pid:
                wait_status = status
    finally:
        os.close(master_fd)

    exit_code = os.waitstatus_to_exitcode(wait_status)
    if exit_code == 0 and prompt_index != len(prompts):
        print(
            f"pnpm login completed without observing all classic-login prompts "
            f"({prompt_index}/{len(prompts)})",
            file=sys.stderr,
        )
        return 1
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
