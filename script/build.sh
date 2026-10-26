#!/usr/bin/env bash
# =============================================================================
# StoryForge 构建脚本（Linux / macOS / Windows Git Bash）
#
#   产物：target/app.jar（前端静态资源已合并进 jar，单端口即可运行）
#
#   用法：
#     ./script/build.sh                    # 构建前端 + 后端（推荐）
#     ./script/build.sh --skip-web         # 只打包后端（复用已构建的 static）
#     ./script/build.sh --with-tests       # 一并运行单元测试
#     ./script/build.sh --no-clean         # 增量构建，不做 clean
#     ./script/build.sh --skip-typecheck   # 前端跳过 vue-tsc 类型检查
#     ./script/build.sh --image            # 打包后再构建镜像并推送腾讯云 TCR
#     ./script/build.sh --help
#
#   可用环境变量覆盖：
#     MVN_CMD   Maven 可执行文件（默认 mvn）
#     NPM_CMD   npm 可执行文件（默认 npm）
#     JAVA_HOME JDK 17+ 根目录（java / mvn 都会读取）
#
#   镜像构建细节见 script/deploy/build.sh（--tag / --no-push / --local 等）。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
MVN_CMD="${MVN_CMD:-mvn}"
NPM_CMD="${NPM_CMD:-npm}"
JAR_PATH="${APP_HOME}/target/app.jar"
STATIC_DIR="${APP_HOME}/src/main/resources/static"
WEB_DIR="${APP_HOME}/web"

SKIP_WEB=0
SKIP_TYPECHECK=0
WITH_TESTS=0
DO_CLEAN=1
BUILD_IMAGE=0

# ---------- 输出 ----------
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_STEP=$'\033[1;34m'
else
    C_RESET=''; C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_STEP=''
fi
step() { printf '%s==> %s%s\n' "${C_STEP}" "$*" "${C_RESET}"; }
info() { printf '%s  - %s%s\n' "${C_INFO}" "$*" "${C_RESET}"; }
ok()   { printf '%s  ✓ %s%s\n' "${C_OK}" "$*" "${C_RESET}"; }
warn() { printf '%s  ! %s%s\n' "${C_WARN}" "$*" "${C_RESET}"; }
die()  { printf '%s  ✗ %s%s\n' "${C_ERR}" "$*" "${C_RESET}" >&2; exit 1; }

usage() {
    sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

# ---------- 参数 ----------
for arg in "$@"; do
    case "${arg}" in
        --skip-web)        SKIP_WEB=1 ;;
        --skip-typecheck)  SKIP_TYPECHECK=1 ;;
        --with-tests)      WITH_TESTS=1 ;;
        --no-clean)        DO_CLEAN=0 ;;
        --image)           BUILD_IMAGE=1 ;;
        -h|--help)         usage ;;
        *) die "未知参数：${arg}（用 --help 查看用法）" ;;
    esac
done

cd "${APP_HOME}"

# ---------- 环境检查 ----------
check_java() {
    command -v java >/dev/null 2>&1 || die "未找到 java，请安装 JDK 17+ 并配置 PATH / JAVA_HOME"
    local major
    major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\)[".].*/\1/p' | head -n 1)"
    if [ -z "${major}" ]; then
        warn "无法解析 java 版本，跳过版本校验"
        return
    fi
    if [ "${major}" = "1" ]; then
        major="$(java -version 2>&1 | sed -n 's/.*version "1\.\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    fi
    if [ "${major}" -lt 17 ] 2>/dev/null; then
        die "检测到 Java ${major}，本项目要求 JDK 17+（pom.xml: release=17）"
    fi
    ok "Java ${major}：$(java -version 2>&1 | sed -n '2p' | sed 's/^[[:space:]]*//')"
}

check_maven() {
    command -v "${MVN_CMD}" >/dev/null 2>&1 || die "未找到 ${MVN_CMD}，请安装 Maven 3.9+ 并配置 PATH"
    local ver
    ver="$("${MVN_CMD}" -v 2>/dev/null | sed -n 's/^Apache Maven \([0-9.]*\).*/\1/p' | head -n 1)"
    ok "Maven ${ver:-(版本未知)}"
}

check_node() {
    command -v node >/dev/null 2>&1 || die "未找到 node，请安装 Node 20+ 并配置 PATH（或设置 NODE_HOME 后重开终端）"
    command -v "${NPM_CMD}" >/dev/null 2>&1 || die "未找到 ${NPM_CMD}，请确认 Node.js 安装完整"
    local node_major
    node_major="$(node -v | sed 's/^v\([0-9]*\).*/\1/')"
    if [ "${node_major}" -lt 20 ] 2>/dev/null; then
        warn "当前 Node $(node -v)，建议 Node 20+（低版本 Vite 5 可能构建失败）"
    fi
    ok "Node $(node -v) / ${NPM_CMD} $("${NPM_CMD}" -v 2>/dev/null)"
}

# ---------- 1. 前端构建 ----------
build_web() {
    step "构建前端（Vue3 + Vite）"
    cd "${WEB_DIR}"

    if [ ! -d node_modules ]; then
        info "未检测到 node_modules，先安装依赖…"
        if [ -f package-lock.json ]; then
            "${NPM_CMD}" ci || die "npm ci 失败"
        else
            "${NPM_CMD}" install || die "npm install 失败"
        fi
    else
        info "node_modules 已存在，跳过依赖安装（如需重装请手动删除该目录）"
    fi

    if [ "${SKIP_TYPECHECK}" -eq 1 ]; then
        info "跳过类型检查（--skip-typecheck）"
        "${NPM_CMD}" run build || die "前端构建失败（npm run build）"
    else
        info "执行类型检查 + 构建（npm run build:typecheck）"
        "${NPM_CMD}" run build:typecheck || die "前端构建失败（类型检查未通过？可用 --skip-typecheck 跳过）"
    fi

    [ -f dist/index.html ] || die "前端构建产物缺失：web/dist/index.html"

    local count
    count="$(find dist -type f | wc -l | tr -d ' ')"
    ok "前端产物 ${count} 个文件 → web/dist"

    # 合并进 static：只覆盖不删除，历史 hash 命名的旧文件不会被引用，保留无害
    step "合并前端产物到 src/main/resources/static"
    mkdir -p "${STATIC_DIR}"
    cp -R dist/. "${STATIC_DIR}/"
    ok "已同步到 src/main/resources/static"

    cd "${APP_HOME}"
}

# ---------- 2. 后端打包 ----------
build_backend() {
    step "打包后端（Spring Boot）"
    local args=(-B)
    if [ "${WITH_TESTS}" -eq 0 ]; then
        args+=(-DskipTests)
    fi
    if [ "${DO_CLEAN}" -eq 1 ]; then
        args+=(clean)
    fi
    args+=(package)

    info "命令：${MVN_CMD} ${args[*]}"
    if ! "${MVN_CMD}" "${args[@]}"; then
        if [ "${DO_CLEAN}" -eq 1 ]; then
            warn "打包失败。若报错为「文件被占用 / cannot delete target/app.jar」，"
            warn "说明有 StoryForge 进程正在运行并锁定了 jar，请先停止服务再构建。"
        fi
        die "后端打包失败"
    fi

    [ -f "${JAR_PATH}" ] || die "打包完成但未找到 ${JAR_PATH}"
    local size
    size="$(du -h "${JAR_PATH}" | cut -f1 | tr -d ' ')"
    ok "产物 target/app.jar（${size}）"
}

# ---------- 主流程 ----------
printf '%s\n' "=============================================="
printf '%s\n' " StoryForge 构建"
printf '%s\n' " 工程根目录：${APP_HOME}"
printf '%s\n' "=============================================="

step "检查构建环境"
check_java
check_maven
ok "当前 JDK：${JAVA_HOME:-<未设置 JAVA_HOME，使用 PATH 中的 java>}"

if [ "${SKIP_WEB}" -eq 1 ]; then
    step "跳过前端构建（--skip-web）"
    [ -f "${STATIC_DIR}/index.html" ] || warn "static/index.html 不存在，打出来的 jar 将没有前端页面"
else
    check_node
    build_web
fi

build_backend

if [ "${BUILD_IMAGE}" -eq 1 ]; then
    step "构建并推送容器镜像（script/deploy/build.sh）"
    bash "${SCRIPT_DIR}/deploy/build.sh" --skip-jar
fi

step "构建完成"
ok "启动：./script/start.sh        （Windows：script\\start.bat）"
info "健康检查：curl http://127.0.0.1:8080/actuator/health"
