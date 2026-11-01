# 运行日志

系统监控 → 运行日志（`/monitor/runtime-log`），把接口请求、慢请求、异常堆栈与 SQL 执行情况收进库里查。

与另外两张日志表的分工要说清，否则容易重复建设：

| 页面 | 表 | 回答的问题 | 写入时机 |
| --- | --- | --- | --- |
| 操作日志 | `sys_oper_log` | 谁改了哪条数据、参数是什么 | 带 `@OperLog` 的写操作 |
| 登录日志 | `sys_login_log` | 谁在什么时候登录、成功与否 | 登录 / 退出 |
| 运行日志 | `sys_runtime_log` | 请求为什么慢、为什么 500、这条 SQL 是谁发的 | 每个请求 + WARN 及以上 + 可选 SQL |

## 采集哪些内容

| 通道 | 来源 | 默认状态 |
| --- | --- | --- |
| 访问 | `RequestAccessLogFilter`（logger `scaffold.access`），一行含方法、路径、状态码、耗时、账号、IP | 开 |
| 异常 | 所有 `WARN` / `ERROR`，堆栈取前 4000 字符存 `detail` | 开 |
| SQL | mapper 的 `DEBUG`（`Preparing` / `Parameters` / `Total`） | 关 |

同一请求的所有日志共用 `traceId`（沿用项目里的 `TraceIdFilter`）。页面里点任意一行的 traceId，就筛出这次请求的全部记录 —— 这是这个功能最核心的用法：从一条 500 或一条慢请求，直接看到它当时执行了哪几条 SQL。

## 开关

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `RUNTIME_LOG_ENABLED` | `true` | 总开关；`false` 时只打控制台，不入库 |
| `RUNTIME_LOG_CAPTURE_SQL` | `false` | 是否收 SQL 与参数；开启时自动把 `scaffold.log.runtime.sql-loggers` 里的 logger 调到 DEBUG |
| `RUNTIME_LOG_RETAIN_DAYS` | `7` | 保留天数，过期数据在写入过程中顺带清理；`0` 永久保留 |
| `RUNTIME_LOG_QUEUE_CAPACITY` | `2000` | 写入队列容量，满了直接丢弃并计数，不阻塞业务线程 |

排障时的推荐姿势：平时保持默认（只收访问与异常，量很小），需要追 SQL 时把 `RUNTIME_LOG_CAPTURE_SQL=true` 重启一次，重现问题，看完再改回 `false`。

不常开 SQL 的原因很实际：一次列表请求就是 5~20 行 SQL，长期开会让表以每天数万行的速度增长，而且 `Parameters` 里是真实字段值，改用户时明文密码也会进库。

## 写入是怎么做的

```
logback 事件
  → DbLogAppender（accept 过滤）→ 有界队列（2000）→ 单线程批量（100 条 / 500ms）
      → JdbcRuntimeLogSink（JdbcTemplate batchUpdate）→ sys_runtime_log
```

四个设计点，改动前先看：

1. **不走 MyBatis**。写日志的 SQL 如果经过 MyBatis，就会被自己的 SQL 采集收走，形成放大。`JdbcRuntimeLogSink` 用 `JdbcTemplate` 直插，与 `JdbcOperationLogRecorder` 同一思路。
2. **不阻塞业务**。`queue.offer` 失败就丢弃并计数（页面上的「队列丢弃」），绝不在请求线程里等。
3. **不自激**。两层过滤：日志页本身与它的接口请求（路径含 `/monitor/runtime-log`）不入库；消息里含 `sys_runtime_log` 的 SQL 不入库。实测连续查 6 次日志页，表行数不变。
4. **不抛异常**。写库失败只增加「写库失败」计数，不影响业务返回。

## 接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/monitor/runtime-logs` | `monitor:runtime:list` | 分页；支持 `level`、`keyword`、`traceId`、`range`（24h/7d/30d/all） |
| GET | `/api/monitor/runtime-logs/stats` | 同上 | 概览：各级别数量、5xx 数、慢请求数、Top 慢接口、采集状态 |
| GET | `/api/monitor/runtime-logs/{id}` | 同上 | 详情，含 `detail`（堆栈或 SQL 全文） |
| GET | `/api/monitor/runtime-logs/export` | 同上 | 导出 CSV，上限 5000 行 |
| DELETE | `/api/monitor/runtime-logs/{id}` | `monitor:runtime:remove` | 删除单条 |
| DELETE | `/api/monitor/runtime-logs` | 同上 | 清空 |
| DELETE | `/api/monitor/runtime-logs/before/{days}` | 同上 | 删除 N 天前，`0` 等于清空 |

列表接口**不返回 `detail`**：堆栈与 SQL 全文只在详情里按需加载，免得列表被大字段拖慢。

两个权限码独立于操作日志，因为堆栈与 SQL 结构会暴露实现细节，默认建议只授超管。

## 容量

单条访问日志约 200~400 字节，一次列表请求若开 SQL 会产生 5~20 条。按 20 人日活、每人 200 次请求估算：

| 场景 | 日增 | 7 天 |
| --- | --- | --- |
| 只收访问与异常 | 约 4 千条 / 2 MB | 约 3 万条 / 15 MB |
| 开 SQL 采集 | 约 4 万条 / 25 MB | 约 28 万条 / 175 MB |

按 `RUNTIME_LOG_RETAIN_DAYS` 自动清过期数据，单批上限 5000 行避免长事务。需要更长的留存就接 Loki / ClickHouse，不要让这张表承担归档职责。

## 部署注意

- 新库：`sql/V3__runtime_log.sql` 会在初始化时一起执行（建表 + 幂等菜单）。
- 老库：手工执行同一份脚本即可，可重复执行，不会重复插菜单。
- 容器里 `docker logs` 仍会有全部控制台日志，落库是额外的，不是替代。
- 日志表跟业务库同库，数据库慢时写入会排队，但这只影响日志，不影响业务响应。
