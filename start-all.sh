#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINGXI_PID=""
DEMO_PID=""

stop_services() {
    trap - EXIT INT TERM
    [[ -z "$LINGXI_PID" ]] || kill "$LINGXI_PID" 2>/dev/null || true
    [[ -z "$DEMO_PID" ]] || kill "$DEMO_PID" 2>/dev/null || true
    [[ -z "$LINGXI_PID" ]] || wait "$LINGXI_PID" 2>/dev/null || true
    [[ -z "$DEMO_PID" ]] || wait "$DEMO_PID" 2>/dev/null || true
}

trap stop_services EXIT INT TERM

"$PROJECT_ROOT/start.sh" &
LINGXI_PID=$!
"$PROJECT_ROOT/examples/resource-demo/start.sh" &
DEMO_PID=$!

echo "Lingxi 和 Demo 已启动，按 Ctrl+C 停止。"
while kill -0 "$LINGXI_PID" 2>/dev/null && kill -0 "$DEMO_PID" 2>/dev/null; do
    sleep 1
done

echo "有服务意外退出，正在停止另一个服务。" >&2
exit 1
