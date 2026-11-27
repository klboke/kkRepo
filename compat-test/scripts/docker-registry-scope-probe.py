#!/usr/bin/env python3
"""Check whether a credential may start a blob upload, following the real registry challenge.

The credential is sent only to the token realm advertised by the registry, after checking that
the realm has the registry's origin. The started upload is cancelled.
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


def call(url, method='GET', headers=None):
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--registry', required=True, help='e.g. http://127.0.0.1:18180')
    parser.add_argument('--image', required=True)
    parser.add_argument('--header', required=True, help='credential header, "Name: value"')
    parser.add_argument('--expect', choices=('allowed', 'denied'), required=True)
    args = parser.parse_args()

    registry = args.registry.rstrip('/')
    upload = f'{registry}/v2/{args.image}/blobs/uploads/'
    status, body, headers = call(upload, 'POST')
    challenge = headers.get('WWW-Authenticate', '')
    fields = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
    assert status == 401 and challenge.lower().startswith('bearer ') and 'realm' in fields, \
        (status, challenge, body[:300])
    realm = urllib.parse.urlsplit(fields['realm'])
    origin = urllib.parse.urlsplit(registry)
    assert (realm.scheme, realm.netloc) == (origin.scheme, origin.netloc), fields['realm']

    query = urllib.parse.parse_qsl(realm.query)
    query += [(key, fields[key]) for key in ('service', 'scope') if key in fields]
    name, value = args.header.split(':', 1)
    token_url = urllib.parse.urlunsplit(
        (realm.scheme, realm.netloc, realm.path, urllib.parse.urlencode(query), ''))
    status, body, _ = call(token_url, headers={name.strip(): value.strip()})
    assert status == 200, ('token', status, body[:300])
    payload = json.loads(body)
    bearer = {'Authorization': 'Bearer ' + (payload.get('token') or payload['access_token'])}

    status, body, headers = call(upload, 'POST', bearer)
    if status == 202 and headers.get('Location'):
        location = urllib.parse.urljoin(upload, headers['Location'])
        call(location, 'DELETE', bearer)
    allowed = status == 202
    print(f'blob upload with {name.strip()} credential: HTTP {status}')
    if allowed != (args.expect == 'allowed'):
        print(f'expected {args.expect}: {body[:300]!r}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
