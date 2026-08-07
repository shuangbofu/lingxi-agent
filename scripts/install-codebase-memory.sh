#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TOOLS_DIR="${PROJECT_DIR}/.tools"
VERSION="${CBM_VERSION:-v0.9.0}"
OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH_NAME="$(uname -m)"

case "${OS_NAME}" in
  darwin) PLATFORM="darwin" ;;
  linux) PLATFORM="linux" ;;
  *) echo "不支持的操作系统：${OS_NAME}" >&2; exit 1 ;;
esac

case "${ARCH_NAME}" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64|amd64) ARCH="amd64" ;;
  *) echo "不支持的处理器架构：${ARCH_NAME}" >&2; exit 1 ;;
esac

ASSET="codebase-memory-mcp-${PLATFORM}-${ARCH}.tar.gz"
BASE_URL="https://github.com/DeusData/codebase-memory-mcp/releases/download/${VERSION}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

mkdir -p "${TOOLS_DIR}"
curl -fsSL "${BASE_URL}/${ASSET}" -o "${TEMP_DIR}/${ASSET}"
curl -fsSL "${BASE_URL}/checksums.txt" -o "${TEMP_DIR}/checksums.txt"

EXPECTED_SHA="$(awk -v asset="${ASSET}" '$2 == asset { print $1 }' "${TEMP_DIR}/checksums.txt")"
ACTUAL_SHA="$(shasum -a 256 "${TEMP_DIR}/${ASSET}" | awk '{ print $1 }')"
if [[ -z "${EXPECTED_SHA}" || "${EXPECTED_SHA}" != "${ACTUAL_SHA}" ]]; then
  echo "Codebase Memory 安装包校验失败" >&2
  exit 1
fi

tar -xzf "${TEMP_DIR}/${ASSET}" -C "${TEMP_DIR}"
BINARY_PATH="$(find "${TEMP_DIR}" -type f -name codebase-memory-mcp -perm -u+x | head -n 1)"
if [[ -z "${BINARY_PATH}" ]]; then
  echo "安装包内未找到 Codebase Memory 二进制" >&2
  exit 1
fi

install -m 755 "${BINARY_PATH}" "${TOOLS_DIR}/codebase-memory-mcp"
"${TOOLS_DIR}/codebase-memory-mcp" --version
echo "已安装到 ${TOOLS_DIR}/codebase-memory-mcp"
