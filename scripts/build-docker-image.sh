#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BUILD_MODE="jvm"
IMAGE_TAG=""

usage() {
  cat <<'EOF'
Usage: scripts/build-docker-image.sh [--native] [image-tag]

Builds the JVM image by default. Pass --native explicitly to build a Spring
AOT/GraalVM Native Image with Cloud Native Buildpacks.
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
    -*)
      echo "[image] unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$IMAGE_TAG" ]]; then
        echo "[image] only one image tag may be specified" >&2
        usage >&2
        exit 2
      fi
      IMAGE_TAG="$1"
      ;;
  esac
  shift
done

IMAGE_TAG="${IMAGE_TAG:-${KKREPO_IMAGE_TAG:-kkrepo:38090}}"
PROJECT_VERSION="$(mvn -q -N -DforceStdout help:evaluate -Dexpression=project.version)"
JAR_FILE="server/target/kkrepo-server-${PROJECT_VERSION}.jar"
START_CLASS="com.github.klboke.kkrepo.server.KkRepoApplication"

if [[ "$BUILD_MODE" == "native" ]]; then
  echo "[image] building Spring AOT/GraalVM native image $IMAGE_TAG..."
  mvn -Pnative -pl server -am -Dmaven.test.skip=true \
    -DskipNativeBuild=true \
    -Dspring-boot.build-image.imageName="$IMAGE_TAG" \
    clean install spring-boot:build-image-no-fork
  docker image inspect "$IMAGE_TAG" >/dev/null
  echo "[image] built native image $IMAGE_TAG"
  exit 0
fi

echo "[image] building Spring Boot jar..."
mvn -pl server -am -DskipTests clean package spring-boot:repackage

echo "[image] verifying executable jar manifest..."
manifest="$(unzip -p "$JAR_FILE" META-INF/MANIFEST.MF)"
jar_listing="$(jar tf "$JAR_FILE")"

if [[ "$manifest" != *"Main-Class: org.springframework.boot.loader.launch.JarLauncher"* ]]; then
  echo "[image] $JAR_FILE is not a Spring Boot executable jar: missing JarLauncher manifest" >&2
  exit 1
fi

if [[ "$manifest" != *"Start-Class: $START_CLASS"* ]]; then
  echo "[image] $JAR_FILE is not a Spring Boot executable jar: missing Start-Class $START_CLASS" >&2
  exit 1
fi

if [[ "$jar_listing" != BOOT-INF/* && "$jar_listing" != *$'\n'BOOT-INF/* ]]; then
  echo "[image] $JAR_FILE is not a Spring Boot executable jar: missing BOOT-INF layout" >&2
  exit 1
fi

echo "[image] building Docker image $IMAGE_TAG..."
docker build --build-arg "JAR_FILE=$JAR_FILE" -t "$IMAGE_TAG" .

echo "[image] built $IMAGE_TAG"
