#!/usr/bin/env python3
"""Docker retention black-box fixture; run against kkRepo or a licensed Nexus reference.

Creates and removes an isolated hosted repository and policy. KKREPO_PROBE_PASSWORD
supplies credentials. Nexus requires the retention feature and cleanup-run REST API.
An explicitly unsupported retention/license response exits 77, never passes.
"""
import argparse
import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MANIFEST = "application/vnd.oci.image.manifest.v1+json"
INDEX = "application/vnd.oci.image.index.v1+json"
CONFIG = "application/vnd.oci.image.config.v1+json"


def digest(body):
    return "sha256:" + hashlib.sha256(body).hexdigest()


def wait_for(description, predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.5)
    raise AssertionError("Timed out waiting for " + description)


def exercise(base, password, username="admin", backend="kkrepo"):
    name = "retention-" + uuid.uuid4().hex[:12]
    auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    base = base.rstrip("/")
    policy = None
    created_repository = False

    def request(method, path, body=None, content_type="application/json", expected=(200,)):
        url = urllib.parse.urljoin(base + "/", path)
        headers = {"Authorization": auth, "Content-Type": content_type,
                   "Accept": f"{MANIFEST}, {INDEX}, application/json"}
        req = urllib.request.Request(url, body, headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status in expected, f"{method} {path}: {response.status} {raw.decode(errors='replace')}"
            return response.status, response.headers, raw

    def api(method, path, payload=None, expected=(200, 201, 202, 204)):
        raw = request(method, path, None if payload is None else json.dumps(payload).encode(),
                      expected=expected)[2]
        return json.loads(raw) if raw else None

    root = f"/repository/{name}/v2/" if backend == "nexus" else f"/v2/{name}/"

    def manifest(image, reference, body, media=MANIFEST):
        request("PUT", root + image + "/manifests/" + reference, body, media, (201,))
        # Separate publication timestamps even on reference databases with coarse precision.
        time.sleep(1.05 if backend == "nexus" else 0.01)
        return body

    def publish(image, tag, marker):
        config = json.dumps({"architecture": "amd64", "os": "linux", "config": {},
                             "rootfs": {"type": "layers", "diff_ids": []}, "marker": marker}).encode()
        _, headers, _ = request("POST", root + image + "/blobs/uploads/", b"", expected=(202,))
        location = urllib.parse.urljoin(base, headers["Location"])
        separator = "&" if "?" in location else "?"
        request("PUT", location + separator + urllib.parse.urlencode({"digest": digest(config)}),
                config, "application/octet-stream", (201,))
        body = json.dumps({"schemaVersion": 2, "mediaType": MANIFEST,
                           "config": {"mediaType": CONFIG, "digest": digest(config), "size": len(config)},
                           "layers": []}).encode()
        return manifest(image, tag or digest(body), body)

    def check(image, reference, body):
        _, _, actual = request("GET", root + image + "/manifests/" + reference,
                               expected=(200,) if body is not None else (404,))
        if body is not None:
            assert actual == body, (image, reference)

    try:
        if backend == "nexus":
            status, _, _ = request("GET", "/service/rest/v1/system/license", expected=(200, 402, 404))
            if status == 402:
                print("UNSUPPORTED Nexus retention: the reference has no valid Pro license", flush=True)
                return 77
            try:
                policy = api("POST", "/service/rest/internal/cleanup-policies", {
                    "name": name, "format": "docker", "retain": 2,
                    "criteriaAssetRegex": ".*", "sortBy": "date"})
            except AssertionError as error:
                message = str(error).lower()
                if (": 400 " in message or ": 403 " in message) and any(
                        word in message for word in ("license", "retain", "professional", "pro edition")):
                    print("UNSUPPORTED Nexus retention: " + str(error), flush=True)
                    return 77
                raise
            api("POST", "/service/rest/v1/repositories/docker/hosted", {
                "name": name, "online": True,
                "storage": {"blobStoreName": "default", "strictContentTypeValidation": True,
                            "writePolicy": "ALLOW"},
                "docker": {"v1Enabled": False, "forceBasicAuth": True},
                "cleanup": {"policyNames": [name]}})
        else:
            repository = api("POST", "/internal/repositories", {
                "name": name, "recipe": "docker-hosted", "online": True,
                "blobStoreName": "default", "hosted": {"writePolicy": "ALLOW"}})
        created_repository = True
        oldest = publish("team/api", "z-old", "oldest")
        middle = publish("team/api", "m-middle", "middle")
        manifest("team/api", "middle-alias", middle)
        third = publish("team/api", "b-third", "third")
        newest = publish("team/api", "a-new", "newest")
        manifest("team/api", "new-alias", newest)
        web = publish("team/web", "only", "separate image")
        child = publish("team/multi", None, "multi-platform child")
        index = json.dumps({"schemaVersion": 2, "mediaType": INDEX, "manifests": [{
            "mediaType": MANIFEST, "digest": digest(child), "size": len(child),
            "platform": {"architecture": "amd64", "os": "linux"}}]}).encode()
        manifest("team/multi", "latest", index, INDEX)
        orphan = publish("team/orphan", None, "unfinished push")
        # Publication time, not tag spelling or original creation time, determines retention.
        manifest("team/api", "z-old", oldest)

        if backend == "nexus":
            preview = api("POST", "/service/rest/v1/cleanup/run", {"repository": name, "dryRun": True})
            assert preview["componentCount"] == 2, preview
            api("POST", "/service/rest/v1/cleanup/run", {"repository": name, "dryRun": False})
        else:
            command = {"name": name, "format": "docker", "criteria": {"retainCount": 2},
                       "repositoryIds": [repository["id"]], "scanLimitPerRepository": 10000,
                       "deleteLimitPerRepository": 1000,
                       "schedule": {"cronExpression": "0/2 * * * * ?", "timeZone": "UTC", "enabled": False}}
            policy = api("POST", "/internal/cleanup/policies", command)["policy"]
            path = f"/internal/cleanup/policies/{policy['id']}"
            run = api("POST", path + "/runs", {"mode": "TRY_RUN", "expectedPolicyRevision": policy["revision"],
                                              "scanLimitPerRepository": 10000})["run"]
            result = wait_for("Docker preview", lambda: (
                value if (value := api("GET", f"/internal/cleanup/runs/{run['id']}"))["run"]["state"]
                not in ("PENDING", "QUEUED", "RUNNING") else None))
            assert result["run"]["state"] == "SUCCEEDED", result
            assert result["run"]["wouldDeleteSubjects"] == 2, result
            check("team/api", "m-middle", middle)
            check("team/api", "b-third", third)
            command["revision"] = policy["revision"]
            command["schedule"]["enabled"] = True
            policy = api("PUT", path, command)["policy"]

        def deleted():
            status, _, _ = request("GET", root + "team/api/manifests/b-third", expected=(200, 404))
            return status == 404

        wait_for("Docker cleanup", deleted)
        for reference in ("m-middle", "middle-alias", digest(middle), "b-third", digest(third)):
            check("team/api", reference, None)
        for reference, body in (("z-old", oldest), ("a-new", newest), ("new-alias", newest),
                                (digest(oldest), oldest), (digest(newest), newest)):
            check("team/api", reference, body)
        check("team/web", "only", web)
        check("team/multi", "latest", index)
        check("team/multi", digest(child), child)
        check("team/orphan", digest(orphan), orphan)
        if backend == "kkrepo":
            runs = wait_for("successful scheduled Docker run", lambda: [r for r in
                api("GET", f"/internal/cleanup/runs?policyId={policy['id']}")["items"]
                if r["triggerKind"] == "SCHEDULED" and r["state"] == "SUCCEEDED" and r["deletedSubjects"] == 2])
            assert runs[0]["failedSubjects"] == 0, runs
            command["revision"] = policy["revision"]
            command["criteria"]["publishedOlderThanDays"] = 0
            command["schedule"]["enabled"] = False
            policy = api("PUT", path, command)["policy"]
            run = api("POST", path + "/runs", {"mode": "EXECUTE",
                "expectedPolicyRevision": policy["revision"]})["run"]
            result = wait_for("age-based Docker cleanup", lambda: (
                value if (value := api("GET", f"/internal/cleanup/runs/{run['id']}"))["run"]["state"]
                not in ("PENDING", "QUEUED", "RUNNING") else None))
            assert result["run"]["state"] == "SUCCEEDED", result
            assert result["run"]["deletedSubjects"] == 1, result
            check("team/orphan", digest(orphan), None)
            check("team/multi", digest(child), child)
            check("team/multi", "latest", index)
        print(f"{backend}: Docker retention preview/deletion preserved the newest two distinct digests, "
              "aliases, separate images, multi-platform children and unfinished uploads", flush=True)
        return 0
    finally:
        # Tear down only the uniquely named resources created by this probe.
        if created_repository:
            if backend == "nexus":
                api("DELETE", "/service/rest/v1/repositories/" + name)
            else:
                if policy is not None:
                    api("DELETE", f"/internal/cleanup/policies/{policy['id']}?revision={policy['revision']}")
                    wait_for("cleanup runs to drain", lambda: not any(r["state"] in
                        ("PENDING", "QUEUED", "RUNNING") for r in api("GET",
                        f"/internal/cleanup/runs?policyId={policy['id']}")["items"]))
                api("DELETE", f"/internal/repositories/{name}")
        if backend == "nexus" and policy is not None:
            api("DELETE", "/service/rest/internal/cleanup-policies/" + name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--backend", choices=("kkrepo", "nexus"), default="kkrepo")
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    raise SystemExit(exercise(args.base_url, os.environ["KKREPO_PROBE_PASSWORD"], args.username, args.backend))
