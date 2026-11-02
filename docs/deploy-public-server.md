# 部署到公网服务器

本文只讲**公网服务器上线**这一件事。目标：服务器上只有 Docker，从一个镜像 tar 包跑起来。

> 核心结论：**不需要下载 ip2region 数据文件**。它已经打包在镜像里，
> `docker load` 之后直接 `docker compose up -d` 即可，全程无需联网构建。

---

## 0. 服务器需要什么

| 项 | 要求 |
| --- | --- |
| Docker | 20.10+ （`docker version` 能跑通） |
| Docker Compose | v2（`docker compose version`，注意是 `compose` 不是 `docker-compose`） |
| 内存 | 建议 ≥ 2GB（应用本身约 512MB，MySQL 约 400MB） |
| 磁盘 | ≥ 5GB 空闲（镜像解压后约 700MB） |
| 端口 | 8080 对外，其余不用开 |

**不需要** JDK、Node.js、Maven、MySQL 客户端 —— 镜像里都有。

---

## 1. 上传并导入镜像

交付包里的 `image/keel-admin-1.1.4.tar.gz` 就是成品镜像（约 160MB，gzip 压缩）。

```bash
# 传到服务器
scp keel-admin-1.1.4.tar.gz user@服务器IP:/opt/keel/

# 服务器上校验完整性（可选但建议）
cd /opt/keel
sha256sum -c SHA256SUMS

# 导入
docker load -i keel-admin-1.1.4.tar.gz
docker images | grep keel      # 应看到 keel/keel-admin:1.1.4
```

> `docker load` 之后镜像就在本地了，**之后再也不需要这个 tar 包**，可以删掉省磁盘。
> 想确认 xdb 确实在里面：
> `docker run --rm --entrypoint sh keel/keel-admin:1.1.4 -c "ls -la /app/app.jar"`
> 镜像内 jar 约 67MB —— 其中约 48MB 就是 ip2region 离线库。

---

## 2. 准备数据库

两种方式，二选一。

### 方式 A：用 compose 自带的 MySQL（推荐，图省事）

compose 文件里已经写好了一个 `mysql` 服务，数据落在**具名卷** `mysql-data`，
容器删了重建会自动挂回同一份数据，不会丢。

```bash
docker network create keel-net     # 只需执行一次
```

数据库口令在 `.env` 里配置，见下一步。

### 方式 B：用服务器上已有的 MySQL

```bash
mysql -h127.0.0.1 -uroot -p -e "CREATE DATABASE IF NOT EXISTS scaffold_system DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -h127.0.0.1 -uroot -p -e "CREATE USER IF NOT EXISTS 'scaffold'@'%' IDENTIFIED BY '你的强密码'; GRANT ALL PRIVILEGES ON scaffold_system.* TO 'scaffold'@'%'; FLUSH PRIVILEGES;"
```

然后在 `.env` 里把 `DB_HOST` 指向它（容器内不能用 `127.0.0.1`）：

| 数据库位置 | `DB_HOST` 填什么 |
| --- | --- |
| compose 里的 mysql 容器 | `mysql`（容器名，需在同一网络 `keel-net`） |
| 宿主机上的 MySQL | Linux 用宿主机网桥 IP（`ip addr show docker0`）；或 `host.docker.internal`（需 `--add-host=host.docker.internal:host-gateway`） |
| 另一台服务器 | 那台的公网/内网 IP |

---

## 3. 导入建表脚本

**只需执行一次**，全新数据库才需要。已有数据就不要重复导。

```bash
# 把源码包里的 sql 目录传上来，或从 GitHub clone
cd /opt/keel
for f in scaffold-system/sql/V*.sql; do
  docker exec -i mysql mysql -uroot -padmin \
    --default-character-set=utf8mb4 \
    --init-command="SET time_zone='+08:00'" scaffold_system < "$f"
done
```

两个参数别省：不加 `--default-character-set=utf8mb4` 会中文乱码；
不加 `--init-command` 会让 `create_time` 差 8 小时。

脚本按序号顺序执行，别跳：

| 文件 | 内容 |
| --- | --- |
| `V1__init_schema.sql` | 全部建表语句 |
| `V2__seed_data.sql` | 种子数据（含 `admin` 账号） |
| `V3__runtime_log.sql` | 运行日志表 |
| `V4__dynamic_params.sql` | 动态参数表 |
| `V5__oper_log_client.sql` | 操作日志补 IP/归属地/浏览器字段 |

数据库在别处时，把 `docker exec -i mysql` 换成宿主机 `mysql` 命令即可。

---

## 4. 配置环境变量

```bash
cd /opt/keel
cp .env.example .env
vi .env
```

**公网上线必须改的几项**：

```dotenv
# 应用镜像版本（与交付的 tar 包一致）
KEEL_IMAGE=keel/keel-admin:1.1.4

# 数据库（用 compose 自带 MySQL 时保持下面即可）
DB_PORT=3306
DB_NAME=scaffold_system
DB_USERNAME=root
DB_PASSWORD=换成强密码

# 应用
KEEL_PORT=8080
JWT_SECRET=用 openssl rand -hex 32 生成
TZ=Asia/Shanghai
```

生成 `JWT_SECRET`：

```bash
openssl rand -hex 32
```

> ⚠️ `JWT_SECRET` 必须 ≥32 字节，不配或太短**启动即失败**。这是设计如此，
> 防止用弱密钥签发令牌。**上线前务必换掉模板里的默认值。**

---

## 5. 启动

```bash
cd /opt/keel
docker compose up -d
docker compose ps          # 两个服务都应该是 running / healthy
```

首次启动 MySQL 要初始化数据目录，约 30~60 秒。应用会等数据库健康检查通过后再启动。

看日志确认：

```bash
docker logs -f keel
```

出现这段就说明 ip2region 离线库正常加载（**不需要联网下载**）：

```
c.s.f.clientinfo.IpLocationService : ip2region 离线库初始化完成
```

健康检查：

```bash
curl -s http://127.0.0.1:8080/api/health
# {"status":"UP","version":"1.1.4",...}
```

浏览器访问 `http://服务器IP:8080`，用 `admin / admin123` 登录，**上线后第一件事是改密码**。

---

## 6. 上线检查清单

```bash
# 1. 容器都在跑且健康
docker compose ps

# 2. 接口通
curl -sf http://127.0.0.1:8080/api/health && echo OK

# 3. 页面能打开
curl -sf -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/

# 4. 归属地功能正常（内网 IP 也算正常，容器网络走的是内网）
docker logs keel 2>&1 | grep -i ip2region
```

浏览器侧再确认一遍：登录 → 系统监控 → 登录日志，「来源」列显示 IP 和归属地。

---

## 7. 常见问题

**容器起不来，日志报 `UnknownHostException: mysql`**
容器之间用容器名通信必须建自定义网络，Docker 默认 `bridge` 只支持 IP 直连。
`docker network create keel-net` 后再 `docker compose up -d`。

**启动报 `JWT_SECRET ... 至少 32 字节`**
`.env` 里的 `JWT_SECRET` 没改或太短，`openssl rand -hex 32` 生成一个。

**端口被占用 / 想换端口**
改 `.env` 的 `KEEL_PORT`，例如 `8081`，重启 `docker compose up -d`。

**归属地显示「内网」或「未知」**
先看 `docker logs keel | grep -i ip2region`。如果日志里是「初始化失败」，
说明镜像不完整（tar 包损坏），重新 `sha256sum -c SHA256SUMS` 校验并重新 `docker load`。

**想换端口对外暴露、又不想直接暴露 8080**
前面加 Nginx 反代并配 HTTPS，同时在 `.env` 里把 `KEEL_PORT` 改成 `127.0.0.1:8080`
只绑本机。compose 里端口写法改成 `"127.0.0.1:${KEEL_PORT:-8080}:8080"`。

**数据怎么备份**
```bash
docker exec mysql mysqldump -uroot -padmin --single-transaction \
  --default-character-set=utf8mb4 scaffold_system > backup_$(date +%F).sql
```

**怎么升级版本**
换新 tar 包 → `docker load` → 改 `.env` 的 `KEEL_IMAGE` → `docker compose up -d`。
数据卷 `mysql-data` 不受影响，容器重建后自动挂回。

---

## 8. 和「从源码构建」的区别

这是最容易搞混的地方：

| | **直接跑镜像**（本文） | 从源码构建 |
| --- | --- | --- |
| 什么时候用 | 公网服务器上线 | 改了代码要验证 |
| 需要下载 ip2region | **不需要**，已打包在镜像里 | 不需要，但本地源码树里要有 xdb 文件 |
| 需要联网 | 不需要（除了 `docker load`） | 需要（拉 npm/maven 依赖） |
| 需要源码 | 不需要 | 需要 |

那个 `scripts/fetch-ip2region.ps1` / `.sh` 脚本只在**换机器克隆源码**时需要跑一次
（因为 xdb 二进制不进 git）。构建镜像本身不下载任何东西 ——
数据文件随源码树一起被 `COPY` 进 jar。
