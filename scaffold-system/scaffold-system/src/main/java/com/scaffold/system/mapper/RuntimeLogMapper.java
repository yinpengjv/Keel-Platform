package com.scaffold.system.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RuntimeLogMapper {

    /** 列表不取 detail：堆栈与 SQL 全文只在详情抽屉里按需加载，避免列表接口被大字段拖慢 */
    @Select("""
            <script>
            SELECT id, level, logger, trace_id AS traceId, request_method AS requestMethod,
                   request_url AS requestUrl, http_status AS httpStatus, cost, username, ip,
                   message, create_time AS createTime
            FROM sys_runtime_log
            WHERE 1 = 1
            <if test="level != null and level != ''">AND level = #{level}</if>
            <if test="traceId != null and traceId != ''">AND trace_id = #{traceId}</if>
            <if test="keyword != null and keyword != ''">
                AND (message LIKE CONCAT('%', #{keyword}, '%')
                     OR request_url LIKE CONCAT('%', #{keyword}, '%')
                     OR logger LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Map<String, Object>> selectRuntimeLogs(
            @Param("level") String level,
            @Param("keyword") String keyword,
            @Param("traceId") String traceId,
            @Param("sinceHours") Integer sinceHours,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_runtime_log
            WHERE 1 = 1
            <if test="level != null and level != ''">AND level = #{level}</if>
            <if test="traceId != null and traceId != ''">AND trace_id = #{traceId}</if>
            <if test="keyword != null and keyword != ''">
                AND (message LIKE CONCAT('%', #{keyword}, '%')
                     OR request_url LIKE CONCAT('%', #{keyword}, '%')
                     OR logger LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            </script>
            """)
    long countRuntimeLogs(
            @Param("level") String level,
            @Param("keyword") String keyword,
            @Param("traceId") String traceId,
            @Param("sinceHours") Integer sinceHours
    );

    @Select("""
            SELECT id, level, logger, trace_id AS traceId, request_method AS requestMethod,
                   request_url AS requestUrl, http_status AS httpStatus, cost, username, ip,
                   message, detail, create_time AS createTime
            FROM sys_runtime_log
            WHERE id = #{id}
            """)
    Map<String, Object> selectRuntimeLogDetail(@Param("id") Long id);

    /** 概览：按级别计数 */
    @Select("""
            <script>
            SELECT level, COUNT(*) AS total
            FROM sys_runtime_log
            WHERE 1 = 1
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            GROUP BY level
            </script>
            """)
    List<Map<String, Object>> countByLevel(@Param("sinceHours") Integer sinceHours);

    @Select("""
            <script>
            SELECT COUNT(*) FROM sys_runtime_log
            WHERE http_status &gt;= 500
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            </script>
            """)
    long countServerErrors(@Param("sinceHours") Integer sinceHours);

    @Select("""
            <script>
            SELECT COUNT(*) FROM sys_runtime_log
            WHERE cost &gt;= 1000
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            </script>
            """)
    long countSlowRequests(@Param("sinceHours") Integer sinceHours);

    /** 最慢的 5 个接口：排障时先看这里，比逐条翻日志快 */
    @Select("""
            <script>
            SELECT request_url AS url, COUNT(*) AS total, ROUND(AVG(cost)) AS avgCost, MAX(cost) AS maxCost
            FROM sys_runtime_log
            WHERE cost IS NOT NULL AND request_url IS NOT NULL
            <if test="sinceHours != null and sinceHours &gt; 0">AND create_time &gt;= NOW() - INTERVAL #{sinceHours} HOUR</if>
            GROUP BY request_url
            ORDER BY maxCost DESC
            LIMIT 5
            </script>
            """)
    List<Map<String, Object>> topSlowRequests(@Param("sinceHours") Integer sinceHours);

    @Delete("DELETE FROM sys_runtime_log WHERE id = #{id}")
    int deleteRuntimeLog(@Param("id") Long id);

    @Delete("DELETE FROM sys_runtime_log")
    int clearRuntimeLogs();

    @Delete("DELETE FROM sys_runtime_log WHERE create_time < NOW() - INTERVAL #{days} DAY")
    int deleteRuntimeLogsBefore(@Param("days") int days);
}
