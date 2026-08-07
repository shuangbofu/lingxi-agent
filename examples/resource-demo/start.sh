#!/usr/bin/env bash
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LISTEN_PORT="${DEMO_SERVER_PORT:-8091}"
cd "$DEMO_ROOT"

if ! command -v java >/dev/null 2>&1; then
    echo "缺少 Java 17 或更高版本" >&2
    exit 1
fi

if [[ -n "${DEMO_JAR:-}" ]]; then
    if [[ ! -f "$DEMO_JAR" ]]; then
        echo "DEMO_JAR 指定的文件不存在：$DEMO_JAR" >&2
        exit 1
    fi
elif [[ -f "$DEMO_ROOT/resource-demo.jar" ]]; then
    DEMO_JAR="$DEMO_ROOT/resource-demo.jar"
else
    LOCAL_BUILD="$(find backend/target -maxdepth 1 -type f -name 'lingxi-resource-demo-*.jar' ! -name '*.original' -print -quit 2>/dev/null || true)"
    if [[ -n "$LOCAL_BUILD" ]]; then
        DEMO_JAR="$LOCAL_BUILD"
    else
        echo "未找到 Demo JAR。请将发布包中的 JAR 放到当前目录并命名为 resource-demo.jar。" >&2
        exit 1
    fi
fi

echo "启动 Lingxi Resource Demo"
echo "访问地址：http://localhost:$LISTEN_PORT"
exec java -jar "$DEMO_JAR"
