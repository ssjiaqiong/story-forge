#!/usr/bin/env bash
# =============================================================================
# 版本号读取（唯一来源 = pom.xml 的 <project><version>）
#
#   用法（Bash 脚本内）：
#     . "${SCRIPT_DIR}/lib/version.sh"
#     V="$(app_version)"        # 例如 1.4.0
#     T="v$(app_version)"       # 例如 v1.4.0（镜像标签）
#
#   直接执行可查看当前版本：
#     ./script/lib/version.sh
#
#   为什么不用「第一个 <version>」：pom.xml 里 spring-boot-starter-parent 的
#   <version>3.5.6</version> 排在前面，必须跳过 <parent> 块。这里按
#   </parent> 之后的第一处 <version> 取值；没有 parent 时退化为
#   artifactId 之后的第一处。
# =============================================================================

app_version() {
    local pom="${1:-}"
    if [ -z "$pom" ]; then
        pom="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/pom.xml"
    fi
    if [ ! -f "$pom" ]; then
        printf '找不到 %s\n' "$pom" >&2
        return 1
    fi

    local v
    v="$(awk '
        /<\/parent>/ { after = 1; next }
        after && /<version>/ {
            sub(/.*<version>[[:space:]]*/, "")
            sub(/[[:space:]]*<\/version>.*/, "")
            print
            exit
        }
    ' "$pom")"

    if [ -z "$v" ]; then
        v="$(awk '
            /<artifactId>storyforge<\/artifactId>/ { found = 1; next }
            found && /<version>/ {
                sub(/.*<version>[[:space:]]*/, "")
                sub(/[[:space:]]*<\/version>.*/, "")
                print
                exit
            }
        ' "$pom")"
    fi

    [ -n "$v" ] || { printf '无法从 %s 解析版本号\n' "$pom" >&2; return 1; }
    printf '%s' "$v"
}

# 被直接执行（而非 source）时输出当前版本
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    app_version "$@"
    echo
fi
