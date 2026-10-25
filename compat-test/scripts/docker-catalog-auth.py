#!/usr/bin/env python3
"""Compare Docker catalog read authorization with Nexus using disposable fixtures.

Restores anonymous settings and removes only the users, roles and repositories it creates.
Run on an isolated compatibility instance: anonymous configuration is temporarily changed.
"""
import argparse
import base64
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def basic(credentials):
    return 'Basic ' + base64.b64encode(credentials.encode()).decode()


def request(base, path, authorization=None, method='GET', data=None, extra_headers=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    headers.update(extra_headers or {})
    if authorization:
        headers['Authorization'] = authorization
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=None if data is None else json.dumps(data).encode())
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            body = error.read()
            if error.code == 429 and attempt < 2:
                # Management authentication is rate limited on default local instances.
                time.sleep(min(60, max(1, int(error.headers.get('Retry-After', '1')))))
                continue
            return error.code, body, error.headers


def expect(result, status):
    assert result[0] == status, (result[0], result[1].decode(errors='replace'))
    return result


def token_for(base, path, authorization, nexus):
    if nexus:
        challenge = request(base, path)[2].get('WWW-Authenticate', '')
        fields = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
        assert challenge.lower().startswith('bearer ') and 'realm' in fields, challenge
        realm = urllib.parse.urlsplit(fields['realm'])
        # Credentials must never be sent to a different origin by a fixture challenge.
        assert (realm.scheme, realm.netloc) == (urllib.parse.urlsplit(base).scheme,
                                               urllib.parse.urlsplit(base).netloc), fields
        query = urllib.parse.parse_qsl(realm.query)
        query += [(key, fields[key]) for key in ('service', 'scope') if key in fields]
        # The Nexus admin-port repository route needs the same prefix on /v2/token.
        realm_path = path.split('/v2/')[0] + realm.path if realm.path == '/v2/token' else realm.path
        token_path = realm_path + '?' + urllib.parse.urlencode(query)
    else:
        token_path = '/service/rest/v1/docker/token?scope=registry%3Acatalog%3A*'
    payload = json.loads(expect(request(base, token_path, authorization), 200)[1])
    return 'Bearer ' + (payload.get('token') or payload['access_token'])


def catalog(base, path, authorization=None, nexus=False):
    response = request(base, path, authorization)
    if nexus and response[0] == 401:
        response = request(base, path, token_for(base, path, authorization, True))
    expect(response, 200)
    assert json.loads(response[1]) == {'repositories': []}, response[1]
    assert response[2].get('Docker-Distribution-API-Version') == 'registry/2.0'


def forwarded_challenges(base, path):
    # Compare origins, not implementation-specific token paths or service identifiers.
    # The candidate must trust the immediate peer running this isolated fixture.
    for original_host, host, port, expected_port in (
            ('registry.example.invalid', 'registry.example.invalid', None, 443),
            ('registry.example.invalid', 'registry.example.invalid', '443', 443),
            ('registry.example.invalid', 'registry.example.invalid:8443', None, 8443),
            ('registry.example.invalid', None, None, 443),
            ('registry.example.invalid:8443', None, None, 8443)):
        headers = {'Host': original_host, 'X-Forwarded-Proto': 'https'}
        if host:
            headers['X-Forwarded-Host'] = host
        if port:
            headers['X-Forwarded-Port'] = port
        result = expect(request(base, path, extra_headers=headers), 401)
        challenge = result[2].get('WWW-Authenticate', '')
        fields = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
        assert challenge.lower().startswith('bearer '), challenge
        realm = urllib.parse.urlsplit(fields['realm'])
        assert realm.scheme == 'https' and realm.hostname == 'registry.example.invalid', challenge
        assert (realm.port or 443) == expected_port, challenge
        assert fields.get('service'), challenge
        assert result[2].get('Docker-Distribution-API-Version') == 'registry/2.0'
    print('Docker forwarded HTTPS challenge: default and explicit ports PASS')


def exercise(base, credentials, nexus, forwarded_headers=False):
    admin = basic(credentials)
    suffix = uuid.uuid4().hex[:10]
    allowed, denied = 'catalog-read-' + suffix, 'catalog-denied-' + suffix
    role, user = 'catalog-role-' + suffix, 'catalog-user-' + suffix
    password = 'CatalogCompat123!'
    user_auth = basic(user + ':' + password)
    repo_api = '/service/rest/v1/repositories' if nexus else '/internal/repositories'
    anonymous_api = '/service/rest/v1/security/anonymous'
    roles_api, users_api = '/service/rest/v1/security/roles', '/service/rest/v1/security/users'
    original_anonymous = json.loads(expect(request(base, anonymous_api, admin), 200)[1])
    realms_api = '/service/rest/v1/security/realms/active'
    original_realms = json.loads(expect(request(base, realms_api, admin), 200)[1]) if nexus else None
    realms_changed = False
    repositories = []
    role_created = user_created = anonymous_changed = False
    path = ('/repository/' + allowed + '/v2/' if nexus else '/v2/' + allowed + '/') + '_catalog'
    denied_path = ('/repository/' + denied + '/v2/' if nexus else '/v2/' + denied + '/') + '_catalog'
    role_data = {'id': role, 'name': role, 'description': 'Docker catalog read compatibility',
                 'privileges': ['nx-repository-view-docker-' + allowed + '-read'], 'roles': []}
    try:
        if nexus and 'DockerToken' not in original_realms:
            expect(request(base, realms_api, admin, 'PUT', original_realms + ['DockerToken']), 204)
            realms_changed = True
        # Disable anonymous before obtaining the real registry challenge.
        expect(request(base, anonymous_api, admin, 'PUT', dict(original_anonymous, enabled=False)), 200)
        anonymous_changed = True
        for name in (allowed, denied):
            payload = {'name': name, 'online': True}
            if nexus:
                payload.update(storage={'blobStoreName': 'default', 'strictContentTypeValidation': False,
                                        'writePolicy': 'ALLOW'},
                               docker={'v1Enabled': False, 'forceBasicAuth': False})
            else:
                payload.update(recipe='docker-hosted', blobStoreName='default',
                               hosted={'writePolicy': 'ALLOW'})
            expect(request(base, repo_api + ('/docker/hosted' if nexus else ''), admin, 'POST', payload), 201)
            repositories.append(name)
        if forwarded_headers:
            forwarded_challenges(base, path)
        expect(request(base, roles_api, admin, 'POST', role_data), 200)
        role_created = True
        expect(request(base, users_api, admin, 'POST', {
            'userId': user, 'firstName': 'Catalog', 'lastName': 'Compatibility',
            'emailAddress': user + '@example.invalid', 'password': password,
            'status': 'active', 'roles': [role]}), 200)
        user_created = True
        catalog(base, path, admin, nexus)
        catalog(base, path, user_auth, nexus)
        token = token_for(base, path, user_auth, nexus)
        catalog(base, path, token, nexus)
        inherited_read = False
        if not nexus:
            permissions = json.loads(expect(request(base, '/internal/security/permissions', user_auth), 200)[1])
            inherited_read = 'nexus:repository-view:*:*:read' in permissions
        denied_basic = request(base, denied_path, user_auth)
        if inherited_read:
            # kkRepo can intentionally append nx-anonymous to every authenticated user.
            expect(denied_basic, 200)
        else:
            assert denied_basic[0] in (401, 403), (denied_basic[0], denied_basic[1])
        if not nexus:
            # A registry scope must not bypass repository permissions, even on an existing token.
            expect(request(base, denied_path, token), 200 if inherited_read else 401)
            expect(request(base, roles_api + '/' + role, admin, 'PUT', dict(role_data, privileges=[])), 204)
            expect(request(base, path, token), 200 if inherited_read else 401)
            expect(request(base, roles_api + '/' + role, admin, 'PUT', role_data), 204)
            denied_result = expect(request(base, path), 401)
            assert denied_result[2].get('Content-Type', '').startswith('application/json')
            assert json.loads(denied_result[1])['errors'][0]['code'] == 'UNAUTHORIZED'
            assert 'registry:catalog:*' in denied_result[2].get('WWW-Authenticate', '')
        expect(request(base, roles_api + '/' + role, admin, 'PUT',
                       dict(role_data, privileges=['nx-repository-view-docker-*-read'])), 204)
        catalog(base, path, user_auth, nexus)
        expect(request(base, roles_api + '/' + role, admin, 'PUT', role_data), 204)
        expect(request(base, anonymous_api, admin, 'PUT',
                       dict(original_anonymous, enabled=True, userId=user, realmName='NexusAuthorizingRealm')), 200)
        catalog(base, path, nexus=nexus)
        if not nexus:
            anonymous_token = token_for(base, path, None, False)
            catalog(base, path, anonymous_token)
            expect(request(base, denied_path), 401)
            expect(request(base, denied_path, anonymous_token), 401)
            expect(request(base, roles_api + '/' + role, admin, 'PUT', dict(role_data, privileges=[])), 204)
            expect(request(base, path, anonymous_token), 401)
            expect(request(base, roles_api + '/' + role, admin, 'PUT', role_data), 204)
            catalog(base, path, anonymous_token)
            expect(request(base, path, basic(user + ':wrong-password')), 401)
            expect(request(base, path, 'Bearer invalid-token'), 401)
            expect(request(base, '/service/rest/v1/docker/token?scope=registry%3Acatalog%3A*',
                           basic(user + ':wrong-password')), 401)
            expect(request(base, anonymous_api, admin, 'PUT', dict(original_anonymous, enabled=False)), 200)
            expect(request(base, path, anonymous_token), 401)
    finally:
        if realms_changed:
            expect(request(base, realms_api, admin, 'PUT', original_realms), 204)
        if anonymous_changed:
            expect(request(base, anonymous_api, admin, 'PUT', original_anonymous), 200)
        if user_created:
            expect(request(base, users_api + '/' + user, admin, 'DELETE'), 204)
        if role_created:
            expect(request(base, roles_api + '/' + role, admin, 'DELETE'), 204)
        for name in reversed(repositories):
            expect(request(base, repo_api + '/' + name, admin, 'DELETE'), 204)
    print(('Nexus' if nexus else 'kkRepo') + ' catalog: admin, repository read, wildcard read, '
          'Bearer, anonymous and repository isolation PASS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nexus')
    parser.add_argument('--kkrepo')
    parser.add_argument('--forwarded-headers', action='store_true',
                        default=os.environ.get('DOCKER_FORWARDED_COMPAT_ENABLED') == 'true',
                        help='Check HTTPS challenges; kkRepo must trust the immediate test peer')
    args = parser.parse_args()
    if not args.nexus and not args.kkrepo:
        parser.error('Specify --nexus or --kkrepo')
    if args.nexus:
        exercise(args.nexus, os.environ.get('NEXUS_COMPAT_AUTH', 'admin:Admin1234'), True, args.forwarded_headers)
    if args.kkrepo:
        exercise(args.kkrepo, os.environ.get('KKREPO_COMPAT_AUTH', 'admin:12345678'), False, args.forwarded_headers)
