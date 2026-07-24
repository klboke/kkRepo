#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BUILD_MODE="jvm"

usage() {
  cat <<'EOF'
Usage: scripts/build-dist.sh [--native]

Builds JVM tar.gz and zip archives by default. Pass --native explicitly to
build platform-specific Spring AOT/GraalVM native archives through Docker.

Set KKREPO_NATIVE_IMAGE to choose the intermediate Native image tag. Set
KKREPO_KEEP_NATIVE_IMAGE=true to retain that image after packaging.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --native)
      BUILD_MODE="native"
      ;;
    --jvm)
      BUILD_MODE="jvm"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[dist] unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

PROJECT_VERSION="$(mvn -q -N -DforceStdout help:evaluate -Dexpression=project.version)"
DIST_VERSION="${PROJECT_VERSION%-SNAPSHOT}"
DIST_SUFFIX=""
RUNTIME_SOURCE="server/target/kkrepo-server-${PROJECT_VERSION}.jar"
RUNTIME_NAME="kkrepo.jar"
RUNTIME_MODE="0644"

echo "[dist] project version: $PROJECT_VERSION"
echo "[dist] archive version: $DIST_VERSION"

if [[ "$BUILD_MODE" == "native" ]]; then
  NATIVE_IMAGE="${KKREPO_NATIVE_IMAGE:-kkrepo:native-dist-${DIST_VERSION//[^a-zA-Z0-9_.-]/-}-$$}"
  KEEP_NATIVE_IMAGE="${KKREPO_KEEP_NATIVE_IMAGE:-false}"
  NATIVE_CONTAINER="kkrepo-native-dist-extract-$$"

  cleanup_native_build() {
    docker rm -f "$NATIVE_CONTAINER" >/dev/null 2>&1 || true
    if [[ "$KEEP_NATIVE_IMAGE" != "true" ]]; then
      docker image rm "$NATIVE_IMAGE" >/dev/null 2>&1 || true
    fi
  }
  trap cleanup_native_build EXIT

  echo "[dist] building Spring AOT/GraalVM native image $NATIVE_IMAGE..."
  scripts/build-docker-image.sh --native "$NATIVE_IMAGE"

  NATIVE_OS="$(docker image inspect "$NATIVE_IMAGE" --format '{{.Os}}')"
  NATIVE_ARCH="$(docker image inspect "$NATIVE_IMAGE" --format '{{.Architecture}}')"
  case "$NATIVE_ARCH" in
    x86_64) NATIVE_ARCH="amd64" ;;
    aarch64) NATIVE_ARCH="arm64" ;;
  esac
  DIST_SUFFIX="-native-${NATIVE_OS}-${NATIVE_ARCH}"
  RUNTIME_SOURCE="server/target/kkrepo"
  RUNTIME_NAME="kkrepo"
  RUNTIME_MODE="0755"

  docker create --name "$NATIVE_CONTAINER" "$NATIVE_IMAGE" >/dev/null
  docker cp \
    "$NATIVE_CONTAINER:/workspace/com.github.klboke.kkrepo.server.KkRepoApplication" \
    "$RUNTIME_SOURCE"
  chmod 0755 "$RUNTIME_SOURCE"
  docker rm "$NATIVE_CONTAINER" >/dev/null
else
  echo "[dist] building Spring Boot executable jar and installing reactor artifacts..."
  mvn -pl server -am -DskipTests clean install spring-boot:repackage
fi

echo "[dist] assembling archive distribution..."
mvn -pl server -DskipTests \
  -Dkkrepo.dist.version="$DIST_VERSION" \
  -Dkkrepo.dist.suffix="$DIST_SUFFIX" \
  -Dkkrepo.dist.runtime.source="$ROOT/$RUNTIME_SOURCE" \
  -Dkkrepo.dist.runtime.name="$RUNTIME_NAME" \
  -Dkkrepo.dist.runtime.mode="$RUNTIME_MODE" \
  assembly:single

echo "[dist] built archives:"
ls -lh \
  "server/target/kkrepo-${DIST_VERSION}${DIST_SUFFIX}.tar.gz" \
  "server/target/kkrepo-${DIST_VERSION}${DIST_SUFFIX}.zip"
