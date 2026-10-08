# Blob-store deletion reference

Observed against Nexus 3.94.0 with a disposable file store and Raw hosted
repository. `BlobStoreDeletionReferenceTest` repeats this sequence against the
reference instance; `BlobStoresControllerTest` covers the kkRepo admin contract.

| Behavior | Nexus REST API | kkRepo internal admin API |
| --- | --- | --- |
| Target | `DELETE /service/rest/v1/blobstores/{name}` | `DELETE /internal/blob-stores/{id}` |
| Repository references the store | HTTP 400, `application/json`, `id: "*"`; message described below | HTTP 409, `application/json`, `message: "Blob store is still used by a repository"` |
| Unused configuration removed | HTTP 204, empty body | HTTP 204, empty body |

The Nexus error's `message` value is `"BlobStore <name> is in use and cannot be
deleted"`, including the surrounding quote characters inside the JSON string.
The reference test asserts the exact status, media type, ID and message with the
generated store name, then confirms the rejected deletion retained the store.
After removing the repository, deletion succeeds and GET returns 404.

The internal admin endpoint deliberately uses kkRepo's HTTP 409 conflict
contract and an actionable message, rather than exposing Nexus's REST error
envelope. This is a management API mapping; package protocol responses are
unchanged. kkRepo also rejects registered blobs, including pending cleanup, and
persisted upload sessions, and removes only the configuration. See the
[operator guide](../docs/en/admin-storage-usage.md#deleting-unused-configurations)
for the accepted race with unregistered in-flight I/O.
