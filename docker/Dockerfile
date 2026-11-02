# ============================================================
# 龙骨中台 · 单镜像（前端 + 后端打在一起）
#
#   构建：docker build -f docker/Dockerfile -t keel/keel-admin:1.1.0 --build-arg KEEL_VERSION=1.1.0 .
#   运行：docker run -d -p 8080:8080 -e JWT_SECRET=... -e DB_HOST=... keel/keel-admin:1.1.0
#
# 版本号以项目根 VERSION 文件为唯一来源：前端界面、后端 /api/health 与镜像 tag 三处同源。
#
# 镜像里只有一个进程：Spring Boot。前端静态文件由后端托管（APP_STATIC_DIR），
# 所以不需要 Nginx，也没有多进程守护。
# ============================================================

# 基础镜像可覆盖：国内拉不动官方 tag 时用 --build-arg 换成镜像站地址
#   --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy
ARG NODE_IMAGE=node:22-alpine
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre-jammy

# ---------- ① 前端构建 ----------
FROM ${NODE_IMAGE} AS web

WORKDIR /web

COPY scaffold-vue/package.json scaffold-vue/package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com --no-audit --no-fund

COPY scaffold-vue ./
# 单一版本源：vite.config.ts 从 /VERSION 读取版本号注入前端。
# 注意 COPY 的目标路径相对 WORKDIR（/web），这里用绝对路径 /VERSION，前端才能读到。
COPY VERSION /VERSION
# 接口地址用相对路径 /api，与后端同源，不需要配置后端域名
RUN npm run build

# ---------- ② 后端构建 ----------
FROM ${MAVEN_IMAGE} AS api

WORKDIR /src

COPY docker/maven-settings.xml /root/.m2/settings.xml

# 先只拷 pom，把「下依赖」单独成层
COPY scaffold-system/pom.xml scaffold-system/pom.xml
COPY scaffold-system/scaffold-common/pom.xml scaffold-system/scaffold-common/pom.xml
COPY scaffold-system/scaffold-framework/pom.xml scaffold-system/scaffold-framework/pom.xml
COPY scaffold-system/scaffold-system/pom.xml scaffold-system/scaffold-system/pom.xml
COPY scaffold-system/scaffold-generator/pom.xml scaffold-system/scaffold-generator/pom.xml
COPY scaffold-system/scaffold-admin/pom.xml scaffold-system/scaffold-admin/pom.xml

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -f scaffold-system/pom.xml -pl scaffold-admin -am -DskipTests \
        dependency:go-offline || true

COPY scaffold-system scaffold-system
# 单一版本源：VERSION 放在项目根，需一并拷贝（目标相对 WORKDIR /src，即 /src/VERSION），
# scaffold-admin 的 pom 会把它打进 classpath:/keel/VERSION
COPY VERSION VERSION

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -f scaffold-system/pom.xml -pl scaffold-admin -am -DskipTests package

# ---------- ③ 运行 ----------
FROM ${JRE_IMAGE} AS runtime

# 应用版本号：与项目根 VERSION 文件同源，构建时用 --build-arg KEEL_VERSION=1.1.0 传入
ARG KEEL_VERSION=dev

LABEL org.opencontainers.image.title="keel-admin" \
      org.opencontainers.image.description="龙骨中台管理系统（前端 + 后端单镜像）" \
      org.opencontainers.image.version="${KEEL_VERSION}"

# curl 供 HEALTHCHECK 用；tzdata 保证时区；非 root 运行
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r keel \
 && useradd -r -g keel -m -d /app -s /usr/sbin/nologin keel

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    APP_STATIC_DIR=/app/static \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 后端可执行 jar（同目录的 .jar.original 不要）
COPY --from=api --chown=keel:keel \
     /src/scaffold-system/scaffold-admin/target/scaffold-admin-0.1.0-SNAPSHOT.jar /app/app.jar

# 前端构建产物，由后端按 APP_STATIC_DIR 托管
COPY --from=web --chown=keel:keel /web/dist /app/static

USER keel

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
