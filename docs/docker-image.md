# 打成单个镜像（前端 + 后端）

一个镜像、一个容器、一个进程、一个端口：前端构建产物由 Spring Boot 直接托管，不需要 Nginx，也不带数据库。

## 1. 构建

```bash
# 仓库根目录执行
docker build -f docker/Dockerfile -t keel/keel-admin:1.0.0 .
```

首次约 5~10 分钟（容器内装前端依赖 + Maven 依赖）。产物约 300 MB（含 JRE）。

国内拉不动官方基础镜像时，换成镜像站：

```bash
docker build -f docker/Dockerfile -t keel/keel-admin:1.0.0 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
  --build-arg NODE_IMAGE=docker.m.daocloud.io/library/node:22-alpine \
  .
```

## 2. 运行

数据库不打包在镜像里，指向你已有的 MySQL / MariaDB：

```bash
docker run -d --name keel --restart unless-stopped \
  -p 8080:8080 \
  -e JWT_SECRET="$(openssl rand -hex 32)" \
  -e DB_HOST=192.168.1.10 -e DB_PORT=3306 -e DB_NAME=scaffold_system \
  -e DB_USERNAME=scaffold -e DB_PASSWORD='数据库密码' \
  -e DB_TIMEZONE="+08:00" \
  keel/keel-admin:1.0.0
```

浏览器直接访问 `http://服务器IP:8080`，登录页和接口同源，不需要再配跨域或反代。

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | 是 | ≥32 字节，不配或太短启动即失败 |
| `DB_HOST` / `DB_PORT` | 是 | 数据库地址；容器里不能用 `127.0.0.1`（那是容器自己） |
| `DB_NAME` | 否 | 默认 `scaffold_system` |
| `DB_USERNAME` / `DB_PASSWORD` | 是 | 数据库账号 |
| `DB_TIMEZONE` | 否 | 默认 `+08:00` |
| `APP_STATIC_DIR` | 否 | 镜像内已设为 `/app/static`，指向前端产物目录 |
| `SCAFFOLD_CACHE` | 否 | `local`（默认，内存）或 `redis` |
| `JAVA_OPTS` | 否 | 默认 `-XX:MaxRAMPercentage=75`，小内存可下调 |

Windows / macOS 上连**宿主机**的数据库时，`DB_HOST=host.docker.internal`；Linux 上用宿主机网桥 IP（`ip addr show docker0`）或 `--network host`。

## 3. 镜像里是怎么合起来的

```
① node:22-alpine      npm ci + npm run build        → /web/dist
② maven:3.9-temurin   mvn package -pl scaffold-admin -am → scaffold-admin.jar
③ temurin:17-jre      拷入 app.jar 与 dist（→ /app/static），ENTRYPOINT 只跑 java
```

后端新增了一个配置类 `SpaStaticConfig`，只在 `app.static-dir` 有值时才生效：

- 静态文件从 `APP_STATIC_DIR` 目录提供；
- 找不到的路径回落到 `index.html`（vue-router history 模式，刷新子页面不会 404）；
- `/api/**` 不参与回落，接口该 404 就 404，不会被 HTML 吞掉。

同时 `SecurityConfig` 里把授权规则收敛为「`/api/**` 需要登录，其余放行」——否则静态资源和深链接会被 401 拦在门外。业务接口全部在 `/api` 下，鉴权范围没有缩小。

本地开发完全不受影响：不设 `APP_STATIC_DIR` 时这套逻辑一行都不执行，前端照旧走 Vite。

## 4. 常用操作

```bash
docker logs -f keel                     # 日志（只有一个进程，看这一个就够）
docker inspect --format '{{.State.Health.Status}}' keel   # healthy / unhealthy
docker exec -it keel sh                 # 进容器
docker save keel/keel-admin:1.0.0 | gzip > keel-admin-1.0.0.tar.gz   # 导出到别的机器
docker load < keel-admin-1.0.0.tar.gz
```

更新版本：重新 `docker build` 出新 tag → 停旧容器 → 起新容器。数据库不动，数据不会丢。

## 5. 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动即退出，日志 `JWT_SECRET 必须配置为至少 32 字节` | 没传或太短 |
| `Communications link failure` / 连不上库 | `DB_HOST` 写成了 `127.0.0.1`；容器里指向的是自己 |
| 打开首页 404 | 确认容器里 `/app/static/index.html` 存在（`docker exec keel ls /app/static`），并为容器设置了 `APP_STATIC_DIR` |
| 刷新子页面 404 | 同上一行；深链接靠 `SpaStaticConfig` 回落 index.html |
| 构建卡在下载依赖 | 用第 1 节的 `--build-arg` 换镜像站；Maven 已在 `docker/maven-settings.xml` 里配了阿里云 |
| 内存吃紧 | 加 `--memory=1g` 并把 `JAVA_OPTS` 的 `MaxRAMPercentage` 降到 60 |
| 想放到 80 + 域名 | 前面套 Nginx/Caddy 反代 `8080`，镜像不用改 |
