#!/usr/bin/env bash
# =============================================================================
# StoryForge 容器启动脚本
#   1. 等待 PostgreSQL 就绪（Flyway 迁移需要数据库可连接）
#   2. 校验关键环境变量
#   3. 启动前后端一体的 Spring Boot 服务
# =============================================================================
set -euo pipefail

log() {
  echo "[start] $(date '+%Y-%m-%d %H:%M:%S') $*"
}

DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
SERVER_PORT="${SERVER_PORT:-8080}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-60}"

# ---------- 关键变量校验 ----------
: "${STORYFORGE_JWT_SECRET:?环境变量 STORYFORGE_JWT_SECRET 未设置（长度需 >= 32 字节）}"

if [ "${#STORYFORGE_JWT_SECRET}" -lt 32 ]; then
  log "ERROR: STORYFORGE_JWT_SECRET 长度不足 32 字节，拒绝启动"
  exit 1
fi

if [ "${STORYFORGE_REGISTRATION_ENABLED:-false}" != "false" ]; then
  log "WARN: 需求约定关闭自助注册，已忽略 STORYFORGE_REGISTRATION_ENABLED=${STORYFORGE_REGISTRATION_ENABLED}"
fi

log "file storage root = ${STORYFORGE_FILE_ROOT:-/data/files}"
log "cover storage root = ${STORYFORGE_COVER_ROOT:-/data/covers}"
mkdir -p "${STORYFORGE_FILE_ROOT:-/data/files}" "${STORYFORGE_COVER_ROOT:-/data/covers}" "${LOG_PATH:-/app/logs}"

# ---------- 等待数据库 ----------
log "waiting for database ${DB_HOST}:${DB_PORT} ..."
attempt=1
until (echo > /dev/tcp/"${DB_HOST}"/"${DB_PORT}") 2>/dev/null; do
  if [ "${attempt}" -ge "${WAIT_MAX_ATTEMPTS}" ]; then
    log "ERROR: 等待数据库超时（${WAIT_MAX_ATTEMPTS} 次），退出"
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 2
done
log "database is ready (after $((attempt - 1)) retries)"

# ---------- 启动服务 ----------
log "launching StoryForge on port ${SERVER_PORT} (profile=${SPRING_PROFILES_ACTIVE:-prod})"
exec java ${JAVA_OPTS:--Xms256m -Xmx1024m} \
  -Dserver.port="${SERVER_PORT}" \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -jar /app/app.jar
