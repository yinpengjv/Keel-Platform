-- ============================================================
-- 龙骨中台 · DDL（表结构）
--
--   本文件只建表、改表，不含任何业务数据；数据见 V2__init_data.sql。
--   全新部署：先执行本文件，再执行 V2__init_data.sql。
--
--   幂等：全部使用 CREATE TABLE IF NOT EXISTS 与条件化 ALTER，
--        重复执行不产生副作用，可用于老库补结构。
--
--   共 18 张表：
--     sys_dept / sys_post / sys_user / sys_role / sys_menu
--     sys_user_role / sys_user_post / sys_role_menu / sys_role_dept
--     sys_dict_type / sys_dict_data / sys_config
--     sys_oper_log / sys_login_log / sys_runtime_log / sys_file
--     gen_table / gen_table_column
-- ============================================================

-- ──────────────────────────── 组织与权限 ────────────────────────────

CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '部门 ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级部门 ID',
    ancestors VARCHAR(500) NOT NULL DEFAULT '' COMMENT '祖级列表',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    leader VARCHAR(64) DEFAULT NULL COMMENT '部门负责人',
    phone VARCHAR(32) DEFAULT NULL COMMENT '联系电话',
    email VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '部门状态：1 正常，0 停用',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sys_dept_parent (parent_id),
    KEY idx_sys_dept_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统部门表';

CREATE TABLE IF NOT EXISTS sys_post (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '岗位 ID',
    post_code VARCHAR(64) NOT NULL COMMENT '岗位编码',
    post_name VARCHAR(100) NOT NULL COMMENT '岗位名称',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '岗位状态：1 正常，0 停用',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_post_code (post_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统岗位表';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password VARCHAR(128) NOT NULL COMMENT '登录密码密文',
    nickname VARCHAR(64) NOT NULL COMMENT '用户昵称',
    phone VARCHAR(32) DEFAULT NULL COMMENT '手机号',
    email VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    dept_id BIGINT DEFAULT NULL COMMENT '所属部门 ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：1 正常，0 停用',
    last_login_time DATETIME DEFAULT NULL COMMENT '最后登录时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_dept (dept_id),
    KEY idx_sys_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色 ID',
    role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    data_scope VARCHAR(32) NOT NULL DEFAULT 'SELF' COMMENT '数据权限范围',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '角色状态：1 正常，0 停用',
    builtin TINYINT NOT NULL DEFAULT 0 COMMENT '是否内置角色：1 是，0 否',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：1 删除，0 正常',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色表';

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT NOT NULL COMMENT '菜单 ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父级菜单 ID',
    menu_name VARCHAR(100) NOT NULL COMMENT '菜单名称',
    menu_type VARCHAR(16) NOT NULL COMMENT '菜单类型：dir 目录，menu 菜单，btn 按钮',
    icon VARCHAR(64) DEFAULT NULL COMMENT '菜单图标',
    path VARCHAR(200) DEFAULT NULL COMMENT '前端路由路径',
    component VARCHAR(200) DEFAULT NULL COMMENT '前端组件路径；open_type 为 iframe 时存嵌入地址',
    open_type VARCHAR(16) NOT NULL DEFAULT 'page' COMMENT '打开方式：page 本站页面，iframe 嵌入网站',
    builtin TINYINT NOT NULL DEFAULT 0 COMMENT '是否系统内置菜单：1 内置（种子数据），0 用户自建',
    permission VARCHAR(128) DEFAULT NULL COMMENT '权限标识',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    visible TINYINT NOT NULL DEFAULT 1 COMMENT '是否显示：1 显示，0 隐藏',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '菜单状态：1 正常，0 停用',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sys_menu_parent (parent_id),
    KEY idx_sys_menu_permission (permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统菜单和按钮权限表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_user_post (
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    post_id BIGINT NOT NULL COMMENT '岗位 ID',
    PRIMARY KEY (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户岗位关联表';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    menu_id BIGINT NOT NULL COMMENT '菜单 ID',
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

CREATE TABLE IF NOT EXISTS sys_role_dept (
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    dept_id BIGINT NOT NULL COMMENT '部门 ID',
    PRIMARY KEY (role_id, dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色自定义数据权限部门关联表';

-- ──────────────────────────── 字典与参数 ────────────────────────────

CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '字典类型 ID',
    dict_name VARCHAR(100) NOT NULL COMMENT '字典类型名称',
    dict_type VARCHAR(100) NOT NULL COMMENT '字典类型编码',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '字典状态：1 正常，0 停用',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '字典数据 ID',
    dict_type VARCHAR(100) NOT NULL COMMENT '字典类型编码',
    dict_label VARCHAR(100) NOT NULL COMMENT '字典项显示文本',
    dict_value VARCHAR(100) NOT NULL COMMENT '字典项实际值',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '字典项状态：1 正常，0 停用',
    tone VARCHAR(32) DEFAULT NULL COMMENT '前端标签色调',
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认项：1 是，0 否',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sys_dict_data_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据表';

CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '参数 ID',
    config_name VARCHAR(100) NOT NULL COMMENT '参数名称',
    config_key VARCHAR(100) NOT NULL COMMENT '参数键名',
    config_value VARCHAR(500) NOT NULL COMMENT '参数键值',
    config_type CHAR(1) NOT NULL DEFAULT 'N' COMMENT '是否系统内置：Y 是，N 否',
    builtin TINYINT NOT NULL DEFAULT 0 COMMENT '是否内置参数：1 是，0 否',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参数配置表';

-- ──────────────────────────── 日志 ────────────────────────────

-- 操作日志：含客户端信息（browser / os），由 OperationLogAspect 写入
CREATE TABLE IF NOT EXISTS sys_oper_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '操作日志 ID',
    module VARCHAR(100) NOT NULL COMMENT '操作模块',
    action VARCHAR(64) NOT NULL COMMENT '操作类型',
    request_method VARCHAR(16) DEFAULT NULL COMMENT '请求方法',
    request_url VARCHAR(500) DEFAULT NULL COMMENT '请求地址',
    request_params TEXT COMMENT '请求参数',
    oper_name VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    dept_name VARCHAR(100) DEFAULT NULL COMMENT '部门名称',
    ip VARCHAR(64) DEFAULT NULL COMMENT 'IP 地址',
    location VARCHAR(128) DEFAULT NULL COMMENT '操作地点',
    browser VARCHAR(128) DEFAULT NULL COMMENT '浏览器',
    os VARCHAR(128) DEFAULT NULL COMMENT '操作系统',
    cost BIGINT DEFAULT NULL COMMENT '耗时毫秒',
    result TINYINT NOT NULL DEFAULT 1 COMMENT '操作结果：1 成功，0 失败',
    error_msg TEXT COMMENT '错误信息',
    oper_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_sys_oper_log_time (oper_time),
    KEY idx_sys_oper_log_oper_name (oper_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '登录日志 ID',
    username VARCHAR(64) DEFAULT NULL COMMENT '登录账号',
    ip VARCHAR(64) DEFAULT NULL COMMENT 'IP 地址',
    location VARCHAR(128) DEFAULT NULL COMMENT '登录地点',
    browser VARCHAR(128) DEFAULT NULL COMMENT '浏览器',
    os VARCHAR(128) DEFAULT NULL COMMENT '操作系统',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '登录状态：1 成功，0 失败',
    msg VARCHAR(500) DEFAULT NULL COMMENT '提示消息',
    login_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    PRIMARY KEY (id),
    KEY idx_sys_login_log_time (login_time),
    KEY idx_sys_login_log_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';

-- 运行日志：请求访问、异常、SQL（可选）等运行时诊断记录
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

-- ──────────────────────────── 文件与代码生成 ────────────────────────────

CREATE TABLE IF NOT EXISTS sys_file (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件 ID',
    original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    storage_name VARCHAR(255) NOT NULL COMMENT '存储文件名',
    url VARCHAR(500) NOT NULL COMMENT '访问地址',
    content_type VARCHAR(128) DEFAULT NULL COMMENT '文件类型',
    size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小字节数',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息表';

CREATE TABLE IF NOT EXISTS gen_table (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '代码生成表 ID',
    table_name VARCHAR(100) NOT NULL COMMENT '数据库表名',
    table_comment VARCHAR(200) DEFAULT NULL COMMENT '表说明',
    class_name VARCHAR(100) NOT NULL COMMENT '后端实体类名',
    module_name VARCHAR(64) NOT NULL COMMENT '模块名称',
    business_name VARCHAR(64) NOT NULL COMMENT '业务名称',
    synced TINYINT NOT NULL DEFAULT 0 COMMENT '是否同步数据库字段：1 是，0 否',
    create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gen_table_name (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码生成表配置';

CREATE TABLE IF NOT EXISTS gen_table_column (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '代码生成字段 ID',
    table_id BIGINT NOT NULL COMMENT '代码生成表 ID',
    column_name VARCHAR(100) NOT NULL COMMENT '数据库字段名',
    java_field VARCHAR(100) NOT NULL COMMENT 'Java 字段名',
    java_type VARCHAR(64) NOT NULL COMMENT 'Java 类型',
    jdbc_type VARCHAR(64) NOT NULL COMMENT 'JDBC 类型',
    column_comment VARCHAR(200) DEFAULT NULL COMMENT '字段说明',
    is_insert TINYINT NOT NULL DEFAULT 1 COMMENT '是否新增字段',
    is_edit TINYINT NOT NULL DEFAULT 1 COMMENT '是否编辑字段',
    is_list TINYINT NOT NULL DEFAULT 1 COMMENT '是否列表展示',
    is_query TINYINT NOT NULL DEFAULT 0 COMMENT '是否查询字段',
    query_type VARCHAR(32) NOT NULL DEFAULT '=' COMMENT '查询方式',
    form_type VARCHAR(64) NOT NULL DEFAULT '文本框' COMMENT '表单控件类型',
    required TINYINT NOT NULL DEFAULT 0 COMMENT '是否必填',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    PRIMARY KEY (id),
    KEY idx_gen_table_column_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码生成字段配置';

-- ──────────────────────────── 老库补列（幂等） ────────────────────────────
-- 上面的 CREATE TABLE 已包含 browser/os；这里的 ALTER 只对「升级前建好的老库」生效，
-- 新建库执行时列已存在，会走 `DO 0` 空操作。
--
-- SET NAMES 不可省：PREPARE 会按 character_set_client 解析语句文本，
-- 连接字符集不是 utf8mb4 时，动态 SQL 里的中文 COMMENT 会被写成「???」。

SET NAMES utf8mb4;
SET @schema_name = DATABASE();

SET @ddl_oper_browser = 'ALTER TABLE sys_oper_log ADD COLUMN browser VARCHAR(128) NULL COMMENT ''浏览器''';
SET @ddl_oper_os = 'ALTER TABLE sys_oper_log ADD COLUMN os VARCHAR(128) NULL COMMENT ''操作系统''';

SET @sql_oper_browser = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'sys_oper_log' AND COLUMN_NAME = 'browser') > 0,
    'DO 0',
    @ddl_oper_browser);
PREPARE stmt FROM @sql_oper_browser; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_oper_os = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'sys_oper_log' AND COLUMN_NAME = 'os') > 0,
    'DO 0',
    @ddl_oper_os);
PREPARE stmt FROM @sql_oper_os; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量日志没有客户端信息，无法回溯浏览器与操作系统，保持 NULL。
-- location 同样无法回溯（IP 需外网库查询），保持原值。
