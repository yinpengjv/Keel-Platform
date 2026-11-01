# 龙骨中台管理系统 · Keel Platform

`Java 17` · `Spring Boot 3.5` · `MyBatis-Plus` · `Vue 3` · `TypeScript` · `MySQL` · `单镜像部署` · `MIT`

一套可以直接开工的后台管理底座。认证、菜单与按钮权限、数据范围、组织架构、字典参数、审计日志、代码生成都已经跑通，新项目只需在这上面写业务，不必再重复搭一遍权限和骨架。

前端不使用第三方 UI 框架，全部组件、图表、动效均为自研，视觉上是一套统一的「珍珠玻璃」主题，而不是拼装出来的后台模板。

---

## 一、它解决什么问题

从零做一个管理后台，真正耗时的从来不是业务页面，而是这些重复劳动：登录态与令牌续期、菜单树与动态路由、按钮级权限、按部门的数据隔离、字典与参数、操作留痕、列表分页导出、表单校验……每一项都不难，但每一项都要做对，做完还要保证它们互相咬合。

龙骨中台把这些一次做完，并把约定固化成可复用的组件与注解：

- 后端写一个接口，加 `@RequiresPermission("system:user:add")` 就有接口级鉴权，加 `@OperLog(module = "用户", action = "新增")` 就自动留痕。
- 前端写一个列表页，套 `ListPage` + `useListPage`，搜索、分页、勾选、批量操作、导出、空状态全部就位。
- 加一个菜单，在 `data/pages.ts` 注册一行即可；想挂外部系统，菜单的「打开方式」选内嵌或新标签页，不用改代码。

---

## 二、能力概览

| 能力 | 说明 |
| --- | --- |
| 认证与会话 | JWT 令牌 + 服务端登记，支持退出即失效；验证码只在登录失败后强制，成功或到期即解除 |
| 菜单与路由 | 菜单即路由，登录后按角色下发；目录、页面、按钮三级；自建菜单可挂内嵌网站或新标签页外链 |
| 按钮级权限 | 后端 `@RequiresPermission` + 切面拦截，前端 `PermissionButton` 与 `v-permission` 同源控制 |
| 数据范围 | 角色维度五档：全部、本部门、本部门及下级、自定义部门、仅本人，SQL 层自动拼装 |
| 组织与岗位 | 部门树（含上级链）、岗位、用户多岗位关联，另有组织画布可视化 |
| 字典与参数 | 字典类型/数据两级维护，前端 `DictSelect` 直接消费；系统参数支持内置保护 |
| 审计日志 | 登录日志、操作日志双轨，含趋势、泳道、热力图、异常筛选、导出与清理，请求参数脱敏 |
| 运行日志 | 请求访问、慢请求、异常堆栈、SQL 与参数统一入库，按 traceId 串起同一次请求，可筛选、导出、按天清理 |
| 代码生成 | 导入数据库表 → 配置 → 字段映射 → 预览/下载，产出**可直接运行**的前后端 CRUD 与幂等菜单 SQL |
| 可观测 | 每个请求一行访问日志（含状态码、耗时、登录用户），SQL 与参数可开，靠 traceId 把一次请求串起来 |
| 部署形态 | 本机前后端分离，或前端打进后端 jar 的**单镜像单端口**部署 |

### 菜单怎么挂页面，怎么挂外部系统

菜单分两类，表单形态不同：

| 类型 | 表单 | 行为 |
| --- | --- | --- |
| 系统自带菜单 | 填「组件路径」 | 指向 `src/data/pages.ts` 已注册的站内页面，如 `system/user/index` |
| 自建菜单 | 选「打开方式」+ 填地址 | `嵌入网站`：整页内嵌在后台内，无地址栏，右上角有刷新与另开窗口；`新标签页`：点击后浏览器开新标签，当前页面保持不变 |

自建菜单只提供上面两种外链形态，这是刻意的：站内页面必须先在 `pages.ts` 登记组件，再由后端菜单配置引用，避免有人随手填一个不存在的组件路径把导航点成白屏。数据层的 `open_type` 仍保留 `page` 取值，供内置菜单与历史数据使用，编辑时能正确回显，界面不会把老数据改坏。

嵌 `iframe` 前先确认对方站点没有设 `X-Frame-Options: DENY` 或 `CSP frame-ancestors`，否则会白屏——这类限制在浏览器侧，后台改不了。

### 登录验证码策略

首次登录**不需要**验证码。密码输错一次后，该账号在 10 分钟窗口内的登录必须带验证码；登录成功或窗口到期即自动解除。连续失败达到阈值（默认 5 次）账号会被锁定 15 分钟，阈值与时长均可在参数配置里调整。窗口长度由 `SCAFFOLD_LOGIN_CAPTCHA_WINDOW` 控制，页面改参数即时生效。

### 密码输入

登录页与用户表单的密码框右侧有眼睛按钮（`TextInput` 的 `revealable`），点一下在密文与明文之间切换，无需靠重填确认刚才输的是什么。

---

## 三、内置页面

工作台 1 个，管理页面 10 个，均已接入真实接口：

| 模块 | 路由 | 特色视图 |
| --- | --- | --- |
| 工作台 | `/dashboard` | 主次布局、会话首次开场、模块网络卡 |
| 用户管理 | `/system/user` | 列表 / 看板双视图 |
| 角色管理 | `/system/role` | 权限矩阵、权限溯源三层下钻 |
| 菜单管理 | `/system/menu` | 表格 / 导航星系图谱 + 侧栏实时预览 |
| 部门管理 | `/system/dept` | 组织画布，选中路径脉冲，缩放实时跟手 |
| 岗位管理 | `/system/post` | 岗位阶梯图谱 |
| 字典管理 | `/system/dict` | 样本册视图 |
| 参数设置 | `/system/config` | 设置面板 / 表格 |
| 操作日志 | `/monitor/oper-log` | 概览 + 趋势/泳道 + 明细横带，抽屉级联 |
| 登录日志 | `/monitor/login-log` | 概览 + 趋势/热力 + 异常 + 明细 |
| 运行日志 | `/monitor/runtime-log` | 概览卡 + 级别/链路筛选 + 明细抽屉（堆栈与 SQL 全文） |
| 代码生成 | `/tool/generator` | 三步流水线抽屉 |

外加无权限、页面不存在、组件未注册三个兜底页。

---

## 四、技术栈

**后端**

| 项 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 17+ |
| 框架 | Spring Boot 3.5.14、Spring Security |
| 持久层 | MyBatis-Plus 3.5.16，Mapper 以注解 + `<script>` 动态 SQL 为主 |
| 令牌 | jjwt，密钥由 `JWT_SECRET` 注入，启动时校验长度 |
| 数据库 | MySQL 5.7+ / 8.x（MariaDB 11 亦可） |
| 缓存 | **可选**。默认进程内存，`SCAFFOLD_CACHE=redis` 才走 Redis |

> Redis 不是必装项。令牌与验证码通过 `KeyValueStore` 抽象，单实例默认用内存实现；只有多实例共享会话时才需要切到 Redis。

**前端**

| 项 | 选型 |
| --- | --- |
| 框架 | Vue 3.5 + TypeScript 5.7 |
| 构建 | Vite 6 |
| 路由 | Vue Router 4（history 模式，动态注册） |
| 动效 | GSAP 3 |
| 组件与图表 | 全自研，无 Element Plus / Ant Design 依赖 |

---

## 五、目录结构

```
Keel-Platform/
├── scaffold-system/                 # 后端 Maven 多模块
│   ├── scaffold-common/             # 统一返回 R、分页 PageQuery/PageResult、业务异常
│   ├── scaffold-framework/          # 安全、JWT、权限切面、操作日志、KeyValueStore 抽象
│   ├── scaffold-system/             # 业务模块（与父目录同名）：认证、用户角色菜单组织字典参数、日志、工作台
│   ├── scaffold-generator/          # 代码生成（模板引擎在 GeneratorService）
│   ├── scaffold-admin/              # 启动模块、配置、健康检查、前端静态托管
│   └── sql/                         # V1 建表、V2 种子数据
├── scaffold-vue/                    # 前端
│   └── src/
│       ├── api/                     # 统一 HTTP 客户端与接口定义
│       ├── components/              # ui 基础件、business 业务件、charts 自绘图表
│       ├── composables/             # 列表、视图记忆、认证、问答、视口
│       ├── data/pages.ts            # 页面注册清单（菜单 component 取值来源）
│       ├── styles/                  # 主题令牌与分层样式
│       └── views/                   # 页面
├── docker/Dockerfile                # 三阶段构建：前端 → 后端 → JRE 运行时
├── scripts/                         # start-local.sh / stop-local.sh
└── docs/                            # 架构、接口约定、双平台运行、镜像部署
```

模块边界：`common` 与 `framework` 不含业务，`system` 只依赖它们不反向依赖，`generator` 独立成模块便于整体剥离，`admin` 负责装配。

---

## 六、快速开始

### 前置

JDK 17+、Maven 3.9+、Node.js 22+、MySQL 5.7+。Redis 可不装。

### ① 建库并初始化

```bash
mysql -uroot -p -e "CREATE DATABASE scaffold_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

mysql -uroot -p --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+08:00'" \
  scaffold_system < scaffold-system/sql/V1__init_schema.sql

mysql -uroot -p --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+08:00'" \
  scaffold_system < scaffold-system/sql/V2__seed_data.sql
```

`--default-character-set=utf8mb4` 与时区设置都不能省：前者避免中文菜单名乱码，后者避免审计时间偏移 8 小时。

### ② 启动后端

```bash
cd scaffold-system
export JWT_SECRET="$(openssl rand -hex 32)"     # Windows: set JWT_SECRET=...（至少 32 字节）
mvn -pl scaffold-admin -am package -DskipTests
java -jar scaffold-admin/target/scaffold-admin-0.1.0-SNAPSHOT.jar
```

默认监听 `8080`，接口前缀 `/api`，健康检查 `GET /api/health`。

### ③ 启动前端

```bash
cd scaffold-vue
npm ci
npm run dev
```

访问 `http://localhost:5173`。前端统一通过 `/api` 代理后端，代理目标由 `VITE_API_PROXY_TARGET` 指定。

### ④ 登录

种子管理员 `admin` / `admin123`，仅用于本地开发。超级管理员可见全部已注册菜单并拥有全部操作权限；其余种子角色默认未授予菜单，需在角色管理中分配，未分配时登录会显示「暂未分配可用菜单」。

### 或者：一条命令起容器

```bash
docker build -f docker/Dockerfile -t keel/keel-admin:1.0.0 .

docker run -d --name keel -p 8080:8080 \
  -e JWT_SECRET="$(openssl rand -hex 32)" \
  -e DB_HOST=host.docker.internal -e DB_PORT=3306 \
  -e DB_NAME=scaffold_system -e DB_USERNAME=root -e DB_PASSWORD='你的密码' \
  -e DB_TIMEZONE="+08:00" \
  keel/keel-admin:1.0.0
```

镜像内只有一个进程（Spring Boot）。前端静态资源由后端托管，`/` 直接出页面，深链接刷新回落 `index.html`，`/api/**` 的 404 仍是 404 而不会被吞成 HTML。Linux 上把 `DB_HOST` 换成宿主机实际 IP 或数据库容器名。

容器日志里能看到两类业务日志，靠 traceId 关联同一个请求：

```
2026-11-01 21:58:55.444 DEBUG [320e9094...] c.s.s.m.S.selectUsers : ==> Parameters: null, 0, 2
2026-11-01 21:58:55.459 INFO  [320e9094...] scaffold.access       : GET /api/system/users?pageNum=1&pageSize=2 200 228ms user=admin ip=172.17.0.1
```

按一次请求追：`docker logs keel 2>&1 | grep 320e9094`。关闭方式、日志轮转与敏感信息提醒见 [docs/docker-image.md](docs/docker-image.md) 第 5 节。

---

## 七、环境变量与运行期参数

后端只读进程环境变量，不加载 `.env` 文件。

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `JWT_SECRET` | 令牌签名密钥，≥32 字节，不能为空白 | **必填** |
| `DB_HOST` / `DB_PORT` | 数据库地址 / 端口 | `localhost` / `3306` |
| `DB_NAME` | 数据库名 | `scaffold_system` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号 / 密码 | `root` / `root` |
| `DB_TIMEZONE` | 连接与会话时区 | `+08:00` |
| `SCAFFOLD_CACHE` | `local` 进程内存 / `redis` 走 Redis | `local` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 仅 `SCAFFOLD_CACHE=redis` 时生效 | `localhost` / `6379` / 空 |
| `SCAFFOLD_LOGIN_CAPTCHA_WINDOW` | 登录失败后强制验证码的窗口（秒），**可被参数覆盖** | `600` |
| `ACCESS_LOG_ENABLED` | 每个请求一行访问日志 | `true` |
| `ACCESS_LOG_SLOW_MS` | 慢请求阈值（毫秒），**可被参数覆盖** | `1000` |
| `SQL_LOG_LEVEL` | mapper 日志级别，`debug` 打印 SQL 与参数 | `debug` |
| `RUNTIME_LOG_ENABLED` | 运行日志入库开关 | `true` |
| `RUNTIME_LOG_CAPTURE_SQL` | 是否把 SQL 与参数也收进运行日志 | `false` |
| `RUNTIME_LOG_RETAIN_DAYS` | 运行日志保留天数，`0` 永久保留，**可被参数覆盖** | `7` |
| `RUNTIME_LOG_QUEUE_CAPACITY` | 运行日志写入队列容量，满了丢弃不阻塞业务 | `2000` |
| `SCAFFOLD_SECURITY_MAX_LOGIN_FAILURES` | 登录失败锁定阈值（次），0 关闭，**可被参数覆盖** | `5` |
| `SCAFFOLD_SECURITY_LOCK_MINUTES` | 登录锁定时长（分钟），**可被参数覆盖** | `15` |
| `APP_STATIC_DIR` | 前端静态目录，设置后由后端托管（镜像内为 `/app/static`） | 空（关闭） |
| `VITE_API_PROXY_TARGET` | 前端开发代理目标（仅前端） | `http://localhost:8080` |

密钥务必在多次重启间保持一致，否则旧 token 全部失效。

**运行期参数（优先于环境变量）**：以下参数已放进「系统管理 → 参数配置」，页面上改完立即生效，无需重启；删除参数则回落到环境变量默认值：

| 参数键 | 含义 | 默认 |
| --- | --- | --- |
| `sys.login.captchaWindowSeconds` | 登录失败后强制验证码的窗口（秒） | `600` |
| `sys.log.accessSlowMs` | 慢请求阈值（毫秒） | `1000` |
| `sys.log.runtimeRetainDays` | 运行日志保留天数 | `7` |
| `sys.security.jwtExpiresSeconds` | 令牌有效期（秒），只对新登录生效 | `7200` |
| `sys.security.maxLoginFailures` | 登录失败锁定阈值（次），`0` 关闭锁定 | `5` |
| `sys.security.lockMinutes` | 登录锁定时长（分钟） | `15` |

**登录失败锁定**：同一账号连续登录失败（含验证码缺失/错误）达到阈值即锁定，期间登录直接拒绝并提示剩余时间；成功登录清零计数。锁定状态在 KV 存储中（`SCAFFOLD_CACHE=local` 时重启即解锁）。

**在线用户与强制下线**：用户管理列表带「在线」标识（有有效令牌即在线）；行内「强制下线」按钮（权限 `system:user:kick`）撤销该用户全部会话，操作写入操作日志。

**接口文档**：「系统管理 → 接口文档」（权限 `system:swagger:view`），Knife4j `/doc.html` 与原生 `/swagger-ui`。页面进入时自动换取一次性票据（60 秒有效、用后即焚），SwaggerAccessFilter 校验票据换会话 Cookie——直接访问文档地址会得到 403。`SWAGGER_ENABLED=false` 可整体关闭文档生成。

---

## 八、权限模型

四层，缺一不可：

1. **登录态** —— `JwtAuthenticationFilter` 解析令牌并校验服务端存储，退出登录即失效。
2. **接口权限** —— `@RequiresPermission("system:user:add")`，`PermissionAspect` 比对当前用户权限码集合，无权限返回 403。
3. **按钮权限** —— 同一权限码驱动前端 `PermissionButton` / `v-permission`，无权限时按钮隐藏。权限码与后端注解必须一致，写错只会在某一端失效。
4. **数据范围** —— 角色 `data_scope` 决定可见数据：`ALL`、`DEPT`、`DEPT_AND_CHILD`、`CUSTOM`、`SELF`，由 `SystemDataScopeResolver` 生成条件，不依赖前端传参。

验证时不要只看管理员。用低权限账号登录，直接调接口看是否 403、换 `deptId` 看是否越权，才算验证过。

---

## 九、代码生成

入口 `/tool/generator`。先在列表页从数据库导入表，再在流水线弹窗里走完 ① 表信息 → ② 字段映射 → ③ 代码预览与下载。元数据存在 `gen_table` 与 `gen_table_column`。

一次生成 9 个文件，**是可以直接跑起来的 CRUD**，不是空壳：

| 产出 | 内容 |
| --- | --- |
| `domain/Xxx.java` | 全字段 + getter/setter，按需 import |
| `domain/dto/XxxQuery.java` | 查询条件对象 |
| `mapper/XxxMapper.java` | 注解 + 动态 SQL：count / 分页 / 详情 / 新增 / 更新 / 单删 / 批删 |
| `service/XxxService.java` | 分页、详情、增删改、批量删、去空格、必填校验、事务 |
| `controller/XxxController.java` | 6 个接口，全部带 `@RequiresPermission` 与 `@OperLog` |
| `api/{module}/{resource}.ts` | 类型定义 + 6 个方法 |
| `views/{module}/{resource}/index.vue` | 搜索条、表格、分页、新增/编辑弹窗、批量删除、按钮权限 |
| `sql/{table}_menu.sql` | 单条幂等语句：菜单 + 增改删三个按钮，重跑不重复 |
| `README.md` | 落位路径、`pages.ts` 注册行、模块扫描提示 |

字段规则：密码/密钥类字段不进列表与查询但保留在表单（渲染成密码框）；`create_by`、`create_time` 等审计字段不进表单；有 `deleted` 列自动切逻辑删除；`status` 列自动出状态标签、开关与下拉筛选。

四个已知边界，动手前先看清：

- 生成路径用 `backend/` `frontend/` 虚拟前缀，需按下载包里的 README 映射手工落位，**不会**自动写入本工程。
- `@MapperScan` 只扫 `com.scaffold.system.mapper` 与 `com.scaffold.generator.mapper`，模块名不是 `system` 时要补一行扫描。
- 字段映射目前是**只读可视化**：`gen_table_column` 里列表/查询/表单/必填的开关在本界面还不能改，实际按导入默认值加上述自动规则生成；表说明与确认状态可以保存。
- 业务规则（唯一性、状态流转、关联校验）仍需自己补进 Service。生成器解决的是重复劳动，不是业务决策。

---

## 十、界面与设计

主题叫「珍珠玻璃」：低饱和底色、细腻高光、克制的玻璃质感，动效用 GSAP。刻意避开了后台模板常见的高饱和大色块。

除了常规列表，多个页面提供第二种看法：组织画布、看板、权限矩阵、岗位阶梯、导航星系、字典样本册。视图选择按页面记忆，下次进入仍是上次那个。

关系类场景用自绘 SVG 图谱组件：弹簧布局、脉冲、扇形触须，`variant="stage"` 是珍珠玻璃舞台形态，支持三层下钻。日志页配趋势形变折线、柱状、刻度环与时间轴泳道。

一处性能细节：玻璃质感不能用 `backdrop-filter` 叠在 3D 变换元素上，否则文字会发糊（用拉普拉斯方差量化，锐度相差 3 倍）。主题改用分层半透明背景加内阴影实现，缩放与倾斜时文字始终清晰。

---

## 十一、测试

```bash
# 后端：不连数据库的单元测试 + 编译
cd scaffold-system
mvn -pl scaffold-framework -am test
mvn -pl scaffold-admin -am package -DskipTests

# 后端：完整集成测试，需要真实数据库（建议独立校验库，测试会写入数据与日志）
mvn -pl scaffold-admin -am test

# 前端：单测与类型检查 + 生产构建
cd scaffold-vue
npm run test:unit
npm run build
```

构建通过不等于业务验收。权限、数据范围、分页边界、表单校验，都要用真实账号点一遍。

---

## 十二、文档

| 文档 | 内容 |
| --- | --- |
| [PROJECT_MAP.md](PROJECT_MAP.md) | 按功能定位页面、接口、服务、SQL、权限码 |
| [DESIGN.md](DESIGN.md) | 珍珠玻璃主题、组件复用与交互规范 |
| [AGENTS.md](AGENTS.md) | 开发约定与协作规则 |
| [docs/backend-architecture.md](docs/backend-architecture.md) | 后端分层与模块职责 |
| [docs/frontend-backend-integration.md](docs/frontend-backend-integration.md) | 前后端接口与数据格式约定 |
| [docs/admin-redesign-architecture.md](docs/admin-redesign-architecture.md) | 前端组件与视图架构 |
| [docs/run-windows-linux.md](docs/run-windows-linux.md) | Windows / Linux 逐步运行、Nginx 反代、排错 |
| [docs/docker-image.md](docs/docker-image.md) | 单镜像构建、运行、看日志、升级与备份 |
| [docs/runtime-log.md](docs/runtime-log.md) | 运行日志：采集范围、开关、接口、容量与自激防护 |
| [changelog.md](changelog.md) | 变更记录 |

---

## 十三、许可证

本项目代码采用 [MIT License](LICENSE)，允许商用、修改与分发，保留版权与许可声明即可。

第三方依赖遵循各自许可。其中 **GSAP 使用 [Standard "No Charge" License](https://gsap.com/community/standard-license/)，不在本项目 MIT 授权范围内**，使用与再分发时须遵守其条款并保留原声明。
