#!/usr/bin/env python3
"""Controlled cold/warm proxy benchmark. Creates uniquely named Raw proxy repositories.

Requires a disposable kkRepo instance and two *different* preconfigured blob stores.
Credentials come from KKREPO_BENCH_USER / KKREPO_BENCH_PASSWORD. Results contain no secrets.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import platform
from pathlib import Path
import statistics
import threading
import time
import urllib.parse
import urllib.request
import uuid


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', required=True)
    p.add_argument('--blob-store-off', required=True)
    p.add_argument('--blob-store-on', required=True)
    p.add_argument('--upstream-host', default='127.0.0.1', help='Host as reachable by kkRepo')
    p.add_argument('--upstream-port', type=int, default=38541)
    p.add_argument('--size-mib', type=int, default=64)
    p.add_argument('--rate-mib', type=float, default=4)
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--build-label', default='unspecified', help='Implementation commit or exact build identifier')
    args = p.parse_args()
    if args.blob_store_off == args.blob_store_on:
        p.error('Use separate stores to prevent cross-mode content deduplication')
    if min(args.size_mib, args.rate_mib, args.repeats) <= 0:
        p.error('size, rate and repeats must be positive')
    auth = 'Basic ' + base64.b64encode((os.environ.get('KKREPO_BENCH_USER', 'admin') + ':'
                                      + os.environ['KKREPO_BENCH_PASSWORD']).encode()).decode()
    run = uuid.uuid4().hex[:10]
    size = args.size_mib * 1024 * 1024
    # Unique bytes each trial, identical between OFF and ON; no compression in HTTP or S3.
    blocks = {f'{i}.bin': hashlib.shake_256(f'{run}-{i}'.encode()).digest(65536)
              for i in range(args.repeats)}
    blocks['warmup.bin'] = bytes(65536)
    requests = []

    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *unused):
            pass
        def do_GET(self):
            name = self.path.rsplit('/', 1)[-1]
            if name not in blocks:
                self.send_error(404)
                return
            total = 65536 if name == 'warmup.bin' else size
            start, end, status = 0, total - 1, 200
            etag = '"' + hashlib.sha256(blocks[name]).hexdigest() + '"'
            wanted = self.headers.get('Range')
            if wanted and self.headers.get('If-Range', etag) == etag:
                left, right = wanted.removeprefix('bytes=').split('-')
                start = int(left) if left else max(0, total - int(right))
                end = min(total - 1, int(right)) if left and right else total - 1
                status = 206
            if start >= total or end < start:
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{total}')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            requests.append({'name': name, 'range': wanted, 'bytes': end - start + 1})
            self.send_response(status)
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('ETag', etag)
            self.send_header('Last-Modified', 'Sat, 14 Nov 2026 00:00:00 GMT')
            self.send_header('Accept-Ranges', 'bytes')
            if status == 206:
                self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
            self.end_headers()
            begun = time.perf_counter()
            sent = 0
            try:
                while start <= end:
                    offset = start % 65536
                    chunk = blocks[name][offset:offset + min(65536 - offset, end - start + 1)]
                    # Include the final chunk's transfer time in the controlled link budget.
                    sent += len(chunk)
                    time.sleep(max(0, begun + sent / (args.rate_mib * 1024 * 1024) - time.perf_counter()))
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    start += len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('0.0.0.0', args.upstream_port), Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = args.base_url.rstrip('/')
    def api(method, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(base + path, data=data, method=method,
                                     headers={'Authorization': auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)

    def download(repo, filename, headers=None):
        url = urllib.parse.urlsplit(base)
        cls = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
        connection = cls(url.hostname, url.port, timeout=180)
        begun = time.perf_counter()
        connection.request('GET', f'{url.path}/repository/{repo}/{filename}',
                           headers={'Authorization': auth, **(headers or {})})
        response = connection.getresponse()
        header_seconds = time.perf_counter() - begun
        first = response.read(1)
        first_seconds = time.perf_counter() - begun
        count = len(first)
        digest = hashlib.sha256(first)
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            digest.update(chunk)
        elapsed = time.perf_counter() - begun
        assert len(response.headers.get_all('ETag', [])) <= 1, 'Response contains duplicate ETag fields'
        result = dict(status=response.status, bytes=count, sha256=digest.hexdigest(),
                      header_seconds=header_seconds, first_body_byte_seconds=first_seconds,
                      total_seconds=elapsed, etag=response.getheader('ETag'),
                      content_range=response.getheader('Content-Range'))
        connection.close()
        return result

    repos = {}
    rows = []
    try:
        for mode, store in [('off', args.blob_store_off), ('on', args.blob_store_on)]:
            repo = f'stream-{run}-{mode}'
            repos[mode] = repo
            api('POST', '/internal/repositories', dict(name=repo, recipe='raw-proxy', online=True,
                blobStoreName=store, strictContentTypeValidation=False,
                proxy=dict(remoteUrl=f'http://{args.upstream_host}:{args.upstream_port}/',
                           streamingEnabled=mode == 'on', contentMaxAgeMinutes=1440,
                           metadataMaxAgeMinutes=1440, autoBlock=False)))
            assert download(repo, 'warmup.bin')['bytes'] == 65536
        for trial in range(args.repeats):
            # Alternate order to reduce JVM/storage warmup bias.
            for mode in (['off', 'on'] if trial % 2 == 0 else ['on', 'off']):
                filename = f'{trial}.bin'
                expected = hashlib.sha256(blocks[filename] * (size // 65536)).hexdigest()
                before = len(requests)
                cold = download(repos[mode], filename)
                assert cold['status'] == 200 and cold['bytes'] == size and cold['sha256'] == expected, cold
                completed = time.perf_counter()
                while True:
                    entries = api('GET', f'/internal/browse/{repos[mode]}').get('entries', [])
                    if any(entry.get('name') == filename for entry in entries):
                        break
                    if time.perf_counter() - completed > 90:
                        raise RuntimeError('Cache publication timeout')
                    time.sleep(0.05)
                cache_lag = time.perf_counter() - completed
                after_fill = len(requests)
                warm = download(repos[mode], filename)
                assert warm['sha256'] == expected and warm['bytes'] == size, warm
                assert len(requests) == after_fill, 'Warm GET contacted upstream'
                resumed = download(repos[mode], filename, {'Range': 'bytes=1048576-', 'If-Range': warm['etag']})
                if mode == 'on':
                    assert resumed['status'] == 206 and resumed['bytes'] == size - 1048576, resumed
                    assert resumed['sha256'] == hashlib.sha256(blocks[filename] * ((size - 1048576) // 65536)).hexdigest()
                else:
                    assert resumed['status'] in (200, 206), resumed
                row = dict(trial=trial + 1, mode=mode, cold=cold, warm=warm,
                           cache_visible_after_client_seconds=cache_lag, upstream_requests=after_fill - before,
                           warm_resume=resumed)
                rows.append(row)
                print(json.dumps(row), flush=True)
        summary = {mode: {phase: {metric: statistics.median(
            row[phase][metric] for row in rows if row['mode'] == mode)
            for metric in ['first_body_byte_seconds', 'total_seconds']}
            for phase in ['cold', 'warm']} for mode in ['off', 'on']}
        output = dict(run_id=run, recorded_at=datetime.now(timezone.utc).isoformat(),
                      build_label=args.build_label, client_platform=platform.platform(),
                      client_python=platform.python_version(),
                      size_bytes=size, upstream_mib_per_second=args.rate_mib,
                      repeats=args.repeats, repositories=repos, results=rows, median=summary)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, indent=2) + '\n')
        print(json.dumps(summary, indent=2))
    finally:
        server.shutdown()
        # Keep uniquely named fixtures for inspection; documented cleanup uses these exact names.

if __name__ == '__main__':
    main()
