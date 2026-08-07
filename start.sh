#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LISTEN_PORT="${SERVER_PORT:-8080}"
cd "$PROJECT_ROOT"

if ! command -v java >/dev/null 2>&1; then
    echo "缺少 Java 17 或更高版本" >&2
    exit 1
fi

if [[ -n "${LINGXI_JAR:-}" ]]; then
    if [[ ! -f "$LINGXI_JAR" ]]; then
        echo "LINGXI_JAR 指定的文件不存在：$LINGXI_JAR" >&2
        exit 1
    fi
elif [[ -f "$PROJECT_ROOT/lingxi.jar" ]]; then
    LINGXI_JAR="$PROJECT_ROOT/lingxi.jar"
else
    LOCAL_BUILD="$(find backend/target -maxdepth 1 -type f -name 'lingxi-*.jar' ! -name '*.original' -print -quit 2>/dev/null || true)"
    if [[ -n "$LOCAL_BUILD" ]]; then
        LINGXI_JAR="$LOCAL_BUILD"
    else
        echo "未找到 Lingxi JAR。当前为 dev 阶段，请先由开发构建生成 backend/target/lingxi-*.jar，或将已有 JAR 放到项目根目录并命名为 lingxi.jar。" >&2
        exit 1
    fi
fi

echo "启动 Lingxi"
echo "访问地址：http://localhost:$LISTEN_PORT"
exec java -jar "$LINGXI_JAR"
