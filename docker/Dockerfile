# =============================================================================
# StoryForge 单镜像构建
#   阶段 1：Node 构建前端（Vue 3 + Vite）
#   阶段 2：Maven 构建后端（把前端产物打进 classpath:/static）
#   阶段 3：JRE 运行，入口为 start.sh
# 构建上下文为仓库根目录：
#   docker build -f docker/Dockerfile -t storyforge:latest .
# =============================================================================

# ---------- 阶段 1：构建前端 ----------
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---------- 阶段 2：Maven 构建后端 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
# 前端产物置于后端静态资源目录，实现前后端同源单端口
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -DskipTests clean package

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    STORYFORGE_FILE_ROOT=/data/files \
    STORYFORGE_COVER_ROOT=/data/covers \
    LOG_PATH=/app/logs \
    JAVA_OPTS="-Xms256m -Xmx1024m"

LABEL org.opencontainers.image.title="StoryForge" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://gitee.com/jiaqiong/story-forge"

COPY --from=build /src/target/app.jar /app/app.jar
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh && mkdir -p /data/files /data/covers /app/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD sh -c 'wget -qO- http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health >/dev/null 2>&1 || exit 1'

ENTRYPOINT ["/app/start.sh"]
