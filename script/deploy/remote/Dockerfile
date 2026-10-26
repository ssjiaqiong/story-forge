# =============================================================================
# StoryForge 运行时镜像（单镜像：前端 + 后端一体，前端已打进 jar 的 static）
#
#   容器内不编译：只装入已构建好的 app.jar，构建上下文必须包含
#     app.jar      —— script/build.sh 的产物
#     start.sh     —— docker/start.sh（等待数据库 + 启动 java）
#     Dockerfile   —— 本文件
#
#   两种用法：
#   1) 源码上传后远端 compose 构建（script/deploy/deploy.sh）
#        docker compose up -d --build
#   2) 构建镜像并推送到腾讯云 TCR（script/deploy/build.sh）
#        docker build --build-arg APP_VERSION=v1.0.0 \
#          -t ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.0 .
#        docker push ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.0
# =============================================================================
FROM eclipse-temurin:17-jre

# ---- 构建参数（写入 OCI 标签，便于在 TCR 控制台识别版本） ----
ARG APP_VERSION=dev
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown

WORKDIR /app

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    STORYFORGE_FILE_ROOT=/data/files \
    STORYFORGE_COVER_ROOT=/data/covers \
    LOG_PATH=/app/logs \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Xms128m -Xmx640m"

LABEL org.opencontainers.image.title="StoryForge" \
      org.opencontainers.image.description="StoryForge 智能小说创作平台（前后端一体单镜像）" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.vendor="jiaqiong" \
      org.opencontainers.image.source="ccr.ccs.tencentyun.com/jiaqiong/story-forge"

COPY app.jar /app/app.jar
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh \
 && mkdir -p /data/files /data/covers /app/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD sh -c 'wget -qO- http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health >/dev/null 2>&1 || exit 1'

ENTRYPOINT ["/app/start.sh"]
