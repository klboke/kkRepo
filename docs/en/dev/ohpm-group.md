# OHPM Group

The `ohpm-group` recipe combines ordered OHPM hosted and proxy members. It is read-only. Clients use the same raw Token configuration and repository READ permission as hosted/proxy repositories.

Create a group through the existing repository management API:

```json
{
  "name": "ohpm-all",
  "recipe": "ohpm-group",
  "online": true,
  "group": {
    "memberNames": ["ohpm-private", "ohpm-public"]
  }
}
```

Members must use the OHPM format and be hosted or proxy repositories. Nested groups, other formats and self-membership are rejected. Changes to member order are read from the shared database on every group request.

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-all/
ohpm config set //repo.example.com/repository/ohpm-all/:_auth <token>
ohpm install
```

## Selection and download behavior

- Versions and dist-tags use first-member precedence independently. Each version's timestamp comes from the member that supplies that version. Valid `time.created` and `time.modified` values use the earliest creation and latest modification times across members.
- HSP type fields are normalized onto their individual versions. A root-level HSP declaration from one member cannot classify unrelated HAR versions from another member.
- A version selects exactly one member for both HAR and HSP. A HAR-only version in an earlier member cannot acquire an HSP file from a later member. If a selected file is missing, the request fails without falling back to different bytes.
- Generated download URLs include `kkrepo_source`, a fingerprint of the group, selected member, package/version and both file checksums. Both files in an HSP pair share this fingerprint. Preserve the complete URL, including its query parameter.
- Missing or malformed fingerprints return `400`; a changed selection or checksum returns `409`. Refresh package metadata and update download URLs in the client lockfile as appropriate before retrying. The fingerprint is not a credential: normal repository authorization still applies, and selection is recomputed for every request.
- The actual blob checksum is compared with the selected metadata before opening the download response. A concurrent replacement cannot silently return bytes from another publication.

Group metadata uses a content ETag and `Cache-Control: private, no-cache`. It does not send Last-Modified: member publication timestamps alone cannot validate a change to group membership or order. An unchanged ETag supports `304`; a time-only conditional request receives the current metadata.

## Member failures and consistency

A member returning `404` or `410` for the package is skipped. An offline member returns `503`; other member failures, including `451`, fail the group request. The service does not silently change precedence because a member failed. Proxy members may still supply a valid stale snapshot according to their own documented policy.

The group reads current repository/member rows directly, and delegates content reads to the selected hosted or proxy service. It introduces no node-local selection cache or new persistence table. Source download policy remains in force, with the group recorded as the request entry repository.

## Validation

Pure merge and service tests cover conflicts, HSP normalization, timestamps, fingerprints, member failures, missing files, changes between selection and download, and conditional metadata behavior:

```bash
mvn -pl server -am -Dtest='OhpmGroup*Test,OhpmProtocolHandlerTest,RepositoryRecipesTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`OhpmProxyRuntimeTest` also exercises groups containing hosted and authenticated proxy members. Both MySQL and PostgreSQL run with two kkRepo instances, covering member-type rejection, shared configuration changes, HAR/HSP selection, no fallback on a missing file, offline members, read-only operations, query preservation and real OHPM installation:

```bash
mvn -pl server -am -Dtest=OhpmProxyRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

These checks use generated protocol fixtures and file test storage. See [independent acceptance](ohpm-acceptance.md) for historical-client and S3-compatible storage coverage and its limits. HSP device execution remains unverified.
