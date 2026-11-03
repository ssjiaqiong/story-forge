#!/usr/bin/env bash
# =============================================================================
# StoryForge 镜像构建与推送脚本（腾讯云 TCR 个人版）
#
#   流程：打包 jar → 组装构建上下文 → docker build → docker tag → docker push
#   默认在**服务器**上构建并推送（registry 登录态、镜像层缓存都在服务器，
#   本机无需安装 Docker）。加 --local 则在本机 docker 构建推送。
#
#   用法：
#     ./script/deploy/build.sh                     # 构建 + 推送 v<pom.xml 版本>
#     ./script/deploy/build.sh --skip-jar          # 复用 target/app.jar，不重新打包
#     ./script/deploy/build.sh --tag v1.0.1        # 指定标签（默认 v + pom.xml 版本）
#     ./script/deploy/build.sh --also-latest       # 额外推送 latest
#     ./script/deploy/build.sh --no-push           # 只构建镜像，不推送
#     ./script/deploy/build.sh --no-cache          # 构建不使用缓存
#     ./script/deploy/build.sh --local             # 在本机 docker 构建（需已装 Docker）
#     ./script/deploy/build.sh login               # 在服务器上登录 TCR（密码从文件/环境变量读）
#     ./script/deploy/build.sh --print-version     # 只打印版本标签（v + pom.xml 版本）
#     ./script/deploy/build.sh --help
#
#   配置：同目录 server.env
#     REGISTRY / IMAGE_NAME / HOST / USER / KEY / REMOTE_DIR / REGISTRY_USER
#   版本：唯一来源是 pom.xml，标签默认 v<pom 版本>；server.env 的 IMAGE_TAG 可覆盖
#
#   推送后即可用镜像部署：./script/deploy/deploy.sh（默认拉镜像，不编译）
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CONFIG="${SCRIPT_DIR}/server.env"
SECRET_DIR="${APP_HOME}/../secret"

# 版本号唯一来源：pom.xml
# shellcheck source=../lib/version.sh
. "${SCRIPT_DIR}/../lib/version.sh"

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
    awk 'NR <= 2 { next } /^# ={10,}$/ { exit } { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 0
}

# ---------- 参数 ----------
ACTION="build"
SKIP_JAR=0
NO_PUSH=0
ALSO_LATEST=0
NO_CACHE=0
USE_LOCAL=0
TAG_OVERRIDE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-jar)    SKIP_JAR=1; shift ;;
        --no-push)     NO_PUSH=1; shift ;;
        --also-latest) ALSO_LATEST=1; shift ;;
        --no-cache)    NO_CACHE=1; shift ;;
        --local)       USE_LOCAL=1; shift ;;
        --tag)         [ $# -ge 2 ] || die "--tag 缺少参数"; TAG_OVERRIDE="$2"; shift 2 ;;
        --print-version) printf 'v%s\n' "$(app_version)"; exit 0 ;;
        login)         ACTION="login"; shift ;;
        -h|--help)     usage ;;
        *) die "未知参数：$1（--help 查看用法）" ;;
    esac
done

# ---------- 读配置 ----------
[ -f "$CONFIG" ] || die "缺少配置文件 $CONFIG"
# shellcheck disable=SC1090
. "$CONFIG"

REGISTRY="${REGISTRY:-ccr.ccs.tencentyun.com}"
IMAGE_NAME="${IMAGE_NAME:?server.env 缺少 IMAGE_NAME（形如 <命名空间>/<仓库名>）}"
# 标签优先级：--tag > server.env 的 IMAGE_TAG > v + pom.xml 版本
IMAGE_TAG="${TAG_OVERRIDE:-${IMAGE_TAG:-v$(app_version)}}"
REPO="${REGISTRY}/${IMAGE_NAME}"
IMAGE="${REPO}:${IMAGE_TAG}"

HOST="${HOST:?server.env 缺少 HOST}"
SSH_USER="${USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
KEY="${KEY:-}"
REMOTE_DIR="${REMOTE_DIR:-/opt/storyforge}"
BUILD_DIR="${REMOTE_DIR}/image-build"
REGISTRY_USER="${REGISTRY_USER:-}"

# KEY 支持相对 server.env 所在目录的路径
if [ -n "$KEY" ] && [[ "$KEY" != /* ]] && [[ ! "$KEY" =~ ^[A-Za-z]: ]]; then
    KEY="${SCRIPT_DIR}/${KEY}"
fi
if [ -n "$KEY" ] && [ ! -f "$KEY" ]; then
    die "SSH 私钥不存在：$KEY"
fi

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new
          -o PasswordAuthentication=no -p "$SSH_PORT")
SCP_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new
          -o PasswordAuthentication=no -P "$SSH_PORT")
if [ -n "$KEY" ]; then
    SSH_OPTS+=(-i "$KEY")
    SCP_OPTS+=(-i "$KEY")
fi
rsh() { ssh "${SSH_OPTS[@]}" "${SSH_USER}@${HOST}" "$@"; }
rcp() { scp -q "${SCP_OPTS[@]}" "$@"; }

# 仓库登录辅助：优先用 TCR_PASSWORD 环境变量 / secret/tcr-password.txt
registry_password() {
    if [ -n "${TCR_PASSWORD:-}" ]; then
        printf '%s' "${TCR_PASSWORD}"
        return 0
    fi
    if [ -f "${SECRET_DIR}/tcr-password.txt" ]; then
        tr -d '\r\n' < "${SECRET_DIR}/tcr-password.txt"
        return 0
    fi
    return 1
}

login_hint() {
    warn "认证失败：请先登录镜像仓库"
    warn "  1) 把 TCR 登录密码写入 ${SECRET_DIR}/tcr-password.txt（或设置 TCR_PASSWORD 环境变量）"
    warn "  2) 执行 ./script/deploy/build.sh login"
    warn "  3) 确认 TCR 控制台已创建命名空间 ${IMAGE_NAME%%/*} 与仓库 ${IMAGE_NAME##*/}"
}

# =============================================================================
# login：在服务器上登录 TCR
# =============================================================================
if [ "$ACTION" = "login" ]; then
    if [ -z "$REGISTRY_USER" ]; then
        die "server.env 缺少 REGISTRY_USER（腾讯云账号 ID）"
    fi
    step "登录 ${REGISTRY}（用户 ${REGISTRY_USER} → ${HOST}）"
    if PW="$(registry_password)"; then
        printf '%s' "$PW" | rsh "docker login ${REGISTRY} -u ${REGISTRY_USER} --password-stdin"
        ok "服务器已登录 ${REGISTRY}"
    else
        login_hint
        exit 1
    fi
    exit 0
fi

# =============================================================================
# build：打包 → 构建 → 推送
# =============================================================================
printf '%s\n' "=============================================="
printf '%s\n' " StoryForge 镜像构建"
printf '%s\n' " 镜像：${IMAGE}"
if [ "$USE_LOCAL" -eq 1 ]; then
    printf '%s\n' " 构建位置：本机 Docker"
else
    printf '%s\n' " 构建位置：服务器 ${SSH_USER}@${HOST}:${BUILD_DIR}"
fi
printf '%s\n' "=============================================="

# ---------- 1. 准备 jar ----------
JAR="${APP_HOME}/target/app.jar"
if [ "$SKIP_JAR" -eq 1 ]; then
    if [ ! -f "$JAR" ]; then
        die "--skip-jar 需要已有 target/app.jar，先运行 ./script/build.sh"
    fi
    warn "跳过构建，复用 target/app.jar（$(date -r "$JAR" '+%F %T' 2>/dev/null || echo 时间未知)）"
else
    step "构建 jar（复用 script/build.sh）"
    bash "${SCRIPT_DIR}/../build.sh" --skip-typecheck
    if [ ! -f "$JAR" ]; then
        die "构建后未找到 $JAR"
    fi
fi
JAR_SIZE="$(du -h "$JAR" | cut -f1 | tr -d ' ')"
ok "app.jar = ${JAR_SIZE}"

# ---------- 2. 组装构建上下文 ----------
step "组装构建上下文"
STAGING="${APP_HOME}/target/image-staging"
rm -rf "$STAGING" 2>/dev/null || true
mkdir -p "$STAGING"
cp "$JAR" "$STAGING/app.jar"
cp "${SCRIPT_DIR}/remote/Dockerfile" "$STAGING/Dockerfile"
cp "${APP_HOME}/docker/start.sh" "$STAGING/start.sh"
ok "上下文：app.jar + Dockerfile + start.sh"

BUILD_DATE="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
VCS_REF="$(cd "$APP_HOME" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BUILD_ARGS=(--build-arg "APP_VERSION=${IMAGE_TAG}"
            --build-arg "BUILD_DATE=${BUILD_DATE}"
            --build-arg "VCS_REF=${VCS_REF}")
TAGS=(-t "${IMAGE}")
if [ "$ALSO_LATEST" -eq 1 ] && [ "${IMAGE_TAG}" != "latest" ]; then
    TAGS+=(-t "${REPO}:latest")
fi
CACHE_ARG=""
if [ "$NO_CACHE" -eq 1 ]; then
    CACHE_ARG="--no-cache"
fi

# ---------- 3. 构建 ----------
if [ "$USE_LOCAL" -eq 1 ]; then
    command -v docker >/dev/null 2>&1 || die "本机未找到 docker；去掉 --local 改用服务器构建"
    step "本机构建镜像"
    # shellcheck disable=SC2086
    docker build ${CACHE_ARG} "${BUILD_ARGS[@]}" "${TAGS[@]}" "$STAGING"
    ok "本机构建完成：${IMAGE}"
else
    step "上传构建上下文到 ${HOST}"
    rsh "mkdir -p ${BUILD_DIR}"
    rcp "$STAGING/app.jar" "$STAGING/Dockerfile" "$STAGING/start.sh" "${SSH_USER}@${HOST}:${BUILD_DIR}/"
    ok "已上传（约 ${JAR_SIZE}）"

    step "服务器构建镜像"
    REMOTE_CMD="cd ${BUILD_DIR} && docker build ${CACHE_ARG} ${BUILD_ARGS[*]} ${TAGS[*]} ."
    rsh "$REMOTE_CMD"
    ok "服务器构建完成：${IMAGE}"
fi

# ---------- 4. 推送 ----------
if [ "$NO_PUSH" -eq 1 ]; then
    warn "已跳过推送（--no-push），镜像仅存在于构建机上：${IMAGE}"
    rm -rf "$STAGING" 2>/dev/null || true
    exit 0
fi

step "推送镜像到 ${REGISTRY}"
PUSH_TAGS=("${IMAGE}")
if [ "$ALSO_LATEST" -eq 1 ] && [ "${IMAGE_TAG}" != "latest" ]; then
    PUSH_TAGS+=("${REPO}:latest")
fi

# 用 skopeo 推送：分块上传 + 自动重试，TCR 上大层用 docker push 容易出现
# "net/http: timeout awaiting response headers"；没有 skopeo 时退回 docker push。
HAS_SKOPEO=0
if [ "$USE_LOCAL" -eq 1 ]; then
    if command -v skopeo >/dev/null 2>&1; then HAS_SKOPEO=1; fi
else
    if rsh "command -v skopeo >/dev/null 2>&1"; then HAS_SKOPEO=1; fi
fi
if [ "$HAS_SKOPEO" -eq 1 ]; then
    info "推送方式：skopeo（分块上传，失败自动重试）"
else
    warn "构建机上没有 skopeo，退回 docker push（大层可能超时；可 dnf install -y skopeo 后重试）"
fi

PUSH_LOG="${APP_HOME}/target/.docker-push.log"
: > "$PUSH_LOG"
for t in "${PUSH_TAGS[@]}"; do
    info "推送 ${t}"
    rc=0
    if [ "$HAS_SKOPEO" -eq 1 ]; then
        if [ "$USE_LOCAL" -eq 1 ]; then
            skopeo copy --retry-times 5 "docker-daemon:${t}" "docker://${t}" >"$PUSH_LOG" 2>&1 || rc=$?
        else
            rsh "skopeo copy --dest-authfile /root/.docker/config.json --retry-times 5 docker-daemon:${t} docker://${t}" >"$PUSH_LOG" 2>&1 || rc=$?
        fi
    else
        local_attempt=1
        while : ; do
            if [ "$USE_LOCAL" -eq 1 ]; then
                docker push "$t" >"$PUSH_LOG" 2>&1 || rc=$?
            else
                rsh "docker push ${t}" >"$PUSH_LOG" 2>&1 || rc=$?
            fi
            if [ "$rc" -eq 0 ] || [ "$local_attempt" -ge 3 ]; then
                break
            fi
            local_attempt=$((local_attempt + 1))
            warn "push 失败，第 ${local_attempt} 次重试…"
            sleep 5
        done
    fi
    if [ "$rc" -ne 0 ]; then
        tail -20 "$PUSH_LOG" | tr '\r' '\n' | sed 's/^/     /'
        AUTH_FAIL=0
        if grep -qiE 'unauthorized|denied|authentication required|no basic auth' "$PUSH_LOG"; then
            AUTH_FAIL=1
        fi
        rm -f "$PUSH_LOG" 2>/dev/null || true
        if [ "$AUTH_FAIL" -eq 1 ]; then
            login_hint
        fi
        die "推送失败（exit ${rc}）：${t}"
    fi
    tail -2 "$PUSH_LOG" | tr '\r' '\n' | sed 's/^/     /'
    ok "已推送 ${t}"
done
rm -f "$PUSH_LOG" 2>/dev/null || true

# ---------- 5. 校验与结果 ----------
DIGEST=""
if [ "$HAS_SKOPEO" -eq 1 ]; then
    if [ "$USE_LOCAL" -eq 1 ]; then
        DIGEST="$(skopeo inspect "docker://${IMAGE}" --format '{{.Digest}}' 2>/dev/null || true)"
    else
        DIGEST="$(rsh "skopeo inspect --authfile /root/.docker/config.json docker://${IMAGE} --format '{{.Digest}}'" 2>/dev/null || true)"
    fi
fi
if [ -z "$DIGEST" ]; then
    DIGEST="$(docker inspect --format '{{index .RepoDigests 0}}' "${IMAGE}" 2>/dev/null || true)"
fi

step "完成"
ok "镜像地址：${IMAGE}"
if [ -n "$DIGEST" ]; then
    info "digest：${DIGEST}"
fi
info "用镜像部署：./script/deploy/deploy.sh（默认）或 --image <地址>"
info "或在服务器手动：APP_IMAGE=${IMAGE} docker compose pull app && docker compose up -d --no-build"

rm -rf "$STAGING" 2>/dev/null || true
