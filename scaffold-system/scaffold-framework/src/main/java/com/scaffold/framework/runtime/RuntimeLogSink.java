package com.scaffold.framework.runtime;

import java.util.List;

/**
 * 运行日志落库出口。实现放在业务模块（用 JdbcTemplate 直插，绕开 MyBatis，
 * 否则写日志的 SQL 又会被日志采集器收走，形成放大）。
 */
public interface RuntimeLogSink {

    /** 批量写入；实现方必须自行吞掉异常，绝不能因为写日志失败而影响业务请求 */
    void write(List<RuntimeLogEntry> entries);

    /** 累计写失败的批次数，供页面展示 */
    default long failedBatches() {
        return 0;
    }
}
