#!/usr/bin/env bash
#
# Fetches the sherpa-onnx Android AAR into libs/.
#
# sherpa-onnx is NOT published to Maven Central. Verified 2026-09-04 against the Central
# search API: there is no k2-fsa artifact there under any coordinates. The only sherpa
# artifact on Central is com.bihe0832.android:lib-sherpa-onnx, an unrelated third-party
# wrapper that this project does not use. The official build ships as a release asset on
# GitHub, so it is fetched here and consumed as a file dependency.
#
# The checksum is not optional. This AAR carries native code that will execute inside the
# application, and a build that silently accepts whatever the network returned is a
# supply-chain hole. If the hash does not match, nothing is installed.
#
# Usage:  tools/fetch_sherpa.sh
set -euo pipefail

VERSION="1.13.7"
SHA256="c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="${ROOT}/libs"
TARGET="${LIBS}/sherpa-onnx-${VERSION}.aar"
TEMP="${TARGET}.partial"

mkdir -p "${LIBS}"

if [ -f "${TARGET}" ]; then
    echo "already present: ${TARGET}"
    exit 0
fi

echo "fetching sherpa-onnx ${VERSION} (~47 MB)"
# --continue-at resumes an interrupted download rather than restarting it.
curl --fail --location --continue-at - --output "${TEMP}" "${URL}"

actual="$(sha256sum "${TEMP}" | cut -d' ' -f1)"
if [ "${actual}" != "${SHA256}" ]; then
    rm -f "${TEMP}"
    echo "CHECKSUM MISMATCH -- nothing installed" >&2
    echo "  expected ${SHA256}" >&2
    echo "  actual   ${actual}" >&2
    exit 1
fi

# Only a verified file ever appears at the destination.
mv "${TEMP}" "${TARGET}"
echo "installed ${TARGET}"
