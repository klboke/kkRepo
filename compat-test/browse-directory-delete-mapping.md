# Browse directory deletion reference and safety contract

Reference: [Sonatype Browse documentation](https://help.sonatype.com/en/browsing-repositories.html).

The running Nexus 3.68.1 reference serves
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

The initial supported hosted formats are Maven, npm, PyPI, NuGet, Helm, Yum,
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
retention of similarly named sibling directories using disposable Raw repositories.
