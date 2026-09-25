#!/usr/bin/env python3
"""Exercise paginated Docker proxy/group tags against kkRepo or a Nexus reference.

The upstream rejects a query separator escaped into the path. Only this fixture's
repositories and temporary Nexus SSRF exception are removed.
"""
import argparse
import base64
import http.server
import ipaddress
import json
import os
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid


def request(base, path, auth, method='GET', data=None):
    req = urllib.request.Request(base + path,
        None if data is None else json.dumps(data).encode(), method=method,
        headers={'Authorization': 'Basic ' + base64.b64encode(auth.encode()).decode(),
                 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


class Registry(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append(self.path)
        uri = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(uri.query)
        if uri.path == '/v2/':
            body = {}
        elif uri.path in ['/v2/team/app/tags/list', '/v2/_catalog']:
            catalog = uri.path.endswith('_catalog')
            values = ['team/a', 'team/b', 'team/c'] if catalog else ['v1.0', 'v1.1', 'v1.2']
            values = [value for value in values if value > query.get('last', [''])[0]]
            limit = int(query.get('n', ['100'])[0])
            body = {'repositories': values[:limit]} if catalog else {'name': 'team/app', 'tags': values[:limit]}
            if len(values) > limit:
                next_query = urllib.parse.urlencode({'n': limit, 'last': values[limit - 1]})
                self.next_link = '<' + uri.path + '?' + next_query + '>; rel="next"'
        else:
            self.send_response(404)
            self.end_headers()
            return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Docker-Distribution-API-Version', 'registry/2.0')
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        if getattr(self, 'next_link', None):
            self.send_header('Link', self.next_link)
        self.end_headers()
        self.wfile.write(data)


def exercise(base, auth, nexus, host):
    server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Registry)
    server.requests = []
    threading.Thread(target=server.serve_forever, daemon=True).start()
    upstream = 'http://' + host + ':' + str(server.server_port)
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
            assert status == 200, (status, body)
            policy = json.loads(body)
            if host not in policy.setdefault(allow_field, []):
                policy[allow_field].append(host)
                assert request(base, ssrf, auth, 'PUT', policy)[0] == 200
                added = True
        proxy = 'compat-docker-pagination-' + uuid.uuid4().hex[:10]
        payload = {'name': proxy, 'online': True}
        if nexus:
            payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False},
                proxy={'remoteUrl': upstream, 'contentMaxAge': 0, 'metadataMaxAge': 0},
                negativeCache={'enabled': False, 'timeToLive': 1},
                docker={'v1Enabled': False, 'forceBasicAuth': True},
                dockerProxy={'indexType': 'REGISTRY', 'cacheForeignLayers': False, 'foreignLayerUrlWhitelist': []},
                httpClient={'blocked': False, 'autoBlock': False})
        else:
            payload.update(recipe='docker-proxy', blobStoreName='default',
                proxy={'remoteUrl': upstream, 'contentMaxAgeMinutes': 0, 'metadataMaxAgeMinutes': 0})
        status, body, _ = request(base, catalog + ('/docker/proxy' if nexus else ''), auth, 'POST', payload)
        assert status == 201, (status, body)
        repositories.append(proxy)
        group = proxy + '-group'
        payload = {'name': group, 'online': True, 'group': {'memberNames': [proxy]}}
        if nexus:
            payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False},
                docker={'v1Enabled': False, 'forceBasicAuth': True})
        else:
            payload.update(recipe='docker-group', blobStoreName='default')
        status, body, _ = request(base, catalog + ('/docker/group' if nexus else ''), auth, 'POST', payload)
        assert status == 201, (status, body)
        repositories.append(group)
        for name in repositories:
            prefix = '/repository/' + name + '/v2/' if nexus else '/v2/' + name + '/'
            for suffix, field, values in [('team/app/tags/list', 'tags', ['v1.0', 'v1.1', 'v1.2'])] + (
                    [] if nexus else [('_catalog', 'repositories', ['team/a', 'team/b', 'team/c'])]):
                last = None
                for expected in values:
                    query = {'n': 1}
                    if last is not None:
                        query['last'] = last
                    status, body, headers = request(base, prefix + suffix + '?' + urllib.parse.urlencode(query), auth)
                    assert status == 200, (name, status, body)
                    result = json.loads(body)
                    assert result[field] == [expected], (name, suffix, query, result)
                    if not nexus and expected != values[-1]:
                        assert 'rel="next"' in headers.get('Link', ''), dict(headers)
                    last = expected
        assert not any('%3f' in path.lower() for path in server.requests), server.requests
        if not nexus:
            assert any('/tags/list?n=' in path for path in server.requests), server.requests
            assert any('/_catalog?n=' in path and 'last=team%2F' in path for path in server.requests), server.requests
        print(('Nexus' if nexus else 'kkRepo') + ' Docker proxy/group tags pagination and upstream paths: PASS')
        if not nexus:
            print('kkRepo proxy/group catalog pagination, encoded cursor and Link headers: PASS')
    finally:
        for name in reversed(repositories):
            status, body, _ = request(base, catalog + '/' + name, auth, 'DELETE')
            assert status in (200, 204), (status, body)
        if added:
            status, body, _ = request(base, ssrf, auth)
            policy = json.loads(body)
            policy[allow_field] = [entry for entry in policy.get(allow_field, []) if entry != host]
            assert request(base, ssrf, auth, 'PUT', policy)[0] == 200
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kkrepo')
    parser.add_argument('--nexus')
    parser.add_argument('--upstream-host', default='host.docker.internal')
    args = parser.parse_args()
    if not args.kkrepo and not args.nexus:
        parser.error('Specify --kkrepo or --nexus')
    if args.nexus:
        exercise(args.nexus, os.environ.get('NEXUS_COMPAT_AUTH', 'admin:Admin1234'), True, args.upstream_host)
    if args.kkrepo:
        exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:123456'), False, args.upstream_host)
