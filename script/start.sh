#!/usr/bin/env bash
# =============================================================================
# StoryForge 启动脚本（Linux / macOS / Windows Git Bash）
#
#   前置：已执行 ./script/build.sh 生成 target/app.jar
#
#   用法：
#     ./script/start.sh                  # 默认端口 8080，profile=dev
#     ./script/start.sh --port 9090      # 指定端口
#     ./script/start.sh --profile prod   # 指定 Spring profile
#     ./script/start.sh --no-wait        # 跳过数据库就绪等待
#     ./script/start.sh --help
#
#   环境变量（未设置时使用下列本地开发默认值）：
#     DB_HOST=127.0.0.1  DB_PORT=5432  DB_NAME=storyforge
#     DB_USER=postgres   DB_PASSWORD=postgres
#     SERVER_PORT=8080   SPRING_PROFILES_ACTIVE=dev
#     STORYFORGE_JWT_SECRET    JWT 签名密钥（≥32 字节，生产必须替换）
#     STORYFORGE_SECRET_KEY    API Key 加密主密钥（≥32 字节）
#     STORYFORGE_FILE_ROOT=./data/files   STORYFORGE_COVER_ROOT=./data/covers
#     JAVA_OPTS="-Xms256m -Xmx1024m"
#     APP_JAR=target/app.jar
#
#   说明：端口通过命令行参数 --server.port 传入，优先级高于环境变量，
#         可避免外部注入的 SERVER__PORT 覆盖配置（本项目开发环境踩过此坑）。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

PORT_ARG=""
PROFILE_ARG=""
WAIT_DB=1

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

while [ $# -gt 0 ]; do
    case "$1" in
        --port)     PORT_ARG="${2:-}";    [ -n "${PORT_ARG}" ] || die "--port 需要端口号"; shift 2 ;;
        --profile)  PROFILE_ARG="${2:-}"; [ -n "${PROFILE_ARG}" ] || die "--profile 需要取值"; shift 2 ;;
        --no-wait)  WAIT_DB=0; shift ;;
        -h|--help)  usage ;;
        *) die "未知参数：$1（用 --help 查看用法）" ;;
    esac
done

cd "${APP_HOME}"

# ---------- 默认配置 ----------
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-storyforge}"
export DB_USER="${DB_USER:-postgres}"
export DB_PASSWORD="${DB_PASSWORD:-postgres}"
export SERVER_PORT="${PORT_ARG:-${SERVER_PORT:-8080}}"
export SPRING_PROFILES_ACTIVE="${PROFILE_ARG:-${SPRING_PROFILES_ACTIVE:-dev}}"
export STORYFORGE_JWT_SECRET="${STORYFORGE_JWT_SECRET:-storyforge-local-dev-secret-key-32bytes-minimum-2026}"
export STORYFORGE_SECRET_KEY="${STORYFORGE_SECRET_KEY:-storyforge-local-crypto-secret-2026}"
export STORYFORGE_FILE_ROOT="${STORYFORGE_FILE_ROOT:-${APP_HOME}/data/files}"
export STORYFORGE_COVER_ROOT="${STORYFORGE_COVER_ROOT:-${APP_HOME}/data/covers}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1024m}"
JAR_PATH="${APP_JAR:-${APP_HOME}/target/app.jar}"

# ---------- 校验 ----------
command -v java >/dev/null 2>&1 || die "未找到 java，请安装 JDK 17+ 并配置 PATH / JAVA_HOME"

[ -f "${JAR_PATH}" ] || die "未找到 ${JAR_PATH}，请先执行 ./script/build.sh 构建"

jwt_len="${#STORYFORGE_JWT_SECRET}"
if [ "${jwt_len}" -lt 32 ]; then
    die "STORYFORGE_JWT_SECRET 长度为 ${jwt_len}，要求 ≥ 32 字节"
fi
if [ "${STORYFORGE_JWT_SECRET}" = "storyforge-local-dev-secret-key-32bytes-minimum-2026" ]; then
    warn "正在使用内置开发密钥，仅限本机调试；生产环境必须通过环境变量替换"
fi
if [ "${#STORYFORGE_SECRET_KEY}" -lt 32 ]; then
    die "STORYFORGE_SECRET_KEY 长度不足 32 字节（用于加密 AI/搜索的 API Key，上线后不可更换）"
fi

mkdir -p "${STORYFORGE_FILE_ROOT}" "${STORYFORGE_COVER_ROOT}"

# ---------- TCP 探测能力（/dev/tcp 是 bash 内建，Git Bash 等环境可能不可用） ----------
tcp_open() {
    (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null
}

TCP_OK=0
if tcp_open 127.0.0.1 9 2>/dev/null; then
    TCP_OK=1
else
    probe_err="$( { exec 3<>/dev/tcp/127.0.0.1/9; } 2>&1 || true )"
    case "${probe_err}" in
        *"No such file"*|*"not supported"*) TCP_OK=0 ;;
        *) TCP_OK=1 ;;
    esac
fi
if [ "${TCP_OK}" -eq 0 ]; then
    warn "当前 shell 不支持 /dev/tcp，跳过端口占用检查与数据库就绪等待"
fi

# 端口占用检查
if [ "${TCP_OK}" -eq 1 ] && tcp_open 127.0.0.1 "${SERVER_PORT}"; then
    die "端口 ${SERVER_PORT} 已被占用，请先停止占用进程或改用 --port 指定其他端口"
fi

# ---------- 等数据库 ----------
# SQLite 是嵌入式库，没有监听端口，等下去只会超时
case "${SPRING_PROFILES_ACTIVE}" in
    *sqlite*)
        WAIT_DB=0
        step "SQLite 为嵌入式数据库，跳过端口就绪等待"
        ;;
esac

if [ "${WAIT_DB}" -eq 1 ] && [ "${TCP_OK}" -eq 1 ]; then
    step "等待数据库 ${DB_HOST}:${DB_PORT}"
    attempt=1
    until tcp_open "${DB_HOST}" "${DB_PORT}"; do
        if [ "${attempt}" -ge 30 ]; then
            die "等待数据库超时（60s）。请确认 PostgreSQL 已启动，或核对 DB_HOST/DB_PORT"
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    ok "数据库已就绪"
fi

# ---------- 启动 ----------
step "启动 StoryForge"
info "profile = ${SPRING_PROFILES_ACTIVE}   端口 = ${SERVER_PORT}"
info "数据库  = ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
info "文件目录 = ${STORYFORGE_FILE_ROOT}"
info "封面目录 = ${STORYFORGE_COVER_ROOT}"
info "JVM 参数 = ${JAVA_OPTS}"
ok "访问地址 = http://127.0.0.1:${SERVER_PORT}/"
printf '\n'

# shellcheck disable=SC2086
exec java ${JAVA_OPTS} \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai \
    -jar "${JAR_PATH}" \
    --server.port="${SERVER_PORT}"
