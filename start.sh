#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LISTEN_PORT="${SERVER_PORT:-8080}"
cd "$PROJECT_ROOT"

if ! command -v java >/dev/null 2>&1; then
    echo "缺少 Java 17 或更高版本" >&2
    exit 1
fi

# JVM 启动后文件系统编码不可变，必须在启动前使用系统实际安装的 UTF-8 locale。
if [[ -n "${LINGXI_LOCALE:-}" ]]; then
    if ! locale -a 2>/dev/null | grep -Fxiq -- "$LINGXI_LOCALE"; then
        echo "LINGXI_LOCALE 指定的 locale 未安装：$LINGXI_LOCALE" >&2
        exit 1
    fi
    PROCESS_LOCALE="$LINGXI_LOCALE"
else
    PROCESS_LOCALE=""
    for candidate in zh_CN.UTF-8 zh_CN.utf8 C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
        if locale -a 2>/dev/null | grep -Fxiq -- "$candidate"; then
            PROCESS_LOCALE="$candidate"
            break
        fi
    done
    if [[ -z "$PROCESS_LOCALE" ]]; then
        echo "系统未安装 UTF-8 locale，无法安全处理中文任务产物文件" >&2
        exit 1
    fi
fi
export LANG="$PROCESS_LOCALE"
export LC_ALL="$PROCESS_LOCALE"

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
echo "运行 locale：$PROCESS_LOCALE"
echo "访问地址：http://localhost:$LISTEN_PORT"
exec java -jar "$LINGXI_JAR"
