#!/usr/bin/env bash
#
# Ensures AWS CLI v2 is available without sudo.
# Progress goes to stderr; the directory to prepend to PATH is printed on stdout.
#
# TOOL_VERSION: empty for the latest release, or a pinned version such as '2.17.0'. Only used when installing.
set -euo pipefail

TOOLS_DIR="${CI_TOOLS_DIR:-${HOME}/.cache/ci-tools}"
BIN_DIR="${TOOLS_DIR}/bin"
mkdir -p "${BIN_DIR}"
export PATH="${BIN_DIR}:${PATH}"

if command -v aws >/dev/null 2>&1; then
    echo "Using $(aws --version 2>&1) at $(command -v aws)" >&2
    echo "${BIN_DIR}"
    exit 0
fi

if ! command -v unzip >/dev/null 2>&1; then
    echo "ERROR: 'unzip' is required to install the AWS CLI. Bake aws-cli (or unzip) into the agent image." >&2
    exit 1
fi

case "$(uname -m)" in
    x86_64|amd64)  arch=x86_64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) echo "ERROR: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
zip="awscli-exe-linux-${arch}${TOOL_VERSION:+-${TOOL_VERSION}}.zip"

tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT

echo "Installing AWS CLI (${zip}) into ${TOOLS_DIR}..." >&2
curl -fsSL --retry 3 -o "${tmp}/awscliv2.zip" "https://awscli.amazonaws.com/${zip}"
unzip -q "${tmp}/awscliv2.zip" -d "${tmp}"
"${tmp}/aws/install" --install-dir "${TOOLS_DIR}/aws-cli" --bin-dir "${BIN_DIR}" --update >&2
echo "Installed $("${BIN_DIR}/aws" --version 2>&1)" >&2
echo "${BIN_DIR}"
