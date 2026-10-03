#!/usr/bin/env python3
"""LFS authorization, immutable publication and replica handoff checks against live servers."""
import base64
import concurrent.futures
import hashlib
import http.client
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

PRIMARY = os.environ.get('KKREPO_COMPAT_BASE_URL', 'http://127.0.0.1:18090').rstrip('/')
SECONDARY = os.environ.get('KKREPO_SECONDARY_BASE_URL', PRIMARY).rstrip('/')
AUTH = 'Basic ' + base64.b64encode((os.environ.get('KKREPO_COMPAT_USERNAME', 'admin') + ':'
    + os.environ.get('KKREPO_COMPAT_PASSWORD', '12345678')).encode()).decode()
LFS = 'application/vnd.git-lfs+json'
STAMP = uuid.uuid4().hex[:12]
REPO = 'gitlfs-resilience-' + STAMP
ROOT = '/repository/' + REPO + '/'
CHECKS = []


def send(method, path, payload=None, base=PRIMARY, auth=AUTH, headers=None):
    body = json.dumps(payload).encode() if isinstance(payload, (dict, list)) else payload
    fields = {'Content-Type': LFS if path.startswith('/repository/') else 'application/json'}
    if auth is not None:
        fields['Authorization'] = auth
    fields.update(headers or {})
    request = urllib.request.Request(base + path, body, fields, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    value = json.loads(raw) if raw and ('json' in response.headers.get('Content-Type', '')) else raw
    return response.status, value, response.headers


def require(status, result):
    assert result[0] == status, (status, result[0], result[1])
    return result[1]


def batch(oid, size, operation='upload', **kwargs):
    return require(200, send('POST', ROOT + 'info/lfs/objects/batch',
        {'operation': operation, 'objects': [{'oid': oid, 'size': size}]}, **kwargs))['objects'][0]


def put(action, data, base=SECONDARY, auth=AUTH):
    parsed = urllib.parse.urlparse(action['href'])
    assert parsed.netloc == urllib.parse.urlparse(PRIMARY).netloc
    return send('PUT', parsed.path, data, base=base, auth=auth, headers=action.get('header', {}))


def create_repository(name):
    return require(201, send('POST', '/internal/repositories', {'name': name, 'recipe': 'gitlfs-hosted',
        'online': True, 'blobStoreName': 'default', 'strictContentTypeValidation': False,
        'hosted': {'writePolicy': 'ALLOW_ONCE'}}))


create_repository(REPO)
data = b'cross replica immutable LFS content\n' * 100
sha = hashlib.sha256(data).hexdigest()
first = batch(sha, len(data))
stale = batch(sha, len(data))
require(200, put(first['actions']['upload'], data))
assert require(200, send('GET', ROOT + sha)) == data
require(200, send('POST', ROOT + sha + '/verify', {'oid': sha, 'size': len(data)}, base=SECONDARY))
assert 'actions' not in batch(sha, len(data))
CHECKS.append('Batch on replica A, PUT/verify on replica B, immediate GET on A')

wrong = 'f' * 64
bad = batch(wrong, len(data))
require(422, put(bad['actions']['upload'], data))
assert batch(wrong, len(data), 'download')['error']['code'] == 404
require(422, send('PUT', ROOT + sha, data))
CHECKS.append('Wrong OID and missing upload context rejected without publication')

# Stream a short chunked body: Content-Length checks cannot catch this mismatch.
short_data = b'short'
short_oid = hashlib.sha256(short_data + b'!').hexdigest()
short_action = batch(short_oid, len(short_data) + 1)['actions']['upload']
url = urllib.parse.urlparse(SECONDARY)
connection_type = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
connection = connection_type(url.hostname, url.port, timeout=30)
connection.request('PUT', ROOT + short_oid, body=iter([short_data]),
    headers={'Authorization': AUTH, 'Content-Type': 'application/octet-stream', **short_action.get('header', {})},
    encode_chunked=True)
response = connection.getresponse()
assert response.status == 422, (response.status, response.read())
response.read()
connection.close()
assert batch(short_oid, len(short_data) + 1, 'download')['error']['code'] == 404
CHECKS.append('Short chunked transfer never becomes downloadable')

for header, expected in [('bytes=1-4', data[1:5]), ('bytes=-4', data[-4:])]:
    assert require(206, send('GET', ROOT + sha, headers={'Range': header})) == expected
require(416, send('GET', ROOT + sha, headers={'Range': 'bytes=999999-'}))
head = send('HEAD', ROOT + sha)
require(200, head)
require(304, send('GET', ROOT + sha, headers={'If-None-Match': head[2]['ETag']}))
require(501, send('POST', ROOT + 'info/lfs/locks/verify', {}))
CHECKS.append('Range, suffix Range, 416, HEAD, 304 and unsupported locks')

other = 'gitlfs-other-' + STAMP
create_repository(other)
require(403, send('PUT', '/repository/' + other + '/' + sha, data,
    headers=stale['actions']['upload'].get('header', {})))
CHECKS.append('Upload context is bound to repository and authenticated owner')

# A token is evaluated on every action, including after its Batch response was issued.
created = require(200, send('POST', '/internal/security/api-keys/current',
    {'domain': 'GenericToken', 'displayName': 'LFS test ' + STAMP}))
token_auth = 'Bearer ' + created['token']
token_oid = hashlib.sha256(b'token').hexdigest()
token_action = batch(token_oid, 5, auth=token_auth)['actions']['upload']
require(200, put(token_action, b'token', auth=token_auth))
pending_oid = hashlib.sha256(b'revoked').hexdigest()
pending = batch(pending_oid, 7, auth=token_auth)['actions']['upload']
require(200, send('DELETE', '/internal/security/api-keys/current/' + str(created['apiKey']['id'])))
require(401, put(pending, b'revoked', auth=token_auth))
CHECKS.append('GenericToken upload works and revoked token cannot use an issued action')

# A content selector must be evaluated against each OID, including within a mixed Batch.
selector_oid = hashlib.sha256(b'selector').hexdigest()
selector = 'lfs-selector-' + STAMP
require(204, send('POST', '/service/rest/v1/security/content-selectors', {'name': selector, 'type': 'csel',
    'description': 'LFS E2E', 'expression': 'format == "gitlfs" and path == "/' + selector_oid + '"'}))
require(201, send('POST', '/service/rest/v1/security/privileges/repository-content-selector',
    {'name': selector, 'description': 'LFS E2E', 'format': 'gitlfs', 'repository': REPO,
     'actions': ['read', 'add'], 'contentSelector': selector}))
require(200, send('POST', '/service/rest/v1/security/roles',
    {'id': selector, 'name': selector, 'description': 'LFS E2E', 'privileges': [selector], 'roles': []}))
require(200, send('POST', '/service/rest/v1/security/users', {'userId': selector, 'firstName': 'LFS',
    'lastName': 'E2E', 'emailAddress': 'lfs@example.invalid', 'password': 'lfs-test-password',
    'status': 'active', 'roles': [selector]}))
restricted = 'Basic ' + base64.b64encode((selector + ':lfs-test-password').encode()).decode()
objects = require(200, send('POST', ROOT + 'info/lfs/objects/batch', {'operation': 'upload',
    'objects': [{'oid': selector_oid, 'size': 8}, {'oid': wrong, 'size': 8}]}, auth=restricted))['objects']
assert 'upload' in objects[0]['actions'] and objects[1]['error']['code'] == 403
require(200, put(objects[0]['actions']['upload'], b'selector', auth=restricted))
# Authenticated users also receive the configured default read role; test selector-scoped ADD.
require(403, send('PUT', ROOT + sha, data, auth=restricted,
    headers=stale['actions']['upload'].get('header', {})))
CHECKS.append('Content selector permits one OID and rejects its mixed-Batch neighbor')
require(204, send('PUT', '/service/rest/v1/security/roles/' + selector,
    {'id': selector, 'name': selector, 'description': 'LFS E2E revoked', 'privileges': [], 'roles': []}))
require(403, put(objects[0]['actions']['upload'], b'selector', auth=restricted))
CHECKS.append('Role privilege revocation on A prevents action replay on B')


# Generic asset deletion must atomically fence any older upload contexts.
listed = require(200, send('GET', '/service/rest/v1/search/assets?repository=' + REPO))
asset_id = next(item['id'] for item in listed['items'] if item['path'].lstrip('/') == sha)
require(204, send('DELETE', '/service/rest/v1/assets/' + str(asset_id)))
require(409, put(stale['actions']['upload'], data))
assert batch(sha, len(data), 'download')['error']['code'] == 404
fresh = batch(sha, len(data))
require(200, put(fresh['actions']['upload'], data))
CHECKS.append('Deletion fences preexisting uploads; only a new Batch can recreate content')

concurrent_data = b'concurrent-' * 100000
concurrent_oid = hashlib.sha256(concurrent_data).hexdigest()
actions = [batch(concurrent_oid, len(concurrent_data))['actions']['upload'] for _ in range(2)]
with concurrent.futures.ThreadPoolExecutor(2) as pool:
    results = list(pool.map(lambda action: put(action, concurrent_data), actions))
assert any(result[0] == 200 for result in results)
assert all(result[0] in (200, 409) for result in results)
assert require(200, send('GET', ROOT + concurrent_oid)) == concurrent_data
CHECKS.append('Competing uploads publish one immutable OID with intact bytes')

# A warm anonymous catalog must not outlive an administrator disabling public reads.
anonymous = require(200, send('GET', '/internal/security/anonymous'))
try:
    require(200, send('PUT', '/internal/security/anonymous', {**anonymous, 'enabled': True}))
    require(200, send('GET', ROOT + sha, base=SECONDARY, auth=None))
    require(200, send('PUT', '/internal/security/anonymous', {**anonymous, 'enabled': False}))
    require(401, send('GET', ROOT + sha, base=SECONDARY, auth=None))
    require(401, send('POST', ROOT + 'info/lfs/objects/batch',
        {'operation': 'download', 'objects': [{'oid': sha, 'size': len(data)}]}, base=SECONDARY, auth=None))
finally:
    require(200, send('PUT', '/internal/security/anonymous', anonymous))
CHECKS.append('Disabling anonymous access on A immediately blocks public reads on B')

# Repository changes must invalidate old actions on a pod with a warm catalog.
require(200, send('GET', ROOT + sha, base=SECONDARY))
require(200, send('PUT', '/internal/repositories/' + REPO, {'online': False}))
require(503, send('GET', ROOT + sha, base=SECONDARY))
require(200, send('PUT', '/internal/repositories/' + REPO,
    {'online': True, 'hosted': {'writePolicy': 'DENY'}}))
readonly = batch(hashlib.sha256(b'read only').hexdigest(), 9, base=SECONDARY)
assert readonly['error']['code'] == 403
CHECKS.append('Offline and read-only changes are enforced across warm replica catalogs')

output = {'primary': PRIMARY, 'secondary': SECONDARY, 'checks': CHECKS}
artifact = Path(os.environ.get('CLIENT_E2E_ARTIFACT_DIR', 'artifacts/client-e2e'))
artifact.mkdir(parents=True, exist_ok=True)
(artifact / 'git-lfs-resilience.json').write_text(json.dumps(output, indent=2) + '\n')
print(json.dumps(output, indent=2))
