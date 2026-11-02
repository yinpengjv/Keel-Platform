# 在 Windows / Linux 上运行龙骨中台

> 适用代码：`scaffold-master-main`（后端 `scaffold-system`，前端 `scaffold-vue`）
> 本文默认端口：后端 **18080**、前端 **15173**、数据库 **3306**（与仓库默认的 8080/5173 不同，可按需改回）

---

## 0. 先分清三个部件

| 部件 | 技术栈 | 默认端口 | 说明 |
| --- | --- | --- | --- |
| 后端 `scaffold-system` | Java 17 + Spring Boot 3.5 + MyBatis | 8080 → 本项目用 18080 | 打成可执行 jar，内置 Tomcat |
| 前端 `scaffold-vue` | Vue 3 + Vite + TypeScript | 5173 → 本项目用 15173 | 开发用 dev server，生产先 `build` 成静态文件 |
| 数据库 | MySQL 5.7+ / MariaDB 10.4+ | 3306 | 库名 `scaffold_system`，需要先导入 `sql/V1__init_schema.sql`（DDL）、`V2__init_data.sql`（DML） |
| Redis（可选） | — | 6379 | 默认 `SCAFFOLD_CACHE=local` 走进程内存，**不装 Redis 也能跑** |

依赖版本：

- JDK **17**（`pom.xml` 里 `java.version=17`，17、21 都可以，8 和 11 不行）
- Maven 3.9+
- Node.js **20+**（README 写 22+，Vite 6 要求 18+）
- MySQL 5.7+ 或 MariaDB 10.4+（本项目实测 MariaDB 11.4）

---

## 1. 数据库准备（两个平台完全一样）

先建库（**空库即可**，表由 SQL 建）：

```sql
CREATE DATABASE scaffold_system DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
```

再导入两份 SQL，**顺序不能反**：

```bash
cd scaffold-system/sql

mysql -h127.0.0.1 -P3306 -uroot -p \
  --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+08:00'" \
  scaffold_system < V1__init_schema.sql

mysql -h127.0.0.1 -P3306 -uroot -p \
  --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+08:00'" \
  scaffold_system < V2__init_data.sql
```

两个参数别省：`--default-character-set=utf8mb4` 不加会中文乱码；`--init-command` 不加会让 `create_time` 差 8 小时。

也可以不敲 SQL，用项目自带的初始化工具（读同一组 `DB_*` 环境变量，按版本号顺序导入）：

```bash
cd scaffold-system/scaffold-admin
mvn -q exec:java -Dexec.mainClass=com.scaffold.admin.util.DbInit
```

> `DbInit` 会按 `../sql` 相对路径找脚本，所以必须在 `scaffold-admin` 目录下运行。加参数 `fix` 会 **DROP 整个库重建**，只在确认库名、已备份时用。
> `exec-maven-plugin` 不在项目依赖里，第一次跑要联网下载；纯离线环境请走上面的 `mysql` 客户端，或在 IDE 里直接运行 `DbInit` 的 main 方法。

导入完成后关键账号：`admin / admin123`（超级管理员，本地开发用，上线前必须改）。

---

## 2. Windows

### 2.1 一键启动（这台机器已经在用的方式）

绿色版运行时全在 `D:\ToolApps\runtime`，不装服务、不改系统环境变量：

```
D:\ToolApps\runtime\start-scaffold.cmd    :: 启动 MariaDB + 后端 + 前端
D:\ToolApps\runtime\stop-scaffold.cmd     :: 全部停掉
```

脚本里可改的都在顶部：`BACKEND_PORT` / `FRONTEND_PORT` / `DB_PORT`，以及 `PROJECT` 指向项目根目录。
启动后浏览器打开 <http://localhost:15173>，账号 `admin / admin123`。

> 注意用 `localhost`，不要用 `127.0.0.1`：Vite 默认只监听 IPv6 的 `[::1]`。

### 2.2 手工三步（换机器时照这个来）

**① 后端**（cmd 窗口，环境变量只对当前窗口有效）

```bat
set "JWT_SECRET=<至少32字节的随机串>"
set "DB_HOST=127.0.0.1"
set "DB_PORT=3306"
set "DB_NAME=scaffold_system"
set "DB_USERNAME=root"
set "DB_PASSWORD="
set "SCAFFOLD_CACHE=local"

cd /d D:\path\to\scaffold-master-main\scaffold-system
mvn -pl scaffold-admin -am package -DskipTests
java -jar scaffold-admin\target\scaffold-admin-0.1.0-SNAPSHOT.jar --server.port=18080
```

PowerShell 写法（变量名带 `$env:`）：

```powershell
$env:JWT_SECRET = "<至少32字节的随机串>"
$env:DB_HOST="127.0.0.1"; $env:DB_PORT="3306"; $env:DB_NAME="scaffold_system"
$env:DB_USERNAME="root"; $env:DB_PASSWORD=""; $env:SCAFFOLD_CACHE="local"
cd D:\path\to\scaffold-master-main\scaffold-system
java -jar scaffold-admin\target\scaffold-admin-0.1.0-SNAPSHOT.jar --server.port=18080
```

密钥可以现场生成后落盘，避免每次重启换密钥导致旧 token 失效：

```bat
for /f %i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set "JWT_SECRET=%i"
```

**② 前端**（另开一个 cmd 窗口）

```bat
cd /d D:\path\to\scaffold-master-main\scaffold-vue
set "VITE_API_PROXY_TARGET=http://127.0.0.1:18080"
npm ci            :: 首次，或 package-lock.json 变更后
npm run dev -- --port 15173 --strictPort
```

**③ 验证**

```bat
curl http://127.0.0.1:18080/api/auth/login-challenge?username=admin
```

返回 `{"code":200,...}` 即后端就绪，再刷新 <http://localhost:15173>。

### 2.3 生产模式（可选）

后端继续用 jar 常驻；前端 `npm run build` 出 `dist/`，交给 IIS 或 Nginx 托管，并把 `/api` 反代到后端。参考第 4 节的 Nginx 配置。

---

## 3. Linux

Linux 上没有绿色版包，走系统包管理安装（下面给出 Ubuntu/Debian 与 RHEL 系两套）。

### 3.1 装依赖

Ubuntu / Debian：

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven curl unzip

# Node.js 22（发行版自带的版本常常太旧）
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

# MySQL（或 sudo apt install -y mariadb-server）
sudo apt install -y mysql-server
sudo systemctl enable --now mysql
```

RHEL / Rocky / AlmaLinux：

```bash
sudo dnf install -y java-17-openjdk-devel maven curl unzip
curl -fsSL https://rpm.nodesource.com/setup_22.x | sudo bash -
sudo dnf install -y nodejs
sudo dnf install -y mysql-server
sudo systemctl enable --now mysql
```

检查：

```bash
java -version     # 需要 17+
mvn -v
node -v           # 需要 20+
mysql --version
```

### 3.2 建库与授权

```bash
sudo mysql -e "CREATE DATABASE IF NOT EXISTS scaffold_system DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"
sudo mysql -e "CREATE USER IF NOT EXISTS 'scaffold'@'127.0.0.1' IDENTIFIED BY '换成强密码';"
sudo mysql -e "GRANT ALL PRIVILEGES ON scaffold_system.* TO 'scaffold'@'127.0.0.1'; FLUSH PRIVILEGES;"
```

导入 SQL：

```bash
cd ~/scaffold-master-main/scaffold-system/sql
for f in V1__init_schema.sql V2__init_data.sql; do
  mysql -h127.0.0.1 -P3306 -uscaffold -p \
    --default-character-set=utf8mb4 \
    --init-command="SET time_zone='+08:00'" \
    scaffold_system < "$f"
done
```

### 3.3 构建与启动后端

```bash
cd ~/scaffold-master-main/scaffold-system
export JWT_SECRET="$(openssl rand -hex 32)"     # 至少 32 字节，否则启动即失败
mvn -pl scaffold-admin -am package -DskipTests
java -jar scaffold-admin/target/scaffold-admin-0.1.0-SNAPSHOT.jar --server.port=18080
```

首次构建约 1~3 分钟（要下依赖）；之后可以加 `-o` 走离线模式。

### 3.4 前端

**开发模式**（改代码即时生效，适合调试）：

```bash
cd ~/scaffold-master-main/scaffold-vue

# 如果 Node 需要换成国内源
npm config set registry https://registry.npmmirror.com

npm ci
export VITE_API_PROXY_TARGET="http://127.0.0.1:18080"
npm run dev -- --port 15173 --strictPort --host 0.0.0.0
```

`--host 0.0.0.0` 是为了让同一网络的机器能访问；只在本机用就换成 `127.0.0.1`。

**生产模式**：

```bash
cd ~/scaffold-master-main/scaffold-vue
npm ci
npm run build          # 产物在 dist/，纯静态，可直接扔给 Nginx
```

### 3.5 用 systemd 托管（服务器上推荐）

`/etc/systemd/system/keel-backend.service`：

```ini
[Unit]
Description=Keel Admin Backend
After=network.target mysql.service

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/scaffold-master-main
Environment="JWT_SECRET=这里填至少32字节的密钥"
Environment="DB_HOST=127.0.0.1"
Environment="DB_PORT=3306"
Environment="DB_NAME=scaffold_system"
Environment="DB_USERNAME=scaffold"
Environment="DB_PASSWORD=换成真实密码"
Environment="SCAFFOLD_CACHE=local"
ExecStart=/usr/bin/java -jar /opt/scaffold-master-main/scaffold-system/scaffold-admin/target/scaffold-admin-0.1.0-SNAPSHOT.jar --server.port=8080
Restart=on-failure
RestartSec=5
StandardOutput=append:/var/log/keel/backend.log
StandardError=append:/var/log/keel/backend.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now keel-backend
sudo systemctl status keel-backend
journalctl -u keel-backend -f
```

### 3.6 一键脚本（仓库内已提供）

```bash
cd ~/scaffold-master-main
./scripts/start-local.sh     # 起后端 + 前端，日志与 pid 落在 .local/
./scripts/stop-local.sh      # 停掉两个进程
```

脚本顶部可改端口、数据库连接、`SCAFFOLD_CACHE`；首次运行会自动生成并保存 JWT 密钥到 `.local/jwt-secret.txt`（已加进 `.gitignore`），不会每次重启都换密钥。

---

## 4. Nginx 反代 + 静态托管（生产推荐）

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /opt/scaffold-master-main/scaffold-vue/dist;
    index index.html;

    # history 路由：刷新子页面不 404
    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

前端用的是 History 模式，`try_files ... /index.html` 这行**必须**有，否则刷新 `/system/user` 会 404。

---

## 5. 环境变量速查

后端只读**进程环境变量**，不会自动加载 `.env` 文件。

| 变量 | 用途 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | JWT 签名密钥 | **必填**，≥32 字节，不配或太短直接启动失败 |
| `DB_HOST` / `DB_PORT` | 数据库地址 / 端口 | 默认 `localhost:3306` |
| `DB_NAME` | 库名 | 默认 `scaffold_system` |
| `DB_USERNAME` / `DB_PASSWORD` | 账号 / 密码 | `application-dev.yml` 里默认密码是 `root`；本机空密码要显式设为空串 |
| `DB_TIMEZONE` | 连接与会话时区 | 默认 `+08:00` |
| `SCAFFOLD_CACHE` | 缓存实现 | `local`（内存，默认）/ `redis` |
| `SCAFFOLD_LOGIN_CAPTCHA_WINDOW` | 登录失败后强制验证码的窗口 | 秒，默认 600 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 仅 `SCAFFOLD_CACHE=redis` 时需要 | — |
| `VITE_API_PROXY_TARGET` | 前端 dev server 的代理目标 | 默认 `http://localhost:8080`，本项目要指到 18080 |

反过来的坑：环境里如果存在 `SERVER_PORT` / `SERVER__PORT`，Spring 松散绑定会把它当 `server.port`，覆盖命令行端口。启动前 `unset SERVER_PORT SERVER__PORT`，或显式 `--server.port=18080`。

---

## 6. 两个平台对照

| 步骤 | Windows | Linux |
| --- | --- | --- |
| 装 JDK | 绿色版解压，或 `winget install EclipseAdoptium.Temurin.17.JDK` | `apt install openjdk-17-jdk` / `dnf install java-17-openjdk-devel` |
| 装 Maven | 绿色版，或 `winget install Apache.Maven` | `apt install maven` / `dnf install maven` |
| 设环境变量 | `set X=Y`（cmd）/ `$env:X="Y"`（PowerShell），只影响当前窗口 | `export X=Y`，或写进 systemd unit |
| 启动后端 | `java -jar ...\scaffold-admin-0.1.0-SNAPSHOT.jar --server.port=18080` | 同左，路径用 `/` |
| 启动前端 | `npm run dev -- --port 15173 --strictPort` | 同左，服务器上再加 `--host 0.0.0.0` |
| 后台常驻 | 多开一个 cmd，或用 `start /min` | `nohup` / `systemd` |
| 看日志 | 控制台窗口 | `journalctl -u keel-backend -f` 或 `.local/logs/*.log` |
| 停进程 | `taskkill /IM java.exe /F` | `kill $(cat .local/backend.pid)` 或 `systemctl stop` |

---

## 7. 踩过的坑（照抄这节能省时间）

1. **构建报 `Unable to rename ... .jar to .jar.original`**
   后端还占着 jar，Windows 上文件被锁。先停后端（`taskkill /IM java.exe /F`）再 `mvn package`。

2. **`curl` 访问本机接口得到 502 / 连不上**
   系统代理（`http_proxy`、`HTTP_PROXY`）把本机请求也代理走了。加 `--noproxy '*'`，或 `unset http_proxy https_proxy`。

3. **浏览器打开 `127.0.0.1:15173` 白屏、`localhost:15173` 正常**
   Vite 默认只监听 `[::1]`（IPv6）。要么用 `localhost` 访问，要么启动加 `--host 127.0.0.1`。

4. **启动直接失败：`JWT_SECRET 必须配置为至少 32 字节的非空白密钥`**
   没配或太短。`openssl rand -hex 32` 生成一个（Linux/macOS），Windows 用 PowerShell 两段 GUID 拼接。

5. **数据库连不上，报 `Access denied for user 'root'@'localhost'`**
   `application-dev.yml` 里密码默认值写的是 `root`。空密码环境必须显式 `DB_PASSWORD=`（设成空串），不能干脆不设。

6. **中文显示成 `????`**
   导入 SQL 没带 `--default-character-set=utf8mb4`，或库不是 `utf8mb4`。库和连接两边都要是 utf8mb4。

7. **端口被占**
   `netstat -ano | findstr :18080`（Windows）/ `ss -lntp | grep 18080`（Linux）找到进程；改端口就用 `--server.port=`，前端同步改 `VITE_API_PROXY_TARGET`。

8. **前端改了代码但页面没变**
   长期运行的 Vite dev server 有 HMR 状态残留，重启 dev server 再看。另外 `localhost:5173` 上可能跑着**别的**项目，确认自己在看 15173。

9. **`npm ci` 卡住 / 报网络错误**
   换源：`npm config set registry https://registry.npmmirror.com`（Linux），Windows 同理。Maven 依赖慢则用 `-s` 指定带阿里云镜像的 `settings.xml`。

---

## 8. 最少验证清单

```bash
# 1. 后端活着（不需要 token）
curl -s --noproxy '*' http://127.0.0.1:18080/api/auth/login-challenge?username=admin

# 2. 能登录，拿到 token
curl -s --noproxy '*' -X POST http://127.0.0.1:18080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

# 3. 带 token 拉菜单路由，应返回 11 个左右已注册页面
TOKEN=<上一步的 token>
curl -s --noproxy '*' http://127.0.0.1:18080/api/auth/routes -H "Authorization: Bearer $TOKEN"
```

浏览器侧：登录 → 左侧出现「工作台 / 系统管理 / 系统工具 / 监控」等分组 → 进「系统管理 → 用户管理」能看到表格，就说明前后端和数据库三方都通了。
