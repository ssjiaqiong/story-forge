#!/usr/bin/env bash
# =============================================================================
# StoryForge 远程部署脚本（Linux / macOS / Windows Git Bash）
#
#   用法：
#     ./script/deploy/deploy.sh                 # 构建 → 上传 → 远端 compose 启动 → 健康检查
#     ./script/deploy/deploy.sh --skip-build    # 复用已有的 target/app.jar（仅更新部署）
#     ./script/deploy/deploy.sh --from-registry # 从 TCR 拉取镜像部署（先跑 build.sh 推送）
#     ./script/deploy/deploy.sh status          # 查看容器状态与健康
#     ./script/deploy/deploy.sh logs [行数]      # 查看应用日志（默认 200 行）
#     ./script/deploy/deploy.sh restart         # 重启应用容器
#     ./script/deploy/deploy.sh stop            # 停止（保留数据）
#     ./script/deploy/deploy.sh psql            # 进入 PostgreSQL 交互终端
#     ./script/deploy/deploy.sh --help
#
#   配置：同目录 server.env（HOST / USER / KEY / REMOTE_DIR / APP_PORT / 镜像仓库）
#   首次部署自动生成 .env（含随机密钥）；再次部署保留远端 .env 不覆盖。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CONFIG="${SCRIPT_DIR}/server.env"
REMOTE_DIR_DEFAULT=/opt/storyforge

# ---------- 输出 ----------
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_STEP=$'\033[1;34m'
else
    C_RESET=''; C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_STEP=''
fi
step() { echo "${C_STEP}==> $*${C_RESET}"; }
info() { echo "${C_INFO}$*${C_RESET}"; }
ok()   { echo "${C_OK}$*${C_RESET}"; }
warn() { echo "${C_WARN}$*${C_RESET}"; }
err()  { echo "${C_ERR}$*${C_RESET}" >&2; }
die()  { err "错误：$*"; exit 1; }

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

# ---------- 参数 ----------
ACTION="deploy"
SKIP_BUILD=0
FROM_REGISTRY=0
IMAGE_OVERRIDE="${APP_IMAGE:-}"
LOG_LINES=200
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)    SKIP_BUILD=1; shift ;;
        --from-registry) FROM_REGISTRY=1; shift ;;
        --image)         [ $# -ge 2 ] || die "--image 缺少参数"; IMAGE_OVERRIDE="$2"; shift 2 ;;
        --help|-h)    usage ;;
        logs)         ACTION="logs"; shift
                      [ $# -gt 0 ] && { LOG_LINES="$1"; shift; } ;;
        status|restart|stop|psql) ACTION="$1"; shift ;;
        *) die "未知参数：$1（--help 查看用法）" ;;
    esac
done

# ---------- 读配置 ----------
[ -f "$CONFIG" ] || die "缺少配置文件 $CONFIG（可参考 server.env.example 创建）"
# shellcheck disable=SC1090
. "$CONFIG"

HOST="${HOST:?server.env 缺少 HOST}"
SSH_USER="${USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
KEY="${KEY:-}"
REMOTE_DIR="${REMOTE_DIR:-$REMOTE_DIR_DEFAULT}"
APP_PORT="${APP_PORT:-8080}"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx640m}"

# ---------- 镜像仓库配置（--from-registry 使用） ----------
REGISTRY="${REGISTRY:-ccr.ccs.tencentyun.com}"
IMAGE_NAME="${IMAGE_NAME:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
APP_IMAGE="${IMAGE_OVERRIDE:-}"
if [ -z "$APP_IMAGE" ] && [ -n "$IMAGE_NAME" ]; then
    APP_IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
fi
if [ "$FROM_REGISTRY" -eq 1 ]; then
    [ -n "$APP_IMAGE" ] || die "--from-registry 需要在 server.env 配置 IMAGE_NAME/IMAGE_TAG，或用 --image 指定完整镜像地址"
fi

# KEY 支持相对 server.env 所在目录的路径
if [ -n "$KEY" ] && [[ "$KEY" != /* ]] && [[ ! "$KEY" =~ ^[A-Za-z]: ]]; then
    KEY="${SCRIPT_DIR}/${KEY}"
fi
[ -z "$KEY" ] || [ -f "$KEY" ] || die "SSH 私钥不存在：$KEY"

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new
          -o PasswordAuthentication=no -p "$SSH_PORT")
SCP_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new
          -o PasswordAuthentication=no -P "$SSH_PORT")
if [ -n "$KEY" ]; then
    SSH_OPTS+=(-i "$KEY")
    SCP_OPTS+=(-i "$KEY")
fi

rsh()  { ssh "${SSH_OPTS[@]}" "${SSH_USER}@${HOST}" "$@"; }
rcp()  { scp -q "${SCP_OPTS[@]}" "$@"; }

case "$ACTION" in
# =============================================================================
status)
    step "远端状态 ${SSH_USER}@${HOST}:${REMOTE_DIR}"
    rsh "cd ${REMOTE_DIR} && docker compose ps && echo '--- health ---' && (curl -sf http://127.0.0.1:${APP_PORT}/actuator/health || echo 'health: 不可用') && echo '--- disk/mem ---' && df -h / | tail -1 && free -m | head -2"
    ;;
# =============================================================================
logs)
    step "应用日志（最近 ${LOG_LINES} 行）"
    rsh "cd ${REMOTE_DIR} && docker compose logs --tail=${LOG_LINES} app"
    ;;
# =============================================================================
restart)
    step "重启应用"
    rsh "cd ${REMOTE_DIR} && docker compose restart app && sleep 3 && docker compose ps"
    ok "重启完成"
    ;;
# =============================================================================
stop)
    step "停止服务（数据保留在 ${REMOTE_DIR}/data）"
    rsh "cd ${REMOTE_DIR} && docker compose down"
    ok "已停止"
    ;;
# =============================================================================
psql)
    step "进入 PostgreSQL（\\q 退出）"
    rsh "cd ${REMOTE_DIR} && docker compose exec postgres psql -U \${DB_USER:-storyforge} -d \${DB_NAME:-storyforge}"
    ;;
# =============================================================================
deploy)
    # ---------- 1. 构建 ----------
    if [ "$FROM_REGISTRY" -eq 1 ]; then
        step "镜像仓库模式：跳过本地构建与 jar 上传"
        info "镜像：${APP_IMAGE}"
    elif [ "$SKIP_BUILD" -eq 1 ]; then
        [ -f "${APP_HOME}/target/app.jar" ] || die "--skip-build 需要已有 target/app.jar，先运行 ./script/build.sh"
        warn "跳过构建，使用现有 target/app.jar（$(date -r "${APP_HOME}/target/app.jar" '+%F %T' 2>/dev/null || echo 时间未知)）"
    else
        step "构建（复用 script/build.sh）"
        bash "${SCRIPT_DIR}/../build.sh" --skip-typecheck
        [ -f "${APP_HOME}/target/app.jar" ] || die "构建后未找到 target/app.jar"
    fi

    if [ "$FROM_REGISTRY" -eq 0 ]; then
        JAR_SIZE=$(du -h "${APP_HOME}/target/app.jar" | cut -f1)
        info "app.jar = ${JAR_SIZE}"
    fi

    # ---------- 2. 准备上传包 ----------
    step "准备部署包"
    STAGING="${APP_HOME}/target/deploy-staging"
    rm -rf "$STAGING" 2>/dev/null || true
    mkdir -p "$STAGING"
    if [ "$FROM_REGISTRY" -eq 0 ]; then
        cp "${APP_HOME}/target/app.jar" "$STAGING/app.jar"
    fi
    cp "${SCRIPT_DIR}/remote/Dockerfile" "$STAGING/Dockerfile"
    cp "${SCRIPT_DIR}/remote/docker-compose.yml" "$STAGING/docker-compose.yml"
    cp "${APP_HOME}/docker/start.sh" "$STAGING/start.sh"

    # ---------- 3. 远端目录与 .env（只在首次生成，避免覆盖已改密码/密钥） ----------
    step "同步到 ${SSH_USER}@${HOST}:${REMOTE_DIR}"
    rsh "mkdir -p ${REMOTE_DIR}/data"
    if rsh "test -f ${REMOTE_DIR}/.env"; then
        info "远端已存在 .env，保留不覆盖（密钥不可随意更换）"
    else
        step "首次部署：生成 .env（随机密钥）"
        JWT_SECRET=$(rsh "openssl rand -base64 48 | tr -d '\\n'")
        SECRET_KEY=$(rsh "openssl rand -base64 48 | tr -d '\\n'")
        DB_PASSWORD=$(rsh "openssl rand -base64 18 | tr -dc 'a-zA-Z0-9' | head -c 24")
        ADMIN_PASSWORD="$(rsh "openssl rand -base64 9 | tr -dc 'a-zA-Z0-9' | head -c 12")Aa1!"
        cat > "$STAGING/.env" <<EOF
APP_PORT=${APP_PORT}
DB_NAME=storyforge
DB_USER=storyforge
DB_PASSWORD=${DB_PASSWORD}
STORYFORGE_JWT_SECRET=${JWT_SECRET}
STORYFORGE_SECRET_KEY=${SECRET_KEY}
STORYFORGE_ADMIN_USERNAME=admin
STORYFORGE_ADMIN_PASSWORD=${ADMIN_PASSWORD}
STORYFORGE_ADMIN_FORCE_CHANGE=false
JAVA_OPTS=${JAVA_OPTS}
EOF
        chmod 600 "$STAGING/.env" 2>/dev/null || true
        rcp "$STAGING/.env" "${SSH_USER}@${HOST}:${REMOTE_DIR}/.env"
        rsh "chmod 600 ${REMOTE_DIR}/.env"
        ok "已生成 .env"
        warn "=============================================================="
        warn "初始管理员：admin / ${ADMIN_PASSWORD}"
        warn "（仅显示这一次，请立即保存并登录后修改）"
        warn "=============================================================="
    fi

    # ---------- 4. 上传 ----------
    if [ "$FROM_REGISTRY" -eq 1 ]; then
        rcp "$STAGING/Dockerfile" "$STAGING/docker-compose.yml" "$STAGING/start.sh" \
            "${SSH_USER}@${HOST}:${REMOTE_DIR}/"
    else
        rcp "$STAGING/app.jar" "$STAGING/Dockerfile" "$STAGING/docker-compose.yml" "$STAGING/start.sh" \
            "${SSH_USER}@${HOST}:${REMOTE_DIR}/"
    fi

    # ---------- 5. 腾讯云镜像加速（首次） ----------
    if ! rsh "grep -q mirror.ccs.tencentyun.com /etc/docker/daemon.json 2>/dev/null"; then
        step "配置 Docker 镜像加速（腾讯云内网源）"
        rsh "mkdir -p /etc/docker && printf '{\"registry-mirrors\":[\"https://mirror.ccs.tencentyun.com\",\"https://docker.m.daocloud.io\"]}' > /etc/docker/daemon.json && (systemctl restart docker || service docker restart) && sleep 2" || warn "镜像加速配置失败，继续尝试直连"
    fi

    # ---------- 6. 构建镜像并启动 ----------
    if [ "$FROM_REGISTRY" -eq 1 ]; then
        step "从镜像仓库拉取并启动（docker compose pull → up --no-build）"
        rsh "cd ${REMOTE_DIR} && APP_IMAGE='${APP_IMAGE}' docker compose pull app"
        rsh "cd ${REMOTE_DIR} && APP_IMAGE='${APP_IMAGE}' docker compose up -d --no-build"
    else
        step "远端构建镜像并启动（compose up -d --build）"
        rsh "cd ${REMOTE_DIR} && docker compose up -d --build"
    fi

    # ---------- 7. 健康检查 ----------
    step "健康检查"
    HEALTH_URL="http://127.0.0.1:${APP_PORT}/actuator/health"
    for i in $(seq 1 40); do
        if H=$(rsh "curl -sf ${HEALTH_URL}" 2>/dev/null); then
            ok "部署成功：${H}"
            break
        fi
        [ "$i" -eq 40 ] && die "健康检查超时，请执行 $0 logs 查看原因"
        sleep 3
    done

    EXT_IP="$HOST"
    ok "--------------------------------------------------------------"
    ok "  内网访问：http://127.0.0.1:${APP_PORT}"
    ok "  公网访问：http://${EXT_IP}:${APP_PORT}"
    ok "  部署目录：${REMOTE_DIR}（数据在 ${REMOTE_DIR}/data）"
    ok "  管理命令：$0 status | logs | restart | stop | psql"
    ok "--------------------------------------------------------------"
    warn "提示：若公网无法访问，请在腾讯云轻量服务器控制台「防火墙」放行 TCP ${APP_PORT}"
    rm -rf "$STAGING" 2>/dev/null || true
    ;;
esac
