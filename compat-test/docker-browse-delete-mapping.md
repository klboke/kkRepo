# Docker Browse administrative deletion reference

Captured from a running Nexus Repository Community 3.94.0-12 instance. The browser loads
`/static/rapture/nexus-coreui-plugin-prod.js` and `/static/rapture/extdirect-prod.js`.
`NX.coreui.store.ComponentAssetTree` calls `NX.direct.coreui_Browse.read`; the Browse
controller opens `ComponentAssetInfo` for an `asset` node. Its Delete button calls
`NX.direct.coreui_Component.deleteAsset(asset.getId(), asset.get("repositoryName"))`.
The ExtDirect provider sends these calls to `POST /service/extdirect`.

| UI step | ExtDirect action / method | `data` arguments |
| --- | --- | --- |
| List tag leaves | `coreui_Browse.read` | `[{"repositoryName":"<repo>","node":"v2/<image>/tags"}]` |
| List digest leaves | `coreui_Browse.read` | `[{"repositoryName":"<repo>","node":"v2/<image>/manifests"}]` |
| Open selected leaf | `coreui_Component.readAsset` | `["<node.assetId>","<repo>"]` |
| Delete selected asset | `coreui_Component.deleteAsset` | `["<asset.id>","<asset.repositoryName>"]` |

Each request includes `type: "rpc"` and a transaction `tid`. Successful calls return
HTTP 200 with `result.success: true`. Deletion returns the affected asset paths in
`result.data`, such as `["/v2/<image>/manifests/latest"]`. The asset model supplies the
source repository; deletion does not guess it from the displayed group name.

kkRepo's existing Browse leaves use `<image>/manifests/<tag-or-digest>` and send
`DELETE /internal/browse/<repo>?path=<leaf>&source=<source-repo>`. Success is HTTP 200
with `repository`, `sourceRepository`, `path`, and `deletedAssets`. These transport
envelopes are intentionally different; the black-box test validates both contracts
and compares the remaining tag/digest reads through Registry V2.

The reference test follows the actual Nexus node lookup, asset lookup, and administrative
delete calls for both a tag and a digest. Tag deletion preserves another tag and the
digest on both systems. Administrative digest deletion removes the selected digest
entry and preserves tag assets on both systems. The test compares the retained tag's
status and manifest bytes, verifies that the deleted digest entry cannot be opened
or deleted again, and deletes the final tag through both administrative APIs.
The test confirms Nexus asset removal by calling `readAsset` again and checking its
`success: false` / `HTTP 404 Not Found` result. Registry digest reads can remain
available after the administrative asset deletion, so they are not used as proof
that the selected Nexus asset was removed.

kkRepo persists digest-reference deletion in the manifest's database attributes,
separately from the body still owned by its tags. Browse listing and detail lookup
omit the deleted reference across replicas. Deletion locks the manifest row and
releases the asset/blob reference only after both the digest entry and all tags
are gone. A subsequent manifest push restores the digest entry. The Registry V2
DELETE operation continues to remove the manifest and all associated tags.

This fix accepts tag/digest leaves only. Docker directory deletion is not exposed in
kkRepo Browse and directory requests are rejected. In particular, `team/manifests`
must never be inferred to mean deletion of the separate image `team`.

Run `DockerRegistryBlackBoxCompatibilityTest#browseDeletionUsesNexusAdministrativeAssetMappingWhenConfigured`
with writable Docker endpoints and `NEXUS_COMPAT_BASE_URL` / `KKREPO_COMPAT_BASE_URL`
pointing to their administrative application URLs. Docker connector endpoint overrides
remain available through `DOCKER_NEXUS_COMPAT_BASE_URL` and
`DOCKER_NEXUS_PLUS_COMPAT_BASE_URL`.
