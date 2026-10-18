#!/usr/bin/env bash
#
# Ensures the Snyk CLI is available without sudo.
# Progress goes to stderr; the directory to prepend to PATH is printed on stdout.
#
# TOOL_VERSION: 'stable' (default) or a pinned release such as 'v1.1293.0'. Only used when installing.
set -euo pipefail

BIN_DIR="${CI_TOOLS_DIR:-${HOME}/.cache/ci-tools}/bin"
VERSION="${TOOL_VERSION:-stable}"
mkdir -p "${BIN_DIR}"
export PATH="${BIN_DIR}:${PATH}"

if command -v snyk >/dev/null 2>&1; then
    echo "Using Snyk CLI $(snyk --version) at $(command -v snyk)" >&2
    echo "${BIN_DIR}"
    exit 0
fi

case "$(uname -m)" in
    x86_64|amd64)  asset=snyk-linux ;;
    aarch64|arm64) asset=snyk-linux-arm64 ;;
    *) echo "ERROR: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
if [ -f /etc/alpine-release ]; then
    asset="${asset/linux/alpine}"
fi

tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT
base_url="https://downloads.snyk.io/cli/${VERSION}"

echo "Installing Snyk CLI ${VERSION} (${asset}) into ${BIN_DIR}..." >&2
curl -fsSL --retry 3 -o "${tmp}/${asset}" "${base_url}/${asset}"
curl -fsSL --retry 3 -o "${tmp}/${asset}.sha256" "${base_url}/${asset}.sha256"

expected=$(awk '{print $1}' "${tmp}/${asset}.sha256")
actual=$(sha256sum "${tmp}/${asset}" | awk '{print $1}')
if [ -z "${expected}" ] || [ "${expected}" != "${actual}" ]; then
    echo "ERROR: checksum mismatch for ${asset} (expected ${expected}, got ${actual})" >&2
    exit 1
fi

# Install under a temp name, then rename: atomic for builds running concurrently on the same agent.
install -m 0755 "${tmp}/${asset}" "${BIN_DIR}/.snyk.$$"
mv -f "${BIN_DIR}/.snyk.$$" "${BIN_DIR}/snyk"
echo "Installed Snyk CLI $("${BIN_DIR}/snyk" --version)" >&2
echo "${BIN_DIR}"
