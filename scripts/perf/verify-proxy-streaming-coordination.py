#!/usr/bin/env python3
"""Verify stale proxy fills across two kkRepo processes sharing a database and Blob store.

Use disposable test instances. Leaves one uniquely named fixture repository for inspection.
Credentials come from KKREPO_BENCH_USER / KKREPO_BENCH_PASSWORD, never the output.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--peer-url', required=True)
    parser.add_argument('--blob-store', required=True)
    parser.add_argument('--upstream-host', default='127.0.0.1')
    parser.add_argument('--upstream-port', type=int, default=38546)
    args = parser.parse_args()
    base, peer = args.base_url.rstrip('/'), args.peer_url.rstrip('/')
    auth = 'Basic ' + base64.b64encode((os.environ.get('KKREPO_BENCH_USER', 'admin') + ':'
                                      + os.environ['KKREPO_BENCH_PASSWORD']).encode()).decode()
    old, new = b'o' * (2 * 1024 * 1024), b'n' * (2 * 1024 * 1024)
    phase = {'old': True}
    release = threading.Event()
    first = [threading.Event(), threading.Event()]
    repo = 'stream-fence-' + uuid.uuid4().hex[:8]

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_GET(self):
            stale = phase['old']
            data = old if stale else new
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data[:65536])
            self.wfile.flush()
            if stale:
                release.wait(30)
            self.wfile.write(data[65536:])

    server = ThreadingHTTPServer(('0.0.0.0', args.upstream_port), Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def api(method, path, data=None):
        request = urllib.request.Request(base + path,
            None if data is None else json.dumps(data).encode(), method=method,
            headers={'Authorization': auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    def download(url, index=None):
        parsed = urllib.parse.urlsplit(url)
        cls = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
        connection = cls(parsed.hostname, parsed.port, timeout=40)
        try:
            connection.request('GET', parsed.path + '/repository/' + repo + '/data.bin',
                               headers={'Authorization': auth})
            response = connection.getresponse()
            first_byte = response.read(1)
            if index is not None:
                first[index].set()
            return response.status, first_byte + response.read()
        finally:
            connection.close()

    try:
        api('POST', '/internal/repositories', dict(
            name=repo, recipe='raw-proxy', online=True, blobStoreName=args.blob_store,
            strictContentTypeValidation=False, proxy=dict(
                remoteUrl=f'http://{args.upstream_host}:{args.upstream_port}',
                streamingEnabled=True, autoBlock=False)))
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(download, url, index) for index, url in enumerate([base, peer])]
            try:
                assert all(event.wait(15) for event in first), 'both replicas must stream before upstream EOF'
                api('PUT', '/internal/repositories/' + repo, {'proxy': {'streamingEnabled': False}})
                api('PUT', '/internal/repositories/' + repo, {'proxy': {'streamingEnabled': True}})
                phase['old'] = False
                assert download(base) == (200, new)
                deadline = time.monotonic() + 15
                while not api('GET', '/internal/browse/' + repo)['entries']:
                    assert time.monotonic() < deadline, 'new fill did not publish'
                    time.sleep(0.05)
            finally:
                # Also unblock the HTTP handlers if an assertion fails.
                release.set()
            assert all(future.result(timeout=20) == (200, old) for future in futures)
        # Allow the two superseded writers to complete their small object-store attempts.
        time.sleep(2)
        assert download(base) == (200, new) and download(peer) == (200, new), 'stale fill replaced newer cache'
        entries = api('GET', '/internal/browse/' + repo)['entries']
        assert len(entries) == 1 and entries[0]['name'] == 'data.bin'
        print(json.dumps(dict(test='two-process stale-writer publication', repository=repo,
            replicas=2, old_clients_completed=True, new_cache_preserved_on_both=True,
            visible_assets=len(entries), old_sha256=hashlib.sha256(old).hexdigest(),
            new_sha256=hashlib.sha256(new).hexdigest())))
    finally:
        release.set()
        server.shutdown()


if __name__ == '__main__':
    main()
