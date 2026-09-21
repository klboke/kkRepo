#!/usr/bin/env python3
"""NTLMv2 NuGet upstream contract against disposable Nexus and/or kkRepo instances.

Example: python3 compat-test/scripts/ntlm-upstream.py --nexus http://localhost:28090
The reference HTTP authentication type is ntlm, with username/password/ntlmHost/ntlmDomain.
The fixture validates the NTLMv2 proof and requires Type 1/3 on the same TCP connection.
Only synthetic credentials are used. Repository and temporary SSRF exceptions are removed.
"""
import argparse
import base64
import hashlib
import hmac
import http.server
import io
import ipaddress
import json
import os
import struct
import subprocess
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape
import threading
import urllib.error
import urllib.request
import uuid
import zipfile

USER, PASSWORD, DOMAIN = 'User', 'Password', 'Domain'
# MS-NLMP example NT hash for the synthetic password "Password".
NT_HASH = bytes.fromhex('a4f49c406510bdcab6824ee7c30fd852')
CHALLENGE = bytes.fromhex('0123456789abcdef')


def secbuf(data, offset):
    length, _, start = struct.unpack_from('<HHI', data, offset)
    return data[start:start + length]


def type2():
    domain = DOMAIN.encode('utf-16le')
    info = struct.pack('<HH', 2, len(domain)) + domain + bytes(4)
    # Unicode, request target, NTLM, extended session security, target info.
    return (b'NTLMSSP\0' + struct.pack('<IHHII', 2, len(domain), len(domain), 48, 0x00880205)
            + CHALLENGE + bytes(8) + struct.pack('<HHI', len(info), len(info), 48 + len(domain))
            + domain + info)


class Upstream(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def send(self, status, body=b'', headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        authorization = self.headers.get('Authorization', '')
        if authorization.startswith('NTLM '):
            token = base64.b64decode(authorization[5:])
            kind = struct.unpack_from('<I', token, 8)[0]
            if kind == 1:
                self.negotiated = True
                self.send(401, headers={'WWW-Authenticate': 'NTLM ' + base64.b64encode(type2()).decode()})
                return
            if kind == 3 and getattr(self, 'negotiated', False):
                domain = secbuf(token, 28).decode('utf-16le')
                user = secbuf(token, 36).decode('utf-16le')
                response = secbuf(token, 20)
                key = hmac.new(NT_HASH, (user.upper() + domain).encode('utf-16le'), 'md5').digest()
                proof = hmac.new(key, CHALLENGE + response[16:], 'md5').digest()
                # Nexus 3.94 REST stores ntlmDomain but may negotiate the default (empty) domain.
                # Both cases still require a valid NTLMv2 proof for our synthetic account.
                if user == USER and domain.upper() in ('', DOMAIN.upper()) and hmac.compare_digest(response[:16], proof):
                    self.authenticated = True
                    self.server.authenticated += 1
                else:
                    self.send(401, headers={'WWW-Authenticate': 'NTLM'})
                    return
        if not getattr(self, 'authenticated', False):
            self.send(401, headers={'WWW-Authenticate': 'NTLM'})
            return
        if self.path.endswith('/index.json') and '/flat/' not in self.path and '/v3-flatcontainer/' not in self.path:
            body = json.dumps({'version': '3.0.0', 'resources': [
                {'@id': self.server.remote + 'flat/', '@type': 'PackageBaseAddress/3.0.0'}]}).encode()
            self.send(200, body, {'Content-Type': 'application/json'})
        elif self.path.endswith('.nupkg'):
            self.send(200, self.server.package, {'Content-Type': 'application/octet-stream'})
        else:
            self.send(200, b'{"versions":["1.0.0"]}', {'Content-Type': 'application/json'})


def request(base, path, credentials, method='GET', data=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(base + path, body, method=method, headers={
        'Authorization': 'Basic ' + base64.b64encode(credentials.encode()).decode(),
        'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def exercise(base, credentials, nexus, fixture, dotnet=False):
    name = 'compat-ntlm-' + uuid.uuid4().hex[:10]
    catalog = '/service/rest/v1/repositories' if nexus else '/internal/repositories'
    payload = {'name': name, 'online': True}
    if nexus:
        payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False},
                       proxy={'remoteUrl': fixture.remote + 'index.json', 'contentMaxAge': 0, 'metadataMaxAge': 0},
                       negativeCache={'enabled': False, 'timeToLive': 1},
                       nugetProxy={'queryCacheItemMaxAge': 0, 'nugetVersion': 'V3'},
                       httpClient={'blocked': False, 'autoBlock': False, 'authentication': {
                           'type': 'ntlm', 'username': USER, 'password': PASSWORD,
                           'ntlmDomain': DOMAIN, 'ntlmHost': 'KKREPO'}})
    else:
        payload.update(recipe='nuget-proxy', blobStoreName='default', strictContentTypeValidation=False,
                       proxy={'remoteUrl': fixture.remote + 'index.json', 'contentMaxAgeMinutes': 0,
                              'metadataMaxAgeMinutes': 0, 'autoBlock': False, 'remoteUsername': USER,
                              'remotePassword': PASSWORD, 'remoteAuthenticationType': 'ntlm',
                              'remoteNtlmDomain': DOMAIN, 'remoteNtlmHost': 'KKREPO'})
    path = catalog + ('/nuget/proxy' if nexus else '')
    status, body, _ = request(base, path, credentials, 'POST', payload)
    assert status == 201, (status, body)
    before = fixture.authenticated
    try:
        status, index, _ = request(base, '/repository/' + name + '/index.json', credentials)
        assert status == 200, (status, index)
        flat = next(r['@id'] for r in json.loads(index)['resources'] if r['@type'] == 'PackageBaseAddress/3.0.0')
        path = urllib.parse.urlsplit(flat).path + 'ntlm.fixture/1.0.0/ntlm.fixture.1.0.0.nupkg'
        status, body, _ = request(base, path, credentials)
        assert status == 200, (status, body)
        assert body == fixture.package
        status, _, headers = request(base, path, credentials, 'HEAD')
        assert status == 200 and int(headers['Content-Length']) == len(fixture.package)
        assert fixture.authenticated > before, 'upstream NTLMv2 proof was not verified'
        if dotnet:
            with tempfile.TemporaryDirectory(prefix='kkrepo-ntlm-dotnet-') as directory:
                work = Path(directory)
                (work / 'test.csproj').write_text('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup>'
                    '<TargetFramework>net8.0</TargetFramework><NuGetAudit>false</NuGetAudit></PropertyGroup>'
                    '<ItemGroup><PackageReference Include="ntlm.fixture" Version="1.0.0" /></ItemGroup></Project>')
                (work / 'NuGet.Config').write_text('<configuration><packageSources><clear />'
                    '<add key="ntlmfixture" value="' + escape(base + '/repository/' + name + '/index.json')
                    + '" allowInsecureConnections="true" /></packageSources></configuration>')
                username, password = credentials.split(':', 1)
                env = dict(os.environ, DOTNET_CLI_HOME=directory, NUGET_PACKAGES=str(work / 'packages'),
                           DOTNET_SKIP_FIRST_TIME_EXPERIENCE='1', DOTNET_CLI_TELEMETRY_OPTOUT='1',
                           NuGetPackageSourceCredentials_ntlmfixture='Username=' + username + ';Password='
                           + password + ';ValidAuthenticationTypes=Basic')
                subprocess.run(['dotnet', 'restore', str(work / 'test.csproj'), '--configfile',
                                str(work / 'NuGet.Config'), '--no-cache', '--disable-parallel'],
                               env=env, cwd=directory, check=True, timeout=120,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                assert (work / 'packages/ntlm.fixture/1.0.0/ntlm.fixture.1.0.0.nupkg').read_bytes() == fixture.package
                print(('Nexus' if nexus else 'kkRepo'), 'dotnet restore: PASS')
        print(('Nexus' if nexus else 'kkRepo'), 'GET/HEAD, package SHA-256, NTLMv2 proof: PASS',
              hashlib.sha256(body).hexdigest())
    finally:
        if not nexus:
            request(base, '/internal/browse/' + name + '?path=v3-flatcontainer&source=' + name,
                    credentials, 'DELETE')
        status, body, _ = request(base, catalog + '/' + name, credentials, 'DELETE')
        assert status == 204, ('cleanup', status, body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nexus')
    parser.add_argument('--kkrepo')
    parser.add_argument('--dotnet', action='store_true', help='Also run a .NET 8 restore with an isolated package cache')
    parser.add_argument('--upstream-host', default='host.docker.internal')
    args = parser.parse_args()
    assert args.nexus or args.kkrepo, 'Specify --nexus and/or --kkrepo'
    package = io.BytesIO()
    with zipfile.ZipFile(package, 'w') as archive:
        archive.writestr('ntlm.fixture.nuspec', '<package><metadata><id>ntlm.fixture</id><version>1.0.0</version>'
                         '<authors>compat</authors><description>NTLM fixture</description></metadata></package>')
    fixture = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Upstream)
    fixture.remote = 'http://' + args.upstream_host + ':' + str(fixture.server_port) + '/'
    fixture.package, fixture.authenticated = package.getvalue(), 0
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    nexus_auth = os.environ.get('NEXUS_COMPAT_AUTH', 'admin:Admin1234')
    ssrf_path = '/service/rest/v1/security/ssrf-protection'
    added = False
    try:
        ipaddress.ip_address(args.upstream_host)
        allow_field = 'allowedIPs'
    except ValueError:
        allow_field = 'allowedDomains'
    try:
        if args.nexus:
            status, body, _ = request(args.nexus, ssrf_path, nexus_auth)
            assert status == 200, (status, body)
            config = json.loads(body)
            hosts = config.setdefault(allow_field, [])
            if args.upstream_host not in hosts:
                hosts.append(args.upstream_host)
                status, body, _ = request(args.nexus, ssrf_path, nexus_auth, 'PUT', config)
                assert status == 200, (status, body)
                added = True
            exercise(args.nexus, nexus_auth, True, fixture, args.dotnet)
        if args.kkrepo:
            exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:123456'), False, fixture, args.dotnet)
    finally:
        if added:
            status, body, _ = request(args.nexus, ssrf_path, nexus_auth)
            assert status == 200
            config = json.loads(body)
            config[allow_field].remove(args.upstream_host)
            assert request(args.nexus, ssrf_path, nexus_auth, 'PUT', config)[0] == 200
        fixture.shutdown()
        fixture.server_close()


if __name__ == '__main__':
    main()
