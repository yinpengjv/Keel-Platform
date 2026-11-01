-- ============================================================
-- V3 运行日志：请求访问、异常、SQL（可选）等运行时诊断日志
-- 幂等：可重复执行，不会重复建表或重复插菜单
-- sys_menu.id 不是自增列，这里沿用种子数据的编号规律：203 菜单、2031 按钮
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_runtime_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '运行日志 ID',
    level VARCHAR(8) NOT NULL COMMENT '日志级别：INFO / WARN / ERROR / DEBUG',
    logger VARCHAR(120) DEFAULT NULL COMMENT '日志来源（类名或 scaffold.access）',
    trace_id VARCHAR(40) DEFAULT NULL COMMENT '链路标识，同一次请求的多条日志共用',
    request_method VARCHAR(8) DEFAULT NULL COMMENT '请求方法',
    request_url VARCHAR(300) DEFAULT NULL COMMENT '请求地址',
    http_status SMALLINT DEFAULT NULL COMMENT '响应状态码',
    cost INT DEFAULT NULL COMMENT '耗时毫秒',
    username VARCHAR(64) DEFAULT NULL COMMENT '登录账号',
    ip VARCHAR(64) DEFAULT NULL COMMENT '客户端地址',
    message VARCHAR(500) DEFAULT NULL COMMENT '日志摘要',
    detail TEXT COMMENT '异常堆栈或 SQL 明细',
    create_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_sys_runtime_log_time (create_time),
    KEY idx_sys_runtime_log_level (level, create_time),
    KEY idx_sys_runtime_log_trace (trace_id),
    KEY idx_sys_runtime_log_url (request_url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行日志表';

-- 菜单：挂在「系统监控」(200) 下，排在操作日志、登录日志之后
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, open_type, visible, permission, sort, status, builtin, create_by, update_by)
SELECT 203, 200, '运行日志', 'menu', 'activity', '/monitor/runtime-log', 'monitor/runtimelog/index', 'page', 1, 'monitor:runtime:list', 3, 1, 1, 'system', 'system'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'monitor:runtime:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 203);

-- 按钮权限：清理运行日志
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, open_type, visible, permission, sort, status, builtin, create_by, update_by)
SELECT 2031, 203, '清理运行日志', 'btn', '', '', '', 'page', 1, 'monitor:runtime:remove', 1, 1, 1, 'system', 'system'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'monitor:runtime:remove')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 2031);
