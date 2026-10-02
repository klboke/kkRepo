#!/usr/bin/env python3
"""Compare Docker proxy/group recovery after same-host bearer challenge bursts with Nexus."""
import argparse
import base64
import concurrent.futures
import hashlib
import http.server
import ipaddress
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


MEDIA = 'application/vnd.oci.image.manifest.v1+json'
MANIFEST = json.dumps({'schemaVersion': 2, 'mediaType': MEDIA,
    'config': {'mediaType': 'application/vnd.oci.image.config.v1+json', 'size': 2,
               'digest': 'sha256:' + hashlib.sha256(b'{}').hexdigest()}, 'layers': []}).encode()
DIGEST = 'sha256:' + hashlib.sha256(MANIFEST).hexdigest()


def request(base, path, auth, method='GET', data=None):
    req = urllib.request.Request(base + path,
        None if data is None else json.dumps(data).encode(), method=method,
        headers={'Authorization': 'Basic ' + base64.b64encode(auth.encode()).decode(),
                 'Accept': MEDIA if '/manifests/' in path else 'application/json',
                 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        with error:
            return error.code, error.read(), error.headers


class Registry(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        uri = urllib.parse.urlsplit(self.path)
        path = uri.path
        image = path.removeprefix('/v2/').split('/manifests/')[0]
        scope = 'repository:' + image + ':pull'
        expected_token = base64.urlsafe_b64encode(scope.encode()).decode()
        headers = {}
        status, body = 200, b'{}'
        if path == '/token':
            with self.server.lock:
                self.server.tokens += 1
            requested_scope = urllib.parse.parse_qs(uri.query).get('scope', [''])[0]
            token = base64.urlsafe_b64encode(requested_scope.encode()).decode()
            body = json.dumps({'token': token, 'expires_in': 300}).encode()
        elif path == '/v2/':
            pass
        elif self.headers.get('Authorization') != 'Bearer ' + expected_token:
            with self.server.lock:
                self.server.challenges += 1
                self.server.arrivals += 1
                if self.server.arrivals >= self.server.challenge_barrier:
                    self.server.ready.set()
            if not self.server.ready.wait(15):
                self.send_error(503, 'concurrent challenge barrier was not reached')
                return
            headers['WWW-Authenticate'] = ('Bearer realm="' + self.server.upstream
                + '/token",service="fixture",scope="' + scope + '"')
            status, body = 401, b'{"errors":[{"code":"UNAUTHORIZED","message":"token required"}]}'
        elif '/missing/' in path:
            status, body = 404, b'{"errors":[{"code":"NAME_UNKNOWN","message":"not found"}]}'
        elif '/manifests/' in path:
            body = MANIFEST
            headers['Docker-Content-Digest'] = DIGEST
        else:
            status = 404
        self.send_response(status)
        self.send_header('Docker-Distribution-API-Version', 'registry/2.0')
        self.send_header('Content-Type', MEDIA if body == MANIFEST else 'application/json')
        self.send_header('Content-Length', str(len(body)))
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)


class RegistryServer(http.server.ThreadingHTTPServer):
    request_queue_size = 128
    daemon_threads = True


def exercise(base, auth, nexus, host, concurrency):
    server = RegistryServer(('0.0.0.0', 0), Registry)
    server.lock, server.ready = threading.Lock(), threading.Event()
    server.challenges = server.tokens = server.arrivals = 0
    server.challenge_barrier = min(20, concurrency)
    server.upstream = 'http://' + host + ':' + str(server.server_port)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    catalog = '/service/rest/v1/repositories' if nexus else '/internal/repositories'
    ssrf = '/service/rest/v1/security/ssrf-protection'
    repositories, added = [], False
    try:
        ipaddress.ip_address(host)
        allow_field = 'allowedIPs'
    except ValueError:
        allow_field = 'allowedDomains'
    try:
        if nexus:
            status, body, _ = request(base, ssrf, auth)
            if status == 200:
                policy = json.loads(body)
                if host not in policy.setdefault(allow_field, []):
                    policy[allow_field].append(host)
                    assert request(base, ssrf, auth, 'PUT', policy)[0] == 200
                    added = True
            else:
                assert status == 404, (status, body)  # Older Nexus versions lack this API.
        proxy = 'compat-docker-auth-' + uuid.uuid4().hex[:10]
        for recipe, name in [('proxy', proxy), ('group', proxy + '-group')]:
            payload = {'name': name, 'online': True}
            if nexus:
                payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False},
                    docker={'v1Enabled': False, 'forceBasicAuth': True})
            else:
                payload.update(recipe='docker-' + recipe, blobStoreName='default')
            if recipe == 'proxy':
                if nexus:
                    payload.update(proxy={'remoteUrl': server.upstream, 'contentMaxAge': 0, 'metadataMaxAge': 0},
                        negativeCache={'enabled': False, 'timeToLive': 1},
                        dockerProxy={'indexType': 'REGISTRY', 'cacheForeignLayers': False,
                                     'foreignLayerUrlWhitelist': []},
                        httpClient={'blocked': False, 'autoBlock': False})
                else:
                    payload.update(proxy={'remoteUrl': server.upstream,
                        'contentMaxAgeMinutes': 0, 'metadataMaxAgeMinutes': 0})
            else:
                payload['group'] = {'memberNames': [proxy]}
            status, body, _ = request(base, catalog + ('/docker/' + recipe if nexus else ''),
                                      auth, 'POST', payload)
            assert status == 201, (status, body)
            repositories.append(name)
        for name in repositories:
            prefix = '/repository/' + name + '/v2/' if nexus else '/v2/' + name + '/'
            for method in ['GET', 'HEAD']:
                with server.lock:
                    server.arrivals = 0
                    server.ready.clear()
                    before_tokens = server.tokens
                unique = uuid.uuid4().hex
                started = time.monotonic()
                with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as workers:
                    responses = list(workers.map(lambda i: request(base,
                        prefix + 'missing/' + unique + '/img' + str(i) + '/manifests/v1', auth, method),
                        range(concurrency)))
                assert all(response[0] == 404 for response in responses), [r[0] for r in responses]
                assert server.arrivals >= server.challenge_barrier and server.tokens > before_tokens, 'fixture must exercise token acquisition'
                # A new image scope proves that the burst did not poison subsequent cold pulls.
                path = prefix + 'healthy/' + unique + '/manifests/latest'
                status, body, headers = request(base, path, auth, method)
                assert status == 200, (status, body)
                assert headers.get('Docker-Content-Digest') == DIGEST, dict(headers)
                assert body == (MANIFEST if method == 'GET' else b''), body
                print(('Nexus' if nexus else 'kkRepo') + ' ' + name + ' ' + method
                      + ': ' + str(concurrency) + ' authenticated misses + cold manifest recovery PASS ('
                      + str(round(time.monotonic() - started, 2)) + 's)')
    finally:
        try:
            for name in reversed(repositories):
                status, body, _ = request(base, catalog + '/' + name, auth, 'DELETE')
                assert status in (200, 204), (status, body)
            if added:
                status, body, _ = request(base, ssrf, auth)
                assert status == 200, (status, body)
                policy = json.loads(body)
                policy[allow_field] = [entry for entry in policy.get(allow_field, []) if entry != host]
                assert request(base, ssrf, auth, 'PUT', policy)[0] == 200
        finally:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kkrepo')
    parser.add_argument('--nexus')
    parser.add_argument('--upstream-host', default='host.docker.internal')
    parser.add_argument('--concurrency', type=int, default=25)
    args = parser.parse_args()
    if not args.kkrepo and not args.nexus:
        parser.error('Specify --kkrepo or --nexus')
    if args.concurrency < 1:
        parser.error('--concurrency must be positive')
    if args.nexus:
        exercise(args.nexus, os.environ.get('NEXUS_COMPAT_AUTH', 'admin:Admin1234'), True, args.upstream_host, args.concurrency)
    if args.kkrepo:
        exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:12345678'), False, args.upstream_host, args.concurrency)
