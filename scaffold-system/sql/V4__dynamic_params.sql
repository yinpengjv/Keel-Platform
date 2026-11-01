-- V4：动态参数 + 在线用户强制下线 + 接口文档
-- 幂等：重复执行不产生副作用。

-- ──────────────────────────── 参数配置（可在页面「系统管理 → 参数配置」修改） ────────────────────────────
-- 内置参数（builtin=1）页面不允许改值，因此以下参数全部 builtin=0；
-- 页面删除某条参数后，后端回落到 application.yml / 环境变量的启动默认值。

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '登录验证码窗口(秒)', 'sys.login.captchaWindowSeconds', '600', 'Y', 0,
       '登录失败后强制验证码的持续时长，单位秒；删除该参数后回落到 SCAFFOLD_LOGIN_CAPTCHA_WINDOW（默认 600）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.login.captchaWindowSeconds');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '慢请求阈值(毫秒)', 'sys.log.accessSlowMs', '1000', 'Y', 0,
       '请求耗时超过该值记为慢请求（运行日志 WARN + 控制台 [慢请求] 标记）；删除后回落到 ACCESS_LOG_SLOW_MS（默认 1000）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.log.accessSlowMs');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '运行日志保留天数', 'sys.log.runtimeRetainDays', '7', 'Y', 0,
       'sys_runtime_log 超过该天数的记录在写入过程中被顺带清理，0 或负数表示永久保留；删除后回落到 RUNTIME_LOG_RETAIN_DAYS（默认 7）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.log.runtimeRetainDays');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '令牌有效期(秒)', 'sys.security.jwtExpiresSeconds', '7200', 'Y', 0,
       '登录令牌有效期，单位秒，改后对新登录生效（存量令牌不受影响）；删除后回落到 scaffold.jwt.expires-seconds（默认 7200）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.security.jwtExpiresSeconds');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '登录失败锁定阈值(次)', 'sys.security.maxLoginFailures', '5', 'Y', 0,
       '同一账号连续登录失败达到该次数即锁定，0 或负数表示不启用锁定；成功登录后清零'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.security.maxLoginFailures');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, builtin, remark)
SELECT '登录锁定时长(分钟)', 'sys.security.lockMinutes', '15', 'Y', 0,
       '达到失败阈值后账号锁定的时长，单位分钟，锁定期间登录直接拒绝'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.security.lockMinutes');

-- ──────────────────────────── 菜单：用户管理下的强制下线按钮 ────────────────────────────
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, open_type, builtin, permission, sort, visible, status, create_by, update_by)
SELECT 1015, 101, '强制下线', 'btn', '', '', '', 'page', 1, 'system:user:kick', 5, 1, 1, 'system', 'system'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1015);

-- ──────────────────────────── 菜单：系统管理 → 接口文档（受 system:swagger:view 控制） ────────────────────────────
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, open_type, builtin, permission, sort, visible, status, create_by, update_by)
SELECT 108, 100, '接口文档', 'menu', 'code', '/system/swagger-doc', 'system/swaggerdoc/index', 'page', 1, 'system:swagger:view', 8, 1, 1, 'system', 'system'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 108);
