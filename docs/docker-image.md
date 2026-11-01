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
  --log-opt max-size=50m --log-opt max-file=3 \
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
| `ACCESS_LOG_ENABLED` | 否 | 默认 `true`，每个请求一行访问日志；设 `false` 关闭 |
| `ACCESS_LOG_SLOW_MS` | 否 | 默认 `1000`，超过该耗时的请求记 warn 并标 `[慢请求]` |
| `SQL_LOG_LEVEL` | 否 | 默认 `debug`，打印每条 SQL 与参数；设 `info` 关闭 |
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

## 5. 看日志

容器里只有一个进程，`docker logs` 就是全部输出。启动后默认有两类业务日志：

```bash
docker logs -f keel                                # 实时跟踪
docker logs --tail 100 keel                        # 最近 100 行
docker logs keel 2>&1 | grep scaffold.access       # 只看访问日志
docker logs keel 2>&1 | grep "==> Parameters"      # 只看 SQL 参数
```

每行都带 traceId，同一个请求的访问日志和它触发的所有 SQL 共用一个值：

```
2026-11-01 21:58:55.441 DEBUG [320e9094...] c.s.s.m.S.selectUsers : ==>  Preparing: SELECT ...
2026-11-01 21:58:55.444 DEBUG [320e9094...] c.s.s.m.S.selectUsers : ==> Parameters: null, 0, 2
2026-11-01 21:58:55.459 INFO  [320e9094...] scaffold.access       : GET /api/system/users?pageNum=1&pageSize=2 200 228ms user=admin ip=172.17.0.1
```

追一次请求：`docker logs keel 2>&1 | grep 320e9094`。

| 日志 | 内容 | 关闭方式 |
| --- | --- | --- |
| 访问日志 | 方法、路径、状态码、耗时、登录用户、来源 IP；≥500 记 error，超过 1s 记 warn 并标 `[慢请求]` | `ACCESS_LOG_ENABLED=false` |
| SQL 日志 | 每条 SQL、参数、影响行数（DEBUG 级） | `SQL_LOG_LEVEL=info` |
| 应用日志 | 启动、连接池、未捕获异常堆栈 | — |

静态资源（`/assets/*`、`*.js` 等后缀）与 `/api/health` 不记访问日志，否则前端 chunk 和容器探针会把日志刷满。

SQL 参数是真实字段值，新增或修改用户时密码也会出现在日志里。生产长期开启前先评估，或连同访问日志一起关掉，改用系统内的「操作日志」「登录日志」页。

json-file 驱动不加限制会一直增长，起容器时带上轮转参数（第 2 节的命令已包含）：

```bash
--log-opt max-size=50m --log-opt max-file=3
```

访问日志由 `RequestAccessLogFilter` 输出（logger 名 `scaffold.access`），SQL 日志靠 `application-dev.yml` 里把 mapper 的 logger 调到 debug。两者都只在服务端，前端浏览器控制台看不到。

## 6. 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动即退出，日志 `JWT_SECRET 必须配置为至少 32 字节` | 没传或太短 |
| `Communications link failure` / 连不上库 | `DB_HOST` 写成了 `127.0.0.1`；容器里指向的是自己 |
| 打开首页 404 | 确认容器里 `/app/static/index.html` 存在（`docker exec keel ls /app/static`），并为容器设置了 `APP_STATIC_DIR` |
| 刷新子页面 404 | 同上一行；深链接靠 `SpaStaticConfig` 回落 index.html |
| 构建卡在下载依赖 | 用第 1 节的 `--build-arg` 换镜像站；Maven 已在 `docker/maven-settings.xml` 里配了阿里云 |
| 内存吃紧 | 加 `--memory=1g` 并把 `JAVA_OPTS` 的 `MaxRAMPercentage` 降到 60 |
| 想放到 80 + 域名 | 前面套 Nginx/Caddy 反代 `8080`，镜像不用改 |
| `docker logs` 只有启动几行，操作时没动静 | 确认没有设 `ACCESS_LOG_ENABLED=false`、`SQL_LOG_LEVEL=info`；另外查询类接口不写 `sys_oper_log`，系统内看不到属正常 |
