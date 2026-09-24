# Test fixtures only. The upstream community images and binary downloads were removed.
# Keep the previous fixture releases, built from immutable official source archives.
FROM --platform=$BUILDPLATFORM golang:1.24.2-alpine AS build-base
ARG TARGETOS
ARG TARGETARCH
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
WORKDIR /src

FROM build-base AS minio-build
# RELEASE.2025-04-22T22-12-26Z
ADD --checksum=sha256:7eb30a913fea30f18069abf194e1e78e4983b558cc526911ae1c11396a9859a5 https://codeload.github.com/minio/minio/tar.gz/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src && rm /tmp/source.tar.gz
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
      -ldflags='-s -w -X github.com/minio/minio/cmd.CopyrightYear=2025 -X github.com/minio/minio/cmd.ShortCommitID=0d7408fc9969c -X github.com/minio/minio/cmd.Version=2025-04-22T22:12:26Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-04-22T22-12-26Z -X github.com/minio/minio/cmd.CommitID=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e' \
      -o /out/minio .

FROM build-base AS mc-build
# RELEASE.2025-04-16T18-13-26Z
ADD --checksum=sha256:4cd13e34daeeb8481c3ba8686b082f161b8dc1f7aad52d715a706a587349c6ae https://codeload.github.com/minio/mc/tar.gz/b00526b153a31b36767991a4f5ce2cced435ee8e /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src && rm /tmp/source.tar.gz
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
      -ldflags='-s -w -X github.com/minio/mc/cmd.CopyrightYear=2025 -X github.com/minio/mc/cmd.ShortCommitID=b00526b153a31 -X github.com/minio/mc/cmd.Version=2025-04-16T18:13:26Z -X github.com/minio/mc/cmd.ReleaseTag=RELEASE.2025-04-16T18-13-26Z -X github.com/minio/mc/cmd.CommitID=b00526b153a31b36767991a4f5ce2cced435ee8e' \
      -o /out/mc .

FROM alpine:3.21 AS runtime
RUN apk add --no-cache ca-certificates curl

FROM runtime AS minio
COPY --from=minio-build /out/minio /usr/local/bin/minio
COPY --from=minio-build /src/LICENSE /licenses/minio/LICENSE
ENTRYPOINT ["/usr/local/bin/minio"]

FROM runtime AS mc
COPY --from=mc-build /out/mc /usr/local/bin/mc
COPY --from=mc-build /src/LICENSE /licenses/mc/LICENSE
ENTRYPOINT ["/usr/local/bin/mc"]
