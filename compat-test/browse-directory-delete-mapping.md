# Browse directory deletion reference and safety contract

Reference: [Sonatype Browse documentation](https://help.sonatype.com/en/browsing-repositories.html).

The running Nexus 3.94.0 and 3.68.1 references serve
`/static/rapture/nexus-coreui-plugin-prod.js`. Its Browse controller selects
`ComponentFolderInfo` for a folder, asks for confirmation, and calls
`NX.direct.coreui_Component.deleteFolder(decodedPath, repositoryName)` through
`POST /service/extdirect` (`action: coreui_Component`, `method: deleteFolder`,
`data: [path, repositoryName]`). Button enablement calls `canDeleteFolder` with
the same arguments. The UI hides folder deletion for group repositories.

kkRepo uses its own management transport: `GET /internal/browse/{repository}/directory-deletion`
previews the selection, then `POST` to the same URL submits `path`,
`confirmationPath` and `selectionToken`. This is an administrative operation,
not an extension to any package client's DELETE protocol. Both calls require the
existing Browse deletion privilege (`nexus:*`).

The initial supported hosted formats are Maven, npm, PyPI, NuGet, Yum,
RubyGems and Raw. Proxy/group and other formats retain their existing individual
asset actions; they do not expose this bulk operation. Repository-root deletion
is rejected. Each preview includes the complete bounded selection (at most 500
assets and 1,000 tree nodes); larger directories must be narrowed before retrying.
No truncated preview may be executed. Indexed parent/child traversal uses exact
node identities, including literal percent/underscore and case/accent differences.

Maven, Raw and Yum use exact asset identities. Other formats use complete package
components through the existing protocol deletion adapters; a selection crossing
a component boundary is rejected. Generated metadata is identified separately in
the preview and repaired by the protocol, rather than deleted as a package.
All deletes and rebuild markers commit in one database transaction. The execution
locks the selected components/assets and rechecks the preview fingerprint before
any deletion. Another replica can handle execution; no local preview cache is used.
A concurrent addition after final validation is not part of the confirmed selection
and is retained. This operation does not provide an undo or restore feature.

`BrowseDirectoryDeletionBlackBoxCompatibilityTest` follows the Nexus folder-delete
mapping and kkRepo's preview/confirmation mapping, comparing nested removal and
retention of similarly named sibling directories using disposable repositories for
all seven enabled formats. It waits for the uploaded assets in `coreui_Browse.read`
before deleting: Nexus 3.94 builds that tree asynchronously, and an earlier
`deleteFolder` can succeed without selecting any assets.

| Format | Selection and checks |
| --- | --- |
| Raw | Nested files removed; similarly named and unrelated siblings retain bytes |
| Maven | Both versions and artifact metadata removed; sibling artifact retained |
| npm | Both tarballs removed; versions/tags cleared; sibling packument and bytes retained |
| PyPI | Both wheels removed with 404; Simple root/project indexes updated; sibling retained |
| NuGet | Nexus name/version projection mapped to kkRepo flat-container folder; packages/nuspecs and version indexes checked |
| Yum | RPM directory removed; scoped primary metadata stops advertising it; other release bytes/repomd unchanged |
| RubyGems | Gem files and quick gemspecs removed; compact and Marshal indexes updated |

The test records existing generated-metadata distinctions explicitly. Nexus
returns 404 for an emptied npm packument, NuGet version index and RubyGems compact
info. kkRepo retains 200 responses with empty npm `versions`/`dist-tags`, an empty
NuGet `versions` array and an empty RubyGems compact info body. No removed version
remains installable or advertised. Generated metadata retention is already part
of the management preview contract; these existing client semantics are preserved.

Helm remains unavailable for bulk deletion. Nexus projects flat chart URLs into
virtual name/version folders, whereas kkRepo Browse currently displays the stored
paths. The reference test verifies that projection and kkRepo's rejection while
retaining both uploaded chart versions; enabling it requires a matching Browse
projection and positive deletion test.
