#!/usr/bin/env python3
import base64
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request


def main():
    base = os.environ["KKREPO_COMPAT_BASE_URL"].rstrip("/")
    login_secret = os.environ["KKREPO_COMPAT_PASSWORD"]
    admin_user = os.environ.get("KKREPO_COMPAT_USERNAME", "admin")
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    deadline = time.monotonic() + 120
    while True:
        try:
            with client.open(base + "/internal/security/bootstrap", timeout=5) as response:
                status = json.load(response)
            break
        except (urllib.error.URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise TimeoutError("OHPM candidate did not start") from None
            time.sleep(1)
    if not status.get("required"):
        raise RuntimeError("This setup command requires a fresh disposable candidate")
    try:
        session = client.open(base + "/internal/security/session", timeout=10)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
        session = error
    with session:
        csrf = session.headers["X-Nexus-Plus-CSRF-Token"]
    if not csrf:
        raise RuntimeError("Candidate did not issue a bootstrap CSRF token")
    body = json.dumps({"password": login_secret, "passwordConfirm": login_secret,
                       "anonymousAccessEnabled": False}).encode()
    with client.open(urllib.request.Request(base + "/internal/security/bootstrap/admin", data=body,
            headers={"Content-Type": "application/json", "X-Nexus-Plus-CSRF-Token": csrf}), timeout=20):
        pass
    authorization = "Basic " + base64.b64encode(f"{admin_user}:{login_secret}".encode()).decode()
    body = json.dumps({"name": "default", "type": "file",
                       "path": os.environ["OHPM_COMPAT_BLOB_PATH"]}).encode()
    with client.open(urllib.request.Request(base + "/internal/blob-stores", data=body,
            headers={"Content-Type": "application/json", "Authorization": authorization}), timeout=20):
        pass
    print("Disposable OHPM candidate initialized")


if __name__ == "__main__":
    main()
