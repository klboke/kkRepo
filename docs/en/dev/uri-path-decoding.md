# URI path decoding

Repository request paths use URI percent-encoding, not HTML form encoding. Decode exactly once at
the boundary that owns the raw path, before interpreting protocol identifiers. A literal `+` must
remain a plus (for example Go's `+incompatible` and package versions with build metadata).

`core.http.UriPathDecoder` uses Spring Core's `StringUtils.uriDecode`, the implementation behind
`UriUtils.decode`. This keeps the shared implementation available to protocol modules without a
Spring Web dependency. It also rejects malformed UTF-8 instead of silently replacing invalid bytes.
The helper is stateless and yields the same result on every replica.

## API and ownership

- `decodePath` splits on literal `/` before decoding each segment. It preserves empty segments and
  trailing slashes; the protocol decides whether those shapes are valid.
- `decodeSegment` rejects decoded separators, backslashes, dot segments and ASCII control characters.
- `decodeComponent` decodes an opaque URI value without imposing path structure. Use it only when
  the protocol owns the separator rules, such as npm scoped identities, Hugging Face revisions,
  Terraform URL credentials and URI query values.

None of these methods recursively decodes percent signs. `%2521` becomes `%21`, not `!`. Callers
must not decode the result again. The module/package/version validators remain authoritative.
HTML form query decoding continues to use `URLDecoder`; servlet `getParameter` values are already
decoded. Whole-URI inspection with `URI.getPath()` is separate from repository path normalization.

## Audited callers

| Area | Boundary and retained semantics |
| --- | --- |
| Go | Security filter decodes before permission checks; dispatcher and all three recipes reuse the same path. Go `!a` case decoding remains in `GoModulePaths` / `GoVersions`. |
| PyPI, Composer | Existing normalization before permission checks uses the shared helper; downstream parsing uses the already decoded path. Literal percent signs in filenames survive one pass. |
| Cargo, Pub | Protocol parser decodes segments; malformed input remains an unknown protocol route. |
| Swift, Ansible, Conda, Conan, APT, Alpine, R | Existing strict protocol parsers reuse the helper and retain their rejection of decoded percent signs. Swift/Ansible query plus handling is preserved; Conan query values retain form-style plus handling. |
| Hugging Face | Repository and file segments use strict segment decoding. Revisions retain encoded slashes such as `refs%2Fpr%2F3`, with existing revision validation. |
| Terraform | Only the optional opaque URL credential is decoded, preserving encoded base64 slashes and literal plus. Existing registry path restrictions remain. |
| npm | Scoped identities retain encoded `/`; parsed package identities are not decoded a second time. Authentication endpoint matching preserves opaque username/token suffixes. |
| Docker | Segments are decoded once; manifest references, digests and upload IDs reuse the decoded values. |
| RubyGems | Compact-index path names use URI segment decoding. Dependency query values retain form decoding and are no longer decoded twice. |
| Repository names, metrics, Docker migration | Shared segment decoding replaces form decoding; metrics defer malformed requests to request validation. |

Do not blanket-decode every protocol in the dispatcher. Some parsers own raw URI components and
intentionally accept encoded separators; decoding globally would erase those boundaries or decode
values twice. Protocols without their own decoding pass continue to receive their existing input.

## Regression coverage

The core tests cover plus signs, percent nesting, malformed escapes, strict UTF-8 (including valid
U+FFFD), Unicode, encoded separators and controls. Protocol tests cover their special cases. Go
filter/dispatcher tests cover GET/HEAD across hosted, proxy and group, version endpoints and
uppercase prerelease versions. Nexus black-box probes and the client E2E suite include uppercase
Go module paths so real Go URL escaping is exercised.

References: [RFC 3986 section 2.4](https://www.rfc-editor.org/rfc/rfc3986#section-2.4),
[Spring URI decoding](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/util/UriUtils.html),
[Go proxy protocol](https://go.dev/ref/mod#goproxy-protocol).
