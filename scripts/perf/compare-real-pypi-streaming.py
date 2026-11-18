#!/usr/bin/env python3
"""Compare cold/warm OFF/ON downloads of one real PyPI artifact, without throttling.

Requires a disposable kkRepo instance, public PyPI access, and a private JSON S3
BlobStoreRequest template. Creates one new repository AND storage prefix per sample.
The template and KKREPO_BENCH_PASSWORD are never written to the result.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import http.client
import json
import os
from pathlib import Path
import platform
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hrefs = []

    def handle_starttag(self, tag, attrs):
        if tag == 'a':
            self.hrefs.extend(value for key, value in attrs if key == 'href' and value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--management-url', required=True)
    parser.add_argument('--metadata-file', type=Path, required=True,
                        help='Official https://pypi.org/pypi/PROJECT/VERSION/json response')
    parser.add_argument('--filename', required=True)
    parser.add_argument('--blob-store-template', type=Path, required=True,
                        help='Private S3 BlobStoreRequest JSON, including credentials')
    parser.add_argument('--repeats', type=int, default=1,
                        help='OFF/ON pairs; each pair downloads the public artifact twice')
    parser.add_argument('--build-label', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error('repeats must be positive')
    metadata = json.loads(args.metadata_file.read_text())
    artifact = next(item for item in metadata['urls'] if item['filename'] == args.filename)
    template = json.loads(args.blob_store_template.read_text())
    if template['type'] != 's3':
        parser.error('Use an S3 store template with a dedicated benchmark bucket/prefix')
    project, version = metadata['info']['name'], metadata['info']['version']
    size, expected = artifact['size'], artifact['digests']['sha256']
    auth = 'Basic ' + base64.b64encode((os.environ.get('KKREPO_BENCH_USER', 'admin') + ':'
                                      + os.environ['KKREPO_BENCH_PASSWORD']).encode()).decode()
    base = args.base_url.rstrip('/')
    run = uuid.uuid4().hex[:10]
    rows, fixtures = [], []
    output = dict(run_id=run, started_at=datetime.now(timezone.utc).isoformat(),
                  build_label=args.build_label, client_platform=platform.platform(),
                  client_python=platform.python_version(), repeats=args.repeats,
                  artifact=dict(project=project, version=version, filename=args.filename,
                                size_bytes=size, sha256=expected, url=artifact['url'],
                                metadata_url=f'https://pypi.org/pypi/{project}/{version}/json'),
                  method=dict(recipe='pypi-proxy', remote_url='https://pypi.org/',
                              concurrency=1, artificial_throttling=False,
                              client_socket_timeout_seconds=1800,
                              index_prewarmed=True, unique_store_per_sample=True,
                              cache_poll_interval_seconds=0.1),
                  fixtures=fixtures, results=rows)

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, indent=2) + '\n')

    def request(url, method='GET', payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={'Authorization': auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.read()

    def api(path, method='GET', payload=None):
        return json.loads(request(base + path, method, payload))

    def remote_count(repo):
        query = urllib.parse.urlencode({'tag': f'repo:{repo}'})
        url = args.management_url.rstrip('/') + '/actuator/metrics/kkrepo_proxy_remote_requests_total?' + query
        try:
            data = json.loads(request(url))
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return 0
            raise
        return sum(item['value'] for item in data['measurements'] if item['statistic'] == 'COUNT')

    def download(url, label):
        target = urllib.parse.urlsplit(url)
        cls = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
        connection = cls(target.hostname, target.port, timeout=1800)
        started_at = datetime.now(timezone.utc).isoformat()
        print(json.dumps(dict(event='download_started', sample=label, at=started_at)), flush=True)
        response = None
        count, digest = 0, hashlib.sha256()
        header_seconds = first_seconds = None
        begun = time.perf_counter()
        try:
            connection.request('GET', target.path + ('?' + target.query if target.query else ''),
                               headers={'Authorization': auth})
            response = connection.getresponse()
            header_seconds = time.perf_counter() - begun
            first = response.read(1)
            first_seconds = time.perf_counter() - begun
            print(json.dumps(dict(event='first_body_byte', sample=label,
                                  elapsed_seconds=first_seconds)), flush=True)
            count = len(first)
            digest = hashlib.sha256(first)
            progress = time.perf_counter()
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                digest.update(chunk)
                if time.perf_counter() - progress >= 30:
                    print(json.dumps(dict(event='progress', sample=label, bytes=count,
                                          elapsed_seconds=time.perf_counter() - begun)), flush=True)
                    progress = time.perf_counter()
            elapsed = time.perf_counter() - begun
            assert response.status == 200, response.status
            assert count == size and digest.hexdigest() == expected, (count, digest.hexdigest())
            assert int(response.getheader('Content-Length')) == size
            assert len(response.headers.get_all('ETag', [])) <= 1
            return dict(started_at=started_at, status=response.status, bytes=count,
                        sha256=digest.hexdigest(), header_seconds=header_seconds,
                        first_body_byte_seconds=first_seconds, total_seconds=elapsed,
                        average_mib_per_second=count / 1024**2 / elapsed,
                        etag=response.getheader('ETag'))
        except Exception as error:
            output.setdefault('failed_downloads', []).append(dict(
                sample=label, started_at=started_at, error_type=type(error).__name__,
                status=None if response is None else response.status, bytes=count,
                partial_sha256=digest.hexdigest(), header_seconds=header_seconds,
                first_body_byte_seconds=first_seconds, elapsed_seconds=time.perf_counter() - begun))
            save()
            raise
        finally:
            connection.close()

    for trial in range(1, args.repeats + 1):
        for mode in (['off', 'on'] if trial % 2 else ['on', 'off']):
            name = f'pypi-real-{run}-{trial}-{mode}'
            store = dict(template, name=name,
                         prefix='/'.join(filter(None, [template.get('prefix', '').strip('/'), name])))
            api('/internal/blob-stores', 'POST', store)
            fixtures.append(dict(repository=name, blob_store=name, prefix=store['prefix']))
            save()
            api('/internal/repositories', 'POST', dict(name=name, recipe='pypi-proxy', online=True,
                blobStoreName=name, strictContentTypeValidation=True,
                proxy=dict(remoteUrl='https://pypi.org/', streamingEnabled=mode == 'on',
                           contentMaxAgeMinutes=1440, metadataMaxAgeMinutes=1440, autoBlock=False)))
            # Resolve the package through kkRepo's actual rewritten Simple API page, as pip does.
            index_url = f'{base}/repository/{name}/simple/{project}/'
            links = Links()
            links.feed(request(index_url).decode())
            href = next(link for link in links.hrefs
                        if urllib.parse.urlsplit(link).path.endswith('/' + args.filename))
            artifact_url = urllib.parse.urljoin(index_url, href).split('#')[0]
            assert artifact_url.startswith(f'{base}/repository/{name}/'), artifact_url
            browse = f'/internal/browse/{name}?' + urllib.parse.urlencode({'path': f'{project}/{version}'})
            assert not any(e['name'] == args.filename for e in api(browse).get('entries', []))
            before = remote_count(name)
            cold = download(artifact_url, f'{trial}-{mode}-cold')
            completed = time.perf_counter()
            while not any(e['name'] == args.filename for e in api(browse).get('entries', [])):
                if time.perf_counter() - completed > 180:
                    raise RuntimeError('Cache publication timeout')
                time.sleep(0.1)
            cache_lag = time.perf_counter() - completed
            after_fill = remote_count(name)
            assert after_fill > before, 'Cold GET did not register an upstream request'
            warm = download(artifact_url, f'{trial}-{mode}-warm')
            after_warm = remote_count(name)
            assert after_warm == after_fill, 'Warm GET contacted upstream'
            row = dict(trial=trial, mode=mode, repository=name, cold=cold, warm=warm,
                       cache_visible_after_client_seconds=cache_lag,
                       cold_upstream_requests=after_fill - before,
                       warm_upstream_requests=after_warm - after_fill)
            rows.append(row)
            save()
            print(json.dumps(row), flush=True)
    output['completed_at'] = datetime.now(timezone.utc).isoformat()
    output['median'] = {mode: {phase: {metric: statistics.median(
        row[phase][metric] for row in rows if row['mode'] == mode)
        for metric in ['first_body_byte_seconds', 'total_seconds', 'average_mib_per_second']}
        for phase in ['cold', 'warm']} for mode in ['off', 'on']}
    output['median_cache_visible_after_client_seconds'] = {
        mode: statistics.median(row['cache_visible_after_client_seconds'] for row in rows if row['mode'] == mode)
        for mode in ['off', 'on']}
    save()
    print(json.dumps(output['median'], indent=2))
    # Retain the uniquely named fixtures for inspection; output lists their exact names/prefixes.


if __name__ == '__main__':
    main()
