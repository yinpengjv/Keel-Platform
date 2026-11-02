-- V5：操作日志补充客户端信息（浏览器 / 操作系统）
-- 幂等：重复执行不产生副作用。
-- 背景：登录日志与操作日志此前都没有写入来源与客户端信息，
--      前端「来源」「客户端」两列长期显示为空。
--      sys_login_log 早已有 ip/location/browser/os 四列，仅补写入逻辑；
--      sys_oper_log 只有 ip/location，需新增 browser/os 两列。

-- ──────────────────────────── sys_oper_log：新增浏览器与操作系统列 ────────────────────────────
-- location 列已存在（此前写死为「本机/内网」），改为写入 ip2region 解析出的真实归属地。

SET @schema_name = DATABASE();

SET @ddl_oper_browser = 'ALTER TABLE sys_oper_log ADD COLUMN browser VARCHAR(128) NULL COMMENT ''浏览器''';
SET @ddl_oper_os = 'ALTER TABLE sys_oper_log ADD COLUMN os VARCHAR(128) NULL COMMENT ''操作系统''';

SET @sql_oper_browser = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'sys_oper_log' AND COLUMN_NAME = 'browser') > 0,
    'SELECT 1',
    @ddl_oper_browser);
PREPARE stmt FROM @sql_oper_browser; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_oper_os = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'sys_oper_log' AND COLUMN_NAME = 'os') > 0,
    'SELECT 1',
    @ddl_oper_os);
PREPARE stmt FROM @sql_oper_os; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ──────────────────────────── 历史数据回填（尽力而为） ────────────────────────────
-- 存量日志没有客户端信息，无法回溯浏览器与操作系统，保持 NULL。
-- location 同样无法回溯（IP 需外网库查询），保持原值。
