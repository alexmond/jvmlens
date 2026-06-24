#!/usr/bin/env bash
#
# Build & publish the jvmlens-agent image (jvmlens-agent.jar in a tiny OCI image), then
# optionally install/upgrade the standalone jvmlens Helm chart that attaches it to a JVM
# workload. Mirrors unitrack's scripts/deploy-k8s.sh conventions (same registry, same
# podman/docker + TLS handling).
#
# Usage:
#   scripts/deploy-agent.sh [options]
#     --registry HOST[:PORT]  image registry          (default: registry.example.com:5000)
#     --tag TAG               image tag               (default: project version from Maven)
#     --release NAME          if set, helm upgrade --install the jvmlens chart with this name
#     --namespace NS          target namespace        (default: unitrack)
#     --target-image IMAGE    JVM image to profile    (passed to the chart's target.image)
#     --no-build              skip the build (reuse the image already in the registry)
#     --no-push               build the image but don't publish
#
# Requires: podman or docker for the build; (for --release) kubectl + helm on the target
# cluster; and (for publish) a prior `podman login <registry>` / `docker login <registry>`.

set -euo pipefail
cd "$(dirname "$0")/.."

REGISTRY=registry.example.com:5000
TAG=""
RELEASE=""
NAMESPACE=unitrack
TARGET_IMAGE=""
BUILD=1
PUBLISH=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --registry)     REGISTRY="$2"; shift 2 ;;
    --tag)          TAG="$2"; shift 2 ;;
    --release)      RELEASE="$2"; shift 2 ;;
    --namespace)    NAMESPACE="$2"; shift 2 ;;
    --target-image) TARGET_IMAGE="$2"; shift 2 ;;
    --no-build)     BUILD=0; shift ;;
    --no-push)      PUBLISH=0; shift ;;
    -h|--help)      sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$TAG" ]]; then
  TAG="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
fi
IMAGE="${REGISTRY}/jvmlens-agent:${TAG}"

CONTAINER_CLI="$(command -v podman || command -v docker || true)"
TLS_FLAG=""
[[ "$CONTAINER_CLI" == *podman ]] && TLS_FLAG="--tls-verify=false"

if [[ "$BUILD" -eq 1 ]]; then
  [[ -n "$CONTAINER_CLI" ]] || { echo "!! need podman or docker to build the image" >&2; exit 1; }
  echo "==> Building the agent jar..."
  ./mvnw -q -DskipTests package
  AGENT_JAR="$(ls target/*-agent.jar | head -1)"
  [[ -n "$AGENT_JAR" ]] || { echo "!! no target/*-agent.jar produced" >&2; exit 1; }
  cp "$AGENT_JAR" deploy/agent-image/jvmlens-agent.jar
  echo "==> Building image $IMAGE via $CONTAINER_CLI..."
  "$CONTAINER_CLI" build -t "$IMAGE" deploy/agent-image
  rm -f deploy/agent-image/jvmlens-agent.jar
  if [[ "$PUBLISH" -eq 1 ]]; then
    echo "==> Pushing $IMAGE..."
    "$CONTAINER_CLI" push $TLS_FLAG "$IMAGE"
  fi
fi

if [[ -n "$RELEASE" ]]; then
  echo "==> helm upgrade --install $RELEASE (ns: $NAMESPACE)..."
  helm upgrade --install "$RELEASE" deploy/helm/jvmlens \
    --namespace "$NAMESPACE" --create-namespace \
    --set agent.image="$IMAGE" \
    ${TARGET_IMAGE:+--set target.image="$TARGET_IMAGE"}
fi

echo "Done: $IMAGE"
