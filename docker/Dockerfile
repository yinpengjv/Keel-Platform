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

# ip2region 离线库（xdb）随 jar 一起打包，运行时无需联网、无外部服务依赖。
# 二进制共约 48MB，不适合入库，因此 .gitignore 排除了它：克隆代码后本地默认没有。
# 这里做兜底——本地已有且完整就直接用（COPY 已带进来），否则构建时在线拉取；
# 拉取失败也不中断构建，应用会回落为「内网/未知」归属地，不影响启动与业务功能。
#
# 必须校验文件大小：下载中断产生的残缺文件不会报错，却会让约 1/3 的 IPv6
# 查询读越界（IndexOutOfBoundsException），归属地静默变成「未知」。
RUN mkdir -p scaffold-system/scaffold-framework/src/main/resources/ip2region && \
    for spec in "ip2region_v4.xdb:11122714" "ip2region_v6.xdb:37277813"; do \
      f=${spec%%:*}; expect=${spec##*:}; \
      d=scaffold-system/scaffold-framework/src/main/resources/ip2region/$f; \
      if [ -f "$d" ] && [ "$(stat -c%s "$d")" = "$expect" ]; then \
        echo ">>> $f 已存在且完整，跳过下载"; continue; \
      fi; \
      echo ">>> 缺少 $f（或大小不符），构建时在线拉取 ip2region 离线库"; \
      rm -f "$d"; \
      for base in \
        "https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data" \
        "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data" \
        "https://gh-proxy.com/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data" \
        "https://gitee.com/lionsoul/ip2region/raw/master/data"; do \
        if curl -fsSL --connect-timeout 15 --max-time 600 -o "$d" "$base/$f"; then \
          if [ "$(stat -c%s "$d")" = "$expect" ]; then \
            echo ">>> $f 下载完成（完整性校验通过）"; break; \
          fi; \
          echo ">>> $f 大小不符，可能下载不完整，换源重试"; rm -f "$d"; \
        fi; \
      done; \
      if [ ! -f "$d" ]; then echo ">>> $f 下载失败，归属地功能将回落为内网/未知"; fi; \
    done

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
