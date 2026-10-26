#!/usr/bin/env python3
"""Routing-rule fixtures for the config-migration E2E suite (disposable target only)."""
import argparse
import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['prepare', 'verify', 'cleanup'])
parser.add_argument('--nexus', required=True)
parser.add_argument('--kkrepo')
parser.add_argument('--prefix', default='routing-migration')
args = parser.parse_args()
rule = args.prefix + '-block'
repo = args.prefix + '-proxy'
policy = {'name': rule, 'description': 'Routing policy migration fixture',
          'mode': 'BLOCK', 'matchers': ['/private/.*', '/internal/.*']}
artifact = '/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.pom'


def call(base, path, method='GET', body=None, nexus=False, expected=200):
    auth = os.environ.get('NEXUS_COMPAT_AUTH' if nexus else 'KKREPO_COMPAT_AUTH',
                          'admin:Admin1234' if nexus else 'admin:12345678')
    headers = {'Authorization': 'Basic ' + base64.b64encode(auth.encode()).decode(),
               'Content-Type': 'application/json'}
    req = urllib.request.Request(base.rstrip('/') + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    allowed = expected if isinstance(expected, tuple) else (expected,)
    assert status in allowed, f'{method} {path}: HTTP {status}, expected {allowed}'
    return data


if args.mode == 'prepare':
    call(args.nexus, '/service/rest/v1/routing-rules', 'POST', policy, True, 204)
    call(args.nexus, '/service/rest/v1/repositories/raw/proxy', 'POST', {
        'name': repo, 'online': True,
        'storage': {'blobStoreName': 'default', 'strictContentTypeValidation': False},
        'proxy': {'remoteUrl': 'https://repo.maven.apache.org/maven2/', 'contentMaxAge': 1440,
                  'metadataMaxAge': 1440},
        'negativeCache': {'enabled': True, 'timeToLive': 1440},
        'httpClient': {'blocked': False, 'autoBlock': False},
        'routingRuleName': rule, 'routingRule': rule,
    }, True, 201)
    call(args.nexus, '/repository/' + repo + '/private/denied.jar', nexus=True, expected=403)
    call(args.nexus, '/repository/' + repo + artifact, nexus=True)
    print('Nexus routing migration fixture ready: allowed artifact and blocked path verified')
elif args.mode == 'verify':
    assert args.kkrepo, '--kkrepo is required'
    saved = json.loads(call(args.kkrepo, '/service/rest/v1/routing-rules/' + rule))
    assert saved == policy, 'Migrated rule content differs'
    rules = json.loads(call(args.kkrepo, '/service/rest/v1/routing-rules'))
    assert sum(r['name'] == rule for r in rules) == 1, 'Duplicate migrated rule'
    target = json.loads(call(args.kkrepo, '/internal/repositories/' + repo))
    assert target['routingRuleName'] == rule, 'Migrated proxy lost its routing rule'
    # Each target replica may retain its pre-migration snapshot until its configured TTL.
    deadline = time.monotonic() + float(os.environ.get('ROUTING_RULES_CONVERGENCE_TIMEOUT_SECONDS', '45'))
    while True:
        try:
            call(args.kkrepo, '/repository/' + repo + '/private/denied.jar', expected=403)
            break
        except AssertionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.2)
    call(args.kkrepo, '/repository/' + repo + '/%70rivate/denied.jar', expected=403)
    source = call(args.nexus, '/repository/' + repo + artifact, nexus=True)
    migrated = call(args.kkrepo, '/repository/' + repo + artifact)
    assert hashlib.sha256(source).digest() == hashlib.sha256(migrated).digest(), 'Allowed content differs'
    print('Migrated rule, unique identity, binding, blocked paths and allowed artifact SHA-256 verified')
else:
    call(args.nexus, '/service/rest/v1/repositories/' + repo, 'DELETE', nexus=True, expected=(204, 404))
    call(args.nexus, '/service/rest/v1/routing-rules/' + rule, 'DELETE', nexus=True, expected=(204, 404))
    if args.kkrepo:
        call(args.kkrepo, '/internal/browse/' + repo + '?path=org&source=' + repo,
             'DELETE', expected=(200, 404))
        call(args.kkrepo, '/internal/repositories/' + repo, 'DELETE', expected=(204, 404))
        call(args.kkrepo, '/service/rest/v1/routing-rules/' + rule, 'DELETE', expected=(204, 404))
    print('Routing migration fixtures removed')
