# Optional OHPM signed login

Existing Access Tokens remain the default. Optional signed login lets an OHPM publisher prove its identity with an RSA private key and receive a short-lived token. The contract follows the [Huawei protocol](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-interface-protocol) and observed OHPM 6.0.1 requests.

## Configuration and public keys

Apply the same configuration to every replica and restart the affected processes:

```yaml
kkrepo:
  ohpm:
    login:
      enabled: true
      timestamp-tolerance-seconds: 300
      token-ttl-seconds: 300
```

Login is disabled by default. Timestamp tolerance accepts 1–600 seconds and token lifetime accepts 1–3600 seconds; both default to 300 seconds. Invalid values fail startup. Keep server clocks synchronized.

Use **Security → Keys & Tokens → OHPM signing keys (optional)** (`#admin/security/ohpm-login`) to bind an existing user's source and user ID to an X.509 PEM RSA public key. GenericToken remains the default for ordinary OHPM publishing and installation. Keys must contain 2048–8192 bits. Each user has one binding. The page shows the publish ID, SHA-256 fingerprint of the DER public key, and configured enabled state; preconfigured keys cannot authenticate while server signed login is disabled. Private keys remain on publishing machines.

Select **Rotate** to replace an existing key. Rotation preserves the publish ID and the current enabled state under a database lock, including changes made on another replica after opening the form. Saving an existing owner through a freshly opened form also uses rotation; the checkbox applies only to new bindings. Enable/Disable are separate actions. Rotation, disabling and deletion require confirmation showing the owner and fingerprint and revoke existing signed-login tokens. Deleting and registering again creates a new publish ID.

The management API requires fresh `nexus:*` authorization:

| Operation | Endpoint |
| --- | --- |
| Current replica configuration | `GET /internal/ohpm/login` |
| List up to 100 bindings | `GET /internal/ohpm/login/keys?offset=0` |
| Register or rotate | `PUT /internal/ohpm/login/keys`, fields `source`, `userId`, `publicKey`, `enabled` |
| Rotate without changing status | `PUT /internal/ohpm/login/keys/{publishId}/public-key`, field `publicKey` |
| Enable or disable | `PUT /internal/ohpm/login/keys/{publishId}/enabled`, field `enabled` |
| Delete | `DELETE /internal/ohpm/login/keys/{publishId}` |

Rotation preserves the publish ID. Deleting and recreating a binding assigns a new ID. Saving, enabling, disabling or deleting a key revokes that user's issued Login Token without revoking GenericTokens. Re-enabling a key does not restore old tokens.

## Publishing

Generate an encrypted private key and export its public key:

```bash
openssl genpkey -algorithm RSA -aes-256-cbc \
  -pkeyopt rsa_keygen_bits:3072 -out ohpm-private.pem
openssl pkey -in ohpm-private.pem -pubout -out ohpm-public.pem
```

Have an administrator register the public key, then configure the project's `.ohpmrc`:

```ini
registry=https://repo.example.com/repository/ohpm-hosted/
publish_registry=https://repo.example.com/repository/ohpm-hosted/
publish_id=<publish ID returned by the management page>
key_path=/absolute/path/to/ohpm-private.pem
```

Run `ohpm publish library.har` and enter the private-key passphrase at the prompt. Omit registry `_auth` when testing signed authentication so the client does not use an existing token instead.

OHPM 6.0.1 requires an encrypted private key. Its `.ohpmrc` `key_passphrase` setting also requires encryption through the client's crypto component; do not put a plaintext passphrase there. Interactive input does not require that setting. Automated signing tests use Python 3's standard PTY support to drive the actual client prompt with temporary keys and configuration.

Installation and download retain normal repository read permissions. Existing GenericTokens remain suitable for CI. Signed login does not bypass content selectors or grant administrator access.

## Protocol and replica behavior

- Online hosted repositories expose `POST /repository/<repo>/login`. Disabled login returns `404`; proxy/group repositories do not provide signed login.
- Requests contain `version: "v1"`, publishId, a millisecond timestamp, a 32-character hexadecimal nonce, and a standard Base64 signature. RSA-SHA256 signs the literal UTF-8 text `v1-<publishId>-<timestamp>-<nonce>`.
- Success returns `{"success":true,"token":"..."}`. Failures return `success:false` and `error`. Responses are not cacheable.
- `login_pss` returns `404`. OHPM 6.0.1 has been verified to fall back to classic Login. This does not establish PSS support or compatibility with other client versions.
- MySQL/PostgreSQL V62 adds `ohpm_login_key` and `ohpm_login_nonce`. Database locks and unique constraints coordinate identity and replay state across replicas. Nonces are compared in lowercase and retained until the request timestamp plus tolerance plus one second; a scheduled task removes expired rows in batches.
- Key update times, nonce expiry and cleanup cutoffs use UTC when reading and writing timezone-free columns. Both database contracts switch JVM timezones between UTC, Shanghai and Los Angeles to prevent another replica from removing replay protection prematurely.
- Tokens reuse `api_key` under the `OhpmLogin` domain and bind the publish ID and public-key fingerprint. Expiry is evaluated in UTC. JDBC preserves date-time field values without applying the JVM timezone.
- Concurrent logins with different nonces reuse a valid token instead of invalidating each other. Expired tokens are replaced. Nonce recording and token issuance commit in one transaction.
- Login Tokens authenticate only OHPM protocol requests, never management APIs or other protocols. Every OHPM request checks current token expiry, key and user status without using the generic API-key authentication cache. Normal fresh package permissions still apply.
- Disabling the feature rejects both Login and issued Login Tokens on that replica. Update all replicas together. GenericTokens remain unaffected.

## Verification

Run client contract tests and the dual-database, dual-instance runtime tests:

```bash
mvn -pl compat-test -am -Dtest=OhpmClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
mvn -pl server -am -Dtest=OhpmHostedRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

These require Docker, OHPM 6.0.1 and its Node.js runtime, and Python 3. Set `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm` when OHPM is not on PATH. Signing coverage includes fallback, publishing and installation across replicas, nonce races, expiry, key disabling/rotation, and user disabling. Independent black-box coverage, the complete CI matrix and final PR acceptance remain tracked in the [implementation plan](../../zh/dev/ohpm-implementation-plan.md).
