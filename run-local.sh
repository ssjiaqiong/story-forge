#!/usr/bin/env bash
# 本地单端口启动脚本（开发用）
cd "$(dirname "$0")"
export DB_HOST=127.0.0.1
export DB_PORT=5432
export DB_NAME=storyforge
export DB_USER=postgres
export DB_PASSWORD=postgres
export STORYFORGE_JWT_SECRET=storyforge-local-dev-secret-key-32bytes-minimum-2026
export STORYFORGE_SECRET_KEY=storyforge-local-crypto-secret-2026
export SERVER_PORT=8080
exec java -jar target/app.jar
