#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_ROOT="${REPO_ROOT}/build/plugin"
DIST_ROOT="${REPO_ROOT}/dist"
PLUGIN_META="${REPO_ROOT}/metabase-plugin.yaml"
DRIVER_SRC="${REPO_ROOT}/src/metabase/driver"
ARTIFACT_NAME="http-echo-driver.jar"

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}/metabase/driver"
cp "${PLUGIN_META}" "${BUILD_ROOT}/"
cp -R "${DRIVER_SRC}/." "${BUILD_ROOT}/metabase/driver/"
mkdir -p "${DIST_ROOT}"
jar cf "${DIST_ROOT}/${ARTIFACT_NAME}" -C "${BUILD_ROOT}" .

echo "Built ${DIST_ROOT}/${ARTIFACT_NAME}"
