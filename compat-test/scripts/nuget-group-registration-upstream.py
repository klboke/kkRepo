#!/usr/bin/env python3
"""Compare NuGet group registration merging with Nexus and verify kkRepo source routing.

Creates two paginated upstreams with an overlapping version and distinct signed package
links. Only this fixture's repositories and temporary Nexus SSRF exception are removed.
"""
import argparse
import http.server
import importlib.util
import ipaddress
import json
import io
import zipfile
import os
from pathlib import Path
import threading
import urllib.parse
import uuid

spec = importlib.util.spec_from_file_location('ntlm_fixture', Path(__file__).with_name('ntlm-upstream.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
request = fixture.request


class Upstream(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        server = self.server
        server.requests.append(self.path)
        uri = urllib.parse.urlsplit(self.path)
        if uri.path == '/feed':
            server.root_probes += 1
            if server.fail_root:
                self.send_response(503)
                self.end_headers()
                return
            body = b'<html>Repository root</html>'
        elif uri.path in ['/index.json', '/feed/index.json']:
            body = {'version': '3.0.0', 'resources': [
                {'@type': 'PackageBaseAddress/3.0.0', '@id': server.root + '/flat/' + server.resource_query},
                {'@type': 'RegistrationsBaseUrl/3.4.0', '@id': server.root + '/reg/' + server.resource_query},
                {'@type': 'RegistrationsBaseUrl/3.6.0', '@id': server.root + '/reg/' + server.resource_query}]}
        elif uri.path == '/flat/demo/index.json':
            body = {'versions': server.versions}
        elif uri.path == '/reg/demo/index.json':
            body = {'@id': server.root + uri.path, 'source': server.feed, 'count': 1, 'items': [
                {'@id': server.root + '/reg/demo/page.json?' + server.page_query, 'count': 2,
                 'lower': server.versions[0], 'upper': server.versions[1]}]}
        elif uri.path == '/reg/demo/page.json':
            leaves = []
            for version in server.versions:
                leaf = server.root + '/reg/demo/' + version + '.json'
                leaves.append({'@id': leaf, 'parent': server.root + '/reg/demo/page.json',
                    'registration': server.root + '/reg/demo/index.json', 'catalogEntry': {
                    '@id': leaf, 'id': 'demo', 'version': version, 'listed': True,
                    'description': server.feed, 'dependencyGroups': [{'targetFramework': 'net8.0',
                        'dependencies': [{'id': 'dep-' + server.feed, 'range': '[1.0.0,)'}]}]},
                    'packageContent': server.root + '/flat/demo/' + version + '/demo.' + version
                                      + '.nupkg' + ('' if version == '1.9.0' else '?' + server.signed_query)})
            body = {'@id': server.root + uri.path, 'source': server.feed, 'count': 2, 'parent': server.root + '/reg/demo/index.json',
                    'lower': server.versions[0], 'upper': server.versions[1], 'items': leaves}
        elif uri.path.startswith('/reg/demo/') and uri.path.endswith('.json'):
            # The earlier member can serve a leaf omitted from its registration index.
            # A merged link must still reach the member selected by that index.
            body = {'@id': server.root + uri.path, 'source': server.feed}
        elif uri.path.endswith('.nupkg') and uri.path.split('/')[3] in server.versions:
            expected_query = server.resource_query.lstrip('?') if uri.path.split('/')[3] == '1.9.0' else server.signed_query
            if uri.query != expected_query:
                self.send_response(403)
                self.end_headers()
                return
            body = server.packages[uri.path.split('/')[3]]
        else:
            self.send_response(404)
            self.end_headers()
            return
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream' if isinstance(body, bytes) else 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)


def exercise(base, credentials, nexus, host, ordered_query=False, secondary=None):
    servers, repositories = [], []
    added = False
    ssrf = '/service/rest/v1/security/ssrf-protection'
    catalog = '/service/rest/v1/repositories' if nexus else '/internal/repositories'
    try:
        ipaddress.ip_address(host)
        allow_field = 'allowedIPs'
    except ValueError:
        allow_field = 'allowedDomains'
    try:
        if nexus:
            status, body, _ = request(base, ssrf, credentials)
            assert status == 200, (status, body)
            config = json.loads(body)
            if host not in config.setdefault(allow_field, []):
                config[allow_field].append(host)
                assert request(base, ssrf, credentials, 'PUT', config)[0] == 200
                added = True
        for feed, versions in [('a', ['1.0.0', '1.10.0']), ('b', ['1.0.0', '1.9.0'])]:
            server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Upstream)
            server.root = 'http://' + host + ':' + str(server.server_port)
            server.feed, server.versions, server.requests = feed, versions, []
            server.resource_query = '?key=private' if ordered_query else ''
            server.root_probes, server.fail_root = 0, False
            server.page_query = 'api=one' + ('&key=private' if ordered_query else '')
            server.signed_query = 'sig=' + feed + ('&key=private&cursor=1' if ordered_query else '')
            server.packages = {}
            for version in versions:
                archive = io.BytesIO()
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('demo.nuspec', '<package><metadata><id>demo</id><version>' + version
                        + '</version><authors>fixture</authors><description>' + feed + '</description></metadata></package>')
                    package.writestr('content/source.txt', feed)
                server.packages[version] = archive.getvalue()
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            name = 'compat-nuget-registration-' + uuid.uuid4().hex[:10]
            payload = {'name': name, 'online': True}
            if nexus:
                payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False},
                    proxy={'remoteUrl': server.root + '/index.json', 'contentMaxAge': 0, 'metadataMaxAge': 0},
                    negativeCache={'enabled': False, 'timeToLive': 1},
                    nugetProxy={'queryCacheItemMaxAge': 0, 'nugetVersion': 'V3'},
                    httpClient={'blocked': False, 'autoBlock': False})
            else:
                payload.update(recipe='nuget-proxy', blobStoreName='default', strictContentTypeValidation=False,
                    proxy={'remoteUrl': server.root + ('/feed' if ordered_query else '/index.json'), 'contentMaxAgeMinutes': 0,
                           'metadataMaxAgeMinutes': 60, 'autoBlock': ordered_query})
            status, body, _ = request(base, catalog + ('/nuget/proxy' if nexus else ''), credentials, 'POST', payload)
            assert status == 201, (status, body)
            repositories.append(name)
        group = 'compat-nuget-registration-group-' + uuid.uuid4().hex[:10]
        payload = {'name': group, 'online': True, 'group': {'memberNames': list(repositories)}}
        if nexus:
            payload['storage'] = {'blobStoreName': 'default', 'strictContentTypeValidation': False}
        else:
            payload.update(recipe='nuget-group', blobStoreName='default', strictContentTypeValidation=False)
        status, body, _ = request(base, catalog + ('/nuget/group' if nexus else ''), credentials, 'POST', payload)
        assert status == 201, (status, body)
        repositories.append(group)
        status, body, _ = request(base, '/repository/' + group + '/index.json', credentials)
        assert status == 200, (status, body)
        registration = next(r['@id'] for r in json.loads(body)['resources'] if r['@type'] == 'RegistrationsBaseUrl/3.6.0')
        status, body, _ = request(base, urllib.parse.urlsplit(registration).path + 'demo/index.json', credentials)
        assert status == 200, (status, body)
        index = json.loads(body)
        if ordered_query:
            for server in servers:
                assert server.root_probes == 1, server.root_probes
                server.fail_root = True
            if secondary:
                base = secondary
                status, body, _ = request(base, urllib.parse.urlsplit(registration).path + 'demo/index.json', credentials)
                assert status == 200, (status, body)
                index = json.loads(body)
        assert index['count'] == 1, index
        page = index['items'][0]
        assert (page['count'], page['lower'], page['upper']) == (3, '1.0.0', '1.10.0'), page
        assert [leaf['catalogEntry']['version'] for leaf in page['items']] == ['1.0.0', '1.9.0', '1.10.0']
        if not nexus:
            assert all(any(path.endswith('/page.json?' + server.page_query) for path in server.requests) for server in servers)
        for leaf in page['items']:
            version = leaf['catalogEntry']['version']
            source = 'b' if version == '1.9.0' else 'a'
            assert leaf['catalogEntry']['description'] == source, leaf
            assert leaf['catalogEntry']['dependencyGroups'][0]['dependencies'][0]['id'] == 'dep-' + source, leaf
            link = urllib.parse.urlsplit(leaf['packageContent'])
            assert 'private' not in link.query, link
            assert link.netloc == urllib.parse.urlsplit(base).netloc, link
            assert urllib.parse.parse_qs(link.query).get('sig', []) == ([] if version == '1.9.0' else [source]), link
            if not nexus:
                for field in ['@id', 'parent', 'registration']:
                    metadata_link = urllib.parse.urlsplit(leaf[field])
                    status, metadata, _ = request(base, metadata_link.path + '?' + metadata_link.query, credentials)
                    assert status == 200 and json.loads(metadata)['source'] == source, (field, status, metadata)
                for server in servers:
                    server.requests.clear()
                for method in ['GET', 'HEAD']:
                    status, body, headers = request(base, link.path + '?' + link.query, credentials, method)
                    expected = servers[0 if source == 'a' else 1].packages[version]
                    assert status == 200 and int(headers['Content-Length']) == len(expected), (status, body)
                    if method == 'GET':
                        assert body == expected
                other = servers[1 if source == 'a' else 0]
                assert not other.requests, other.requests
                selected = servers[0 if source == 'a' else 1]
                assert all('_kkrepoNugetSource' not in path for path in selected.requests), selected.requests
                assert all('_kkrepoNugetQuery' not in path for path in selected.requests), selected.requests
        if ordered_query:
            # Follow the direct proxy's external page and package links as well.
            prefix = '/repository/' + repositories[0] + '/v3/registration5-semver2/demo/'
            status, body, _ = request(base, prefix + 'index.json', credentials)
            assert status == 200, (status, body)
            page_url = urllib.parse.urlsplit(json.loads(body)['items'][0]['@id'])
            status, body, _ = request(base, page_url.path + '?' + page_url.query, credentials)
            assert status == 200, (status, body)
            link = urllib.parse.urlsplit(json.loads(body)['items'][0]['packageContent'])
            assert 'private' not in link.query
            status, body, _ = request(base, link.path + '?' + link.query, credentials)
            assert status == 200 and body == servers[0].packages['1.0.0'], (status, body)
            assert all(server.root_probes == 1 for server in servers)
            print('kkRepo exact query ordering, hidden credentials, direct/group links and durable root fallback despite root outage: PASS')
        print(('Nexus' if nexus else 'kkRepo') + ' paginated group merge, member precedence, numeric ordering, signed/plain links and dependency metadata: PASS')
    finally:
        for name in reversed(repositories):
            if not nexus:
                request(base, '/internal/browse/' + name + '?path=v3-flatcontainer&source=' + name, credentials, 'DELETE')
            status, body, _ = request(base, catalog + '/' + name, credentials, 'DELETE')
            assert status == 204, (name, status, body)
        if added:
            status, body, _ = request(base, ssrf, credentials)
            assert status == 200
            config = json.loads(body)
            config[allow_field].remove(host)
            assert request(base, ssrf, credentials, 'PUT', config)[0] == 200
        for server in servers:
            server.shutdown()
            server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nexus')
    parser.add_argument('--kkrepo')
    parser.add_argument('--kkrepo-secondary', help='Optional second replica using the same database and blob store')
    parser.add_argument('--upstream-host', default='host.docker.internal')
    args = parser.parse_args()
    assert args.nexus or args.kkrepo, 'Specify --nexus and/or --kkrepo'
    if args.nexus:
        exercise(args.nexus, os.environ.get('NEXUS_COMPAT_AUTH', 'admin:Admin1234'), True, args.upstream_host)
    if args.kkrepo:
        exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:123456'), False, args.upstream_host)
        exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:123456'), False, args.upstream_host, True, args.kkrepo_secondary)


if __name__ == '__main__':
    main()
