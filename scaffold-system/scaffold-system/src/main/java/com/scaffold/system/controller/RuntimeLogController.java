package com.scaffold.system.controller;

import com.scaffold.common.domain.PageQuery;
import com.scaffold.common.domain.PageResult;
import com.scaffold.common.domain.R;
import com.scaffold.framework.security.RequiresPermission;
import com.scaffold.system.service.RuntimeLogService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 运行日志。权限码独立于操作日志：堆栈与 SQL 结构只应给运维/超管看。
 */
@RestController
@RequestMapping("/api/monitor/runtime-logs")
public class RuntimeLogController {

    private final RuntimeLogService service;

    public RuntimeLogController(RuntimeLogService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("monitor:runtime:list")
    public R<PageResult<Map<String, Object>>> logs(
            PageQuery query,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false, defaultValue = "24h") String range
    ) {
        return R.success(service.logs(query, level, keyword, traceId, range));
    }

    /** 详情单独取：列表不返回 detail，避免堆栈与 SQL 全文拖慢列表 */
    @GetMapping("/{id}")
    @RequiresPermission("monitor:runtime:list")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.success(service.detail(id));
    }

    @GetMapping("/stats")
    @RequiresPermission("monitor:runtime:list")
    public R<Map<String, Object>> stats(@RequestParam(required = false, defaultValue = "24h") String range) {
        return R.success(service.stats(range));
    }

    @GetMapping("/export")
    @RequiresPermission("monitor:runtime:list")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false, defaultValue = "24h") String range
    ) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"runtime-logs.csv\"")
                .body(service.export(level, keyword, traceId, range));
    }

    /** 清理 N 天前的日志；0 表示全部清空。路径里带天数是因为前端的 delete 不支持 query */
    @DeleteMapping("/before/{days}")
    @RequiresPermission("monitor:runtime:remove")
    public R<Integer> cleanup(@PathVariable Integer days) {
        return R.success(service.cleanup(days == null ? 7 : days));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("monitor:runtime:remove")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.success(null);
    }

    @DeleteMapping
    @RequiresPermission("monitor:runtime:remove")
    public R<Void> clear() {
        service.clear();
        return R.success(null);
    }
}
