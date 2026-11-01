package com.scaffold.generator.service;

import com.scaffold.common.domain.PageQuery;
import com.scaffold.common.domain.PageResult;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.generator.mapper.GeneratorMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class GeneratorService {

    /** 敏感字段：不进列表、不进查询条件，但保留在表单里（渲染成密码框） */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "passwd", "pwd", "salt", "secret", "token", "access_token", "refresh_token");

    /** 审计字段：不让用户在表单里手填，列表里照常展示 */
    private static final Set<String> AUDIT_FIELDS = Set.of("create_by", "update_by", "create_time", "update_time");

    /** 逻辑删除字段：既不进表单也不进列表，只在 SQL 里做过滤 */
    private static final Set<String> HIDDEN_FIELDS = Set.of("deleted", "is_deleted", "del_flag");

    private final GeneratorMapper mapper;

    public GeneratorService(GeneratorMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<Map<String, Object>> tables(PageQuery query, String keyword) {
        Page page = pageOf(query);
        return PageResult.of(booleanRows(mapper.selectTables(keyword, page.offset(), page.pageSize()), "synced"),
                mapper.countTables(keyword), page.pageNum(), page.pageSize());
    }

    public List<Map<String, Object>> columns(Long tableId) {
        ensureTable(tableId);
        return booleanRows(mapper.selectColumns(tableId), "insert", "edit", "list", "query", "required");
    }

    public List<Map<String, Object>> importableTables(String keyword) {
        return booleanRows(mapper.selectImportableTables(keyword), "imported");
    }

    @Transactional
    public Map<String, Object> importTables(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            throw new BusinessException("请选择要导入的数据库表");
        }
        List<Long> ids = new ArrayList<>();
        for (String tableName : tableNames) {
            Long existingId = mapper.selectTableIdByName(tableName);
            if (existingId != null) {
                ids.add(existingId);
                continue;
            }
            Map<String, Object> dbTable = mapper.selectDatabaseTable(tableName);
            if (dbTable == null) {
                throw new BusinessException("数据库表不存在：" + tableName);
            }
            Map<String, Object> row = tableRow(dbTable);
            mapper.insertTable(row, currentUsername());
            Long tableId = numberAsLong(row.get("id"));
            for (Map<String, Object> column : mapper.selectDatabaseColumns(tableName)) {
                mapper.insertColumn(columnRow(tableId, column));
            }
            ids.add(tableId);
        }
        return Map.of("imported", ids.size(), "ids", ids);
    }

    public Map<String, Object> preview(Long tableId) {
        Map<String, Object> table = ensureTable(tableId);
        String className = String.valueOf(table.get("className"));
        String activeFile = className + "Controller.java";
        List<Map<String, Object>> files = new ArrayList<>();
        String activeCode = "";
        for (Map.Entry<String, String> entry : renderFiles(table, mapper.selectColumns(tableId)).entrySet()) {
            String path = entry.getKey();
            String name = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("name", name);
            file.put("path", path);
            file.put("code", entry.getValue());
            files.add(file);
            if (name.equals(activeFile)) activeCode = entry.getValue();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("files", files);
        data.put("activeFile", activeFile);
        data.put("code", activeCode);
        return data;
    }

    @Transactional
    public void updateTable(Long id, Map<String, Object> request) {
        String tableComment = request == null ? null : asString(request.get("tableComment"));
        Integer synced = null;
        if (request != null && request.containsKey("synced")) {
            synced = Boolean.TRUE.equals(request.get("synced")) ? 1 : 0;
        }
        if (mapper.updateTable(id, tableComment, synced, currentUsername()) == 0) {
            throw new BusinessException("代码生成表不存在");
        }
    }

    public Map<String, Object> generate(Long tableId) {
        Map<String, Object> table = ensureTable(tableId);
        return Map.of(
                "tableName", table.get("tableName"),
                "message", "代码压缩包已准备就绪"
        );
    }

    public byte[] download(Long tableId) {
        Map<String, Object> table = ensureTable(tableId);
        List<Map<String, Object>> columns = mapper.selectColumns(tableId);
        Map<String, String> files = renderFiles(table, columns);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException error) {
            throw new BusinessException("代码压缩包生成失败");
        }
    }

    /* ============================================================
       以下为代码模板：产出「解压落位 → 重启即可增删改查」的完整模块。
       占位符使用 @@name@@ 形式，避免与 Java 注解、Vue 插值语法冲突。
       ============================================================ */

    private Map<String, String> renderFiles(Map<String, Object> table, List<Map<String, Object>> rawColumns) {
        String tableName = String.valueOf(table.get("tableName"));
        String className = String.valueOf(table.get("className"));
        String module = String.valueOf(table.get("module"));
        String resource = decapitalize(className);
        String comment = tableCommentOf(table);
        String menuName = menuNameOf(table);

        List<Col> cols = buildCols(rawColumns);
        Col key = cols.stream().filter(Col::primary).findFirst().orElse(cols.get(0));
        boolean autoKey = "id".equalsIgnoreCase(key.column()) && "Long".equals(key.javaType());
        boolean softDelete = cols.stream().anyMatch(c -> HIDDEN_FIELDS.contains(c.column()));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("backend/src/main/java/com/scaffold/%s/domain/%s.java".formatted(module, className), entityCode(module, className, tableName, comment, cols));
        files.put("backend/src/main/java/com/scaffold/%s/domain/dto/%sQuery.java".formatted(module, className), queryCode(module, className, comment, cols));
        files.put("backend/src/main/java/com/scaffold/%s/mapper/%sMapper.java".formatted(module, className), mapperCode(module, className, tableName, cols, key, autoKey, softDelete));
        files.put("backend/src/main/java/com/scaffold/%s/service/%sService.java".formatted(module, className), serviceCode(module, className, comment, cols, key));
        files.put("backend/src/main/java/com/scaffold/%s/controller/%sController.java".formatted(module, className), controllerCode(module, className, resource, comment, menuName, cols, key));
        files.put("frontend/src/api/%s/%s.ts".formatted(module, resource), apiCode(module, className, resource, comment, cols));
        files.put("frontend/src/views/%s/%s/index.vue".formatted(module, resource), viewCode(module, className, resource, comment, menuName, cols));
        files.put("sql/%s_menu.sql".formatted(tableName), menuSql(tableName, module, resource, menuName, key));
        files.put("README.md", readme(module, className, resource, tableName, comment, menuName));
        return files;
    }

    /* ── 实体 ── */
    private String entityCode(String module, String className, String tableName, String comment, List<Col> cols) {
        boolean needsBigDecimal = cols.stream().anyMatch(c -> "BigDecimal".equals(c.javaType()));
        boolean needsDateTime = cols.stream().anyMatch(c -> "LocalDateTime".equals(c.javaType()));
        StringBuilder code = new StringBuilder();
        code.append("package com.scaffold.").append(module).append(".domain;\n\n");
        if (needsBigDecimal) {
            code.append("import java.math.BigDecimal;\n");
        }
        if (needsDateTime) {
            code.append("import java.time.LocalDateTime;\n");
        }
        if (needsBigDecimal || needsDateTime) {
            code.append('\n');
        }
        code.append("/**\n * ").append(comment).append(" 实体，对应表 ").append(tableName).append("。\n */\n");
        code.append("public class ").append(className).append(" {\n\n");
        for (Col col : cols) {
            code.append("    /** ").append(col.comment()).append(" */\n");
            code.append("    private ").append(col.javaType()).append(' ').append(col.field()).append(";\n\n");
        }
        for (Col col : cols) {
            String cap = capitalize(col.field());
            code.append("    public ").append(col.javaType()).append(" get").append(cap)
                    .append("() { return ").append(col.field()).append("; }\n");
            code.append("    public void set").append(cap).append('(').append(col.javaType()).append(' ').append(col.field())
                    .append(") { this.").append(col.field()).append(" = ").append(col.field()).append("; }\n");
            code.append('\n');
        }
        code.append("}\n");
        return code.toString();
    }

    /* ── 查询条件 DTO ── */
    private String queryCode(String module, String className, String comment, List<Col> cols) {
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        StringBuilder code = new StringBuilder();
        code.append("package com.scaffold.").append(module).append(".domain.dto;\n\n");
        code.append("/**\n * ").append(comment).append(" 列表查询条件。\n */\n");
        code.append("public class ").append(className).append("Query {\n\n");
        if (queryCols.isEmpty()) {
            code.append("    /** 预留扩展：当前没有可查询字段 */\n    private String keyword;\n\n");
            code.append("    public String getKeyword() { return keyword; }\n");
            code.append("    public void setKeyword(String keyword) { this.keyword = keyword; }\n");
        }
        for (Col col : queryCols) {
            String type = "LIKE".equalsIgnoreCase(col.queryType()) ? "String" : col.javaType();
            code.append("    /** ").append(col.comment()).append(" */\n");
            code.append("    private ").append(type).append(' ').append(col.field()).append(";\n\n");
        }
        for (Col col : queryCols) {
            String type = "LIKE".equalsIgnoreCase(col.queryType()) ? "String" : col.javaType();
            String cap = capitalize(col.field());
            code.append("    public ").append(type).append(" get").append(cap).append("() { return ").append(col.field()).append("; }\n");
            code.append("    public void set").append(cap).append('(').append(type).append(' ').append(col.field())
                    .append(") { this.").append(col.field()).append(" = ").append(col.field()).append("; }\n\n");
        }
        code.append("}\n");
        return code.toString();
    }

    /* ── Mapper：注解式动态 SQL ── */
    private String mapperCode(String module, String className, String tableName, List<Col> cols, Col key,
                              boolean autoKey, boolean softDelete) {
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        List<Col> formCols = cols.stream().filter(c -> c.insert() || c.edit()).toList();
        String selectList = join(cols.stream().map(Col::column).toList());
        String where = whereClause(queryCols, softDelete);
        String quote = "\"\"\"";

        StringBuilder code = new StringBuilder();
        code.append("package com.scaffold.").append(module).append(".mapper;\n\n");
        code.append("import com.scaffold.").append(module).append(".domain.").append(className).append(";\n");
        if (!queryCols.isEmpty()) {
            code.append("import com.scaffold.").append(module).append(".domain.dto.").append(className).append("Query;\n");
        }
        code.append("import org.apache.ibatis.annotations.Delete;\n");
        code.append("import org.apache.ibatis.annotations.Insert;\n");
        code.append("import org.apache.ibatis.annotations.Mapper;\n");
        if (autoKey) {
            code.append("import org.apache.ibatis.annotations.Options;\n");
        }
        code.append("import org.apache.ibatis.annotations.Param;\n");
        code.append("import org.apache.ibatis.annotations.Select;\n");
        code.append("import org.apache.ibatis.annotations.Update;\n\n");
        code.append("import java.util.List;\n\n");
        code.append("@Mapper\npublic interface ").append(className).append("Mapper {\n\n");

        // count
        code.append("    @Select(").append(quote).append('\n');
        code.append("            <script>\n");
        code.append("            SELECT COUNT(*)\n");
        code.append("            FROM ").append(tableName).append('\n');
        code.append(where);
        code.append("            </script>\n");
        code.append("            ").append(quote).append(")\n");
        code.append("    long count(").append(queryCols.isEmpty() ? "" : "@Param(\"filter\") " + className + "Query filter").append(");\n\n");

        // selectPage
        code.append("    @Select(").append(quote).append('\n');
        code.append("            <script>\n");
        code.append("            SELECT ").append(selectList).append('\n');
        code.append("            FROM ").append(tableName).append('\n');
        code.append(where);
        code.append("            ORDER BY ").append(key.column()).append(" DESC\n");
        code.append("            LIMIT #{offset}, #{pageSize}\n");
        code.append("            </script>\n");
        code.append("            ").append(quote).append(")\n");
        code.append("    List<").append(className).append("> selectPage(");
        if (!queryCols.isEmpty()) {
            code.append("@Param(\"filter\") ").append(className).append("Query filter, ");
        }
        code.append("@Param(\"offset\") int offset, @Param(\"pageSize\") int pageSize);\n\n");

        // selectById
        code.append("    @Select(").append(quote).append('\n');
        code.append("            SELECT ").append(selectList).append('\n');
        code.append("            FROM ").append(tableName).append('\n');
        code.append("            WHERE ").append(key.column()).append(" = #{").append(key.field()).append("}\n");
        if (softDelete) {
            code.append("              AND deleted = 0\n");
        }
        code.append("            ").append(quote).append(")\n");
        code.append("    ").append(className).append(" selectById(@Param(\"").append(key.field()).append("\") ")
                .append(key.javaType()).append(' ').append(key.field()).append(");\n\n");

        // insert
        code.append("    @Insert(").append(quote).append('\n');
        code.append("            <script>\n");
        code.append("            INSERT INTO ").append(tableName).append('\n');
        code.append("            <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (Col col : formCols) {
            code.append("                <if test=\"").append(col.field()).append(" != null\">").append(col.column()).append(",</if>\n");
        }
        code.append("            </trim>\n");
        code.append("            <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");
        for (Col col : formCols) {
            code.append("                <if test=\"").append(col.field()).append(" != null\">#{").append(col.field()).append("},</if>\n");
        }
        code.append("            </trim>\n");
        code.append("            </script>\n");
        code.append("            ").append(quote).append(")\n");
        if (autoKey) {
            code.append("    @Options(useGeneratedKeys = true, keyProperty = \"").append(key.field()).append("\")\n");
        }
        code.append("    int insert(").append(className).append(" row);\n\n");

        // update
        code.append("    @Update(").append(quote).append('\n');
        code.append("            <script>\n");
        code.append("            UPDATE ").append(tableName).append('\n');
        code.append("            <set>\n");
        for (Col col : formCols) {
            code.append("                <if test=\"").append(col.field()).append(" != null\">").append(col.column())
                    .append(" = #{").append(col.field()).append("},</if>\n");
        }
        code.append("            </set>\n");
        code.append("            WHERE ").append(key.column()).append(" = #{").append(key.field()).append("}\n");
        if (softDelete) {
            code.append("              AND deleted = 0\n");
        }
        code.append("            </script>\n");
        code.append("            ").append(quote).append(")\n");
        code.append("    int update(").append(className).append(" row);\n\n");

        // delete
        if (softDelete) {
            code.append("    @Update(\"UPDATE ").append(tableName).append(" SET deleted = 1 WHERE ").append(key.column())
                    .append(" = #{").append(key.field()).append("}\")\n");
            code.append("    int deleteById(@Param(\"").append(key.field()).append("\") ").append(key.javaType()).append(' ').append(key.field()).append(");\n\n");
            code.append("    @Update(").append(quote).append('\n');
            code.append("            <script>\n");
            code.append("            UPDATE ").append(tableName).append(" SET deleted = 1\n");
            code.append("            WHERE ").append(key.column()).append(" IN\n");
            code.append("            <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>\n");
            code.append("            </script>\n");
            code.append("            ").append(quote).append(")\n");
        } else {
            code.append("    @Delete(\"DELETE FROM ").append(tableName).append(" WHERE ").append(key.column())
                    .append(" = #{").append(key.field()).append("}\")\n");
            code.append("    int deleteById(@Param(\"").append(key.field()).append("\") ").append(key.javaType()).append(' ').append(key.field()).append(");\n\n");
            code.append("    @Delete(").append(quote).append('\n');
            code.append("            <script>\n");
            code.append("            DELETE FROM ").append(tableName).append('\n');
            code.append("            WHERE ").append(key.column()).append(" IN\n");
            code.append("            <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>\n");
            code.append("            </script>\n");
            code.append("            ").append(quote).append(")\n");
        }
        code.append("    int deleteByIds(@Param(\"ids\") List<").append(key.javaType()).append("> ids);\n");
        code.append("}\n");
        return code.toString();
    }

    private String whereClause(List<Col> queryCols, boolean softDelete) {
        StringBuilder where = new StringBuilder();
        where.append("            WHERE 1 = 1\n");
        if (softDelete) {
            where.append("              AND deleted = 0\n");
        }
        for (Col col : queryCols) {
            boolean like = "LIKE".equalsIgnoreCase(col.queryType());
            String expression = like ? "String" : col.javaType();
            if ("String".equals(expression)) {
                where.append("            <if test=\"filter.").append(col.field()).append(" != null and filter.")
                        .append(col.field()).append(" != ''\">\n");
            } else {
                where.append("            <if test=\"filter.").append(col.field()).append(" != null\">\n");
            }
            if (like) {
                where.append("                AND ").append(col.column()).append(" LIKE CONCAT('%', #{filter.")
                        .append(col.field()).append("}, '%')\n");
            } else {
                where.append("                AND ").append(col.column()).append(" = #{filter.").append(col.field()).append("}\n");
            }
            where.append("            </if>\n");
        }
        return where.toString();
    }

    /* ── Service ── */
    private String serviceCode(String module, String className, String comment, List<Col> cols, Col key) {
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        List<Col> requiredCols = cols.stream().filter(c -> c.required() && (c.insert() || c.edit())).toList();
        List<Col> stringCols = cols.stream()
                .filter(c -> "String".equals(c.javaType()))
                .filter(c -> !AUDIT_FIELDS.contains(c.column().toLowerCase(Locale.ROOT)))
                .toList();
        String keyType = key.javaType();
        String keyField = key.field();
        String keyCap = capitalize(keyField);

        StringBuilder code = new StringBuilder();
        code.append("package com.scaffold.").append(module).append(".service;\n\n");
        code.append("import com.scaffold.common.domain.PageQuery;\n");
        code.append("import com.scaffold.common.domain.PageResult;\n");
        code.append("import com.scaffold.common.exception.BusinessException;\n");
        code.append("import com.scaffold.").append(module).append(".domain.").append(className).append(";\n");
        if (!queryCols.isEmpty()) {
            code.append("import com.scaffold.").append(module).append(".domain.dto.").append(className).append("Query;\n");
        }
        code.append("import com.scaffold.").append(module).append(".mapper.").append(className).append("Mapper;\n");
        code.append("import org.springframework.stereotype.Service;\n");
        code.append("import org.springframework.transaction.annotation.Transactional;\n\n");
        code.append("import java.util.List;\n\n");
        code.append("/**\n * ").append(comment).append(" 业务逻辑。\n */\n");
        code.append("@Service\n");
        code.append("public class ").append(className).append("Service {\n\n");
        code.append("    private final ").append(className).append("Mapper mapper;\n\n");
        code.append("    public ").append(className).append("Service(").append(className).append("Mapper mapper) {\n");
        code.append("        this.mapper = mapper;\n");
        code.append("    }\n\n");

        // page
        code.append("    public PageResult<").append(className).append("> page(PageQuery query")
                .append(queryCols.isEmpty() ? "" : ", " + className + "Query filter").append(") {\n");
        code.append("        int pageNum = query == null ? PageQuery.DEFAULT_PAGE_NUM : query.normalizedPageNum();\n");
        code.append("        int pageSize = query == null ? PageQuery.DEFAULT_PAGE_SIZE : query.normalizedPageSize();\n");
        code.append("        long total = mapper.count(").append(queryCols.isEmpty() ? "" : "filter").append(");\n");
        code.append("        if (total == 0) {\n");
        code.append("            return PageResult.of(List.of(), 0, pageNum, pageSize);\n");
        code.append("        }\n");
        code.append("        List<").append(className).append("> rows = mapper.selectPage(")
                .append(queryCols.isEmpty() ? "" : "filter, ").append("(pageNum - 1) * pageSize, pageSize);\n");
        code.append("        return PageResult.of(rows, total, pageNum, pageSize);\n");
        code.append("    }\n\n");

        // detail
        code.append("    public ").append(className).append(" detail(").append(keyType).append(' ').append(keyField).append(") {\n");
        code.append("        ").append(className).append(" row = mapper.selectById(").append(keyField).append(");\n");
        code.append("        if (row == null) {\n");
        code.append("            throw new BusinessException(\"记录不存在或已被删除\");\n");
        code.append("        }\n");
        code.append("        return row;\n");
        code.append("    }\n\n");

        // create
        code.append("    @Transactional\n");
        code.append("    public ").append(keyType).append(" create(").append(className).append(" row) {\n");
        code.append("        normalize(row);\n");
        code.append("        validate(row, true);\n");
        code.append("        mapper.insert(row);\n");
        code.append("        return row.get").append(keyCap).append("();\n");
        code.append("    }\n\n");

        // update
        code.append("    @Transactional\n");
        code.append("    public void update(").append(keyType).append(' ').append(keyField).append(", ").append(className).append(" row) {\n");
        code.append("        detail(").append(keyField).append(");\n");
        code.append("        normalize(row);\n");
        code.append("        validate(row, false);\n");
        code.append("        row.set").append(keyCap).append('(').append(keyField).append(");\n");
        code.append("        mapper.update(row);\n");
        code.append("    }\n\n");

        // delete
        code.append("    @Transactional\n");
        code.append("    public void delete(").append(keyType).append(' ').append(keyField).append(") {\n");
        code.append("        detail(").append(keyField).append(");\n");
        code.append("        mapper.deleteById(").append(keyField).append(");\n");
        code.append("    }\n\n");

        code.append("    @Transactional\n");
        code.append("    public void deleteBatch(List<").append(keyType).append("> ids) {\n");
        code.append("        if (ids == null || ids.isEmpty()) {\n");
        code.append("            throw new BusinessException(\"请选择要删除的记录\");\n");
        code.append("        }\n");
        code.append("        mapper.deleteByIds(ids);\n");
        code.append("    }\n\n");

        // normalize
        code.append("    private void normalize(").append(className).append(" row) {\n");
        if (stringCols.isEmpty()) {
            code.append("        // 当前表没有字符串字段，无需清洗\n");
        }
        for (Col col : stringCols) {
            code.append("        if (row.get").append(capitalize(col.field())).append("() != null) {\n");
            code.append("            row.set").append(capitalize(col.field())).append("(row.get").append(capitalize(col.field())).append("().trim());\n");
            code.append("        }\n");
        }
        code.append("    }\n\n");

        // validate：新增要求必填项齐全；更新允许只传部分字段，仅在显式传空时报错
        code.append("    private void validate(").append(className).append(" row, boolean create) {\n");
        if (requiredCols.isEmpty()) {
            code.append("        // 当前表没有非空字段\n");
        }
        for (Col col : requiredCols) {
            String cap = capitalize(col.field());
            if ("String".equals(col.javaType())) {
                code.append("        if (create && (row.get").append(cap).append("() == null || row.get").append(cap).append("().isBlank())) {\n");
                code.append("            throw new BusinessException(\"请输入").append(col.comment()).append("\");\n");
                code.append("        }\n");
                code.append("        if (!create && row.get").append(cap).append("() != null && row.get").append(cap).append("().isBlank()) {\n");
                code.append("            throw new BusinessException(\"请输入").append(col.comment()).append("\");\n");
                code.append("        }\n");
            } else {
                code.append("        if (create && row.get").append(cap).append("() == null) {\n");
                code.append("            throw new BusinessException(\"请输入").append(col.comment()).append("\");\n");
                code.append("        }\n");
            }
        }
        code.append("    }\n");
        code.append("}\n");
        return code.toString();
    }

    /* ── Controller ── */
    private String controllerCode(String module, String className, String resource, String comment, String menuName,
                                  List<Col> cols, Col key) {
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        String permission = module + ":" + resource;
        String keyType = key.javaType();
        String keyField = key.field();

        StringBuilder code = new StringBuilder();
        code.append("package com.scaffold.").append(module).append(".controller;\n\n");
        code.append("import com.scaffold.common.domain.PageQuery;\n");
        code.append("import com.scaffold.common.domain.PageResult;\n");
        code.append("import com.scaffold.common.domain.R;\n");
        code.append("import com.scaffold.framework.audit.OperLog;\n");
        code.append("import com.scaffold.framework.security.RequiresPermission;\n");
        code.append("import com.scaffold.").append(module).append(".domain.").append(className).append(";\n");
        if (!queryCols.isEmpty()) {
            code.append("import com.scaffold.").append(module).append(".domain.dto.").append(className).append("Query;\n");
        }
        code.append("import com.scaffold.").append(module).append(".service.").append(className).append("Service;\n");
        code.append("import org.springframework.web.bind.annotation.DeleteMapping;\n");
        code.append("import org.springframework.web.bind.annotation.GetMapping;\n");
        code.append("import org.springframework.web.bind.annotation.PathVariable;\n");
        code.append("import org.springframework.web.bind.annotation.PostMapping;\n");
        code.append("import org.springframework.web.bind.annotation.PutMapping;\n");
        code.append("import org.springframework.web.bind.annotation.RequestBody;\n");
        code.append("import org.springframework.web.bind.annotation.RequestMapping;\n");
        code.append("import org.springframework.web.bind.annotation.RestController;\n\n");
        code.append("import java.util.List;\n\n");
        code.append("/**\n * ").append(comment).append(" 接口。\n */\n");
        code.append("@RestController\n");
        code.append("@RequestMapping(\"/api/").append(module).append('/').append(resource).append("\")\n");
        code.append("public class ").append(className).append("Controller {\n\n");
        code.append("    private final ").append(className).append("Service service;\n\n");
        code.append("    public ").append(className).append("Controller(").append(className).append("Service service) {\n");
        code.append("        this.service = service;\n");
        code.append("    }\n\n");

        code.append("    @GetMapping\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":list\")\n");
        code.append("    public R<PageResult<").append(className).append(">> list(PageQuery query")
                .append(queryCols.isEmpty() ? "" : ", " + className + "Query filter").append(") {\n");
        code.append("        return R.success(service.page(query").append(queryCols.isEmpty() ? "" : ", filter").append("));\n");
        code.append("    }\n\n");

        code.append("    @GetMapping(\"/{").append(keyField).append("}\")\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":list\")\n");
        code.append("    public R<").append(className).append("> detail(@PathVariable ").append(keyType).append(' ').append(keyField).append(") {\n");
        code.append("        return R.success(service.detail(").append(keyField).append("));\n");
        code.append("    }\n\n");

        code.append("    @PostMapping\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":add\")\n");
        code.append("    @OperLog(module = \"").append(menuName).append("\", action = \"新增\")\n");
        code.append("    public R<").append(keyType).append("> create(@RequestBody ").append(className).append(" request) {\n");
        code.append("        return R.success(service.create(request));\n");
        code.append("    }\n\n");

        code.append("    @PutMapping(\"/{").append(keyField).append("}\")\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":edit\")\n");
        code.append("    @OperLog(module = \"").append(menuName).append("\", action = \"修改\")\n");
        code.append("    public R<Void> update(@PathVariable ").append(keyType).append(' ').append(keyField)
                .append(", @RequestBody ").append(className).append(" request) {\n");
        code.append("        service.update(").append(keyField).append(", request);\n");
        code.append("        return R.success(null);\n");
        code.append("    }\n\n");

        code.append("    @DeleteMapping(\"/{").append(keyField).append("}\")\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":remove\")\n");
        code.append("    @OperLog(module = \"").append(menuName).append("\", action = \"删除\")\n");
        code.append("    public R<Void> remove(@PathVariable ").append(keyType).append(' ').append(keyField).append(") {\n");
        code.append("        service.delete(").append(keyField).append(");\n");
        code.append("        return R.success(null);\n");
        code.append("    }\n\n");

        code.append("    @PostMapping(\"/batch-remove\")\n");
        code.append("    @RequiresPermission(\"").append(permission).append(":remove\")\n");
        code.append("    @OperLog(module = \"").append(menuName).append("\", action = \"批量删除\")\n");
        code.append("    public R<Void> removeBatch(@RequestBody List<").append(keyType).append("> ids) {\n");
        code.append("        service.deleteBatch(ids);\n");
        code.append("        return R.success(null);\n");
        code.append("    }\n");
        code.append("}\n");
        return code.toString();
    }

    /* ── 前端 api ── */
    private String apiCode(String module, String className, String resource, String comment, List<Col> cols) {
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        List<Col> formCols = cols.stream().filter(c -> c.insert() || c.edit()).toList();
        String base = "/" + module + "/" + resource;
        StringBuilder code = new StringBuilder();
        code.append("import { httpClient } from '@/api/http'\n");
        code.append("import type { QueryParams } from '@/api/http'\n");
        code.append("import type { PageResult } from '@/api'\n\n");
        code.append("/** ").append(comment).append(" 接口封装 */\n\n");
        code.append("export interface ").append(className).append("Row {\n");
        for (Col col : cols) {
            code.append("  ").append(col.field()).append(": ").append(tsType(col.javaType())).append('\n');
        }
        code.append("}\n\n");
        code.append("export interface ").append(className).append("Query extends QueryParams {\n");
        code.append("  pageNum?: number\n");
        code.append("  pageSize?: number\n");
        for (Col col : queryCols) {
            code.append("  ").append(col.field()).append("?: ").append(tsType("LIKE".equalsIgnoreCase(col.queryType()) ? "String" : col.javaType())).append(" | null\n");
        }
        code.append("}\n\n");
        code.append("export interface ").append(className).append("WriteRequest {\n");
        for (Col col : formCols) {
            code.append("  ").append(col.field()).append("?: ").append(tsType(col.javaType())).append(" | null\n");
        }
        code.append("}\n\n");
        code.append("export const ").append(resource).append("Api = {\n");
        code.append("  list: (query?: ").append(className).append("Query) => httpClient.get<PageResult<").append(className).append("Row>>('").append(base).append("', query),\n");
        code.append("  detail: (id: number) => httpClient.get<").append(className).append("Row>(`").append(base).append("/${id}`),\n");
        code.append("  create: (body: ").append(className).append("WriteRequest) => httpClient.post<number>('").append(base).append("', body),\n");
        code.append("  update: (id: number, body: ").append(className).append("WriteRequest) => httpClient.put<void>(`").append(base).append("/${id}`, body),\n");
        code.append("  remove: (id: number) => httpClient.delete<void>(`").append(base).append("/${id}`),\n");
        code.append("  removeBatch: (ids: number[]) => httpClient.post<void>('").append(base).append("/batch-remove', ids),\n");
        code.append("}\n");
        return code.toString();
    }

    /* ── 前端页面 ── */
    private String viewCode(String module, String className, String resource, String comment, String menuName, List<Col> cols) {
        List<Col> listCols = cols.stream().filter(Col::list).toList();
        List<Col> queryCols = cols.stream().filter(Col::query).toList();
        List<Col> formCols = cols.stream().filter(c -> c.insert() || c.edit()).toList();
        List<Col> requiredCols = formCols.stream().filter(Col::required).toList();
        boolean hasStatus = cols.stream().anyMatch(GeneratorService::isStatus);
        boolean statusListed = listCols.stream().anyMatch(GeneratorService::isStatus);
        boolean statusForm = formCols.stream().anyMatch(GeneratorService::isStatus);
        boolean statusQuery = queryCols.stream().anyMatch(GeneratorService::isStatus);

        List<String> uiImports = new ArrayList<>();
        uiImports.add("Field");
        if (statusListed) {
            uiImports.add("StatusTag");
        }
        if (statusForm) {
            uiImports.add("Switch");
        }
        if (statusQuery) {
            uiImports.add("Select");
        }
        uiImports.add("TextInput");
        uiImports.add("type Column");

        StringBuilder columns = new StringBuilder();
        for (Col col : listCols) {
            columns.append("  { key: '").append(col.field()).append("', title: '").append(col.comment()).append("'");
            if (isNumeric(col.javaType())) {
                columns.append(", width: 110, align: 'right'");
            }
            if ("LocalDateTime".equals(col.javaType())) {
                columns.append(", width: 170");
            }
            columns.append(" },\n");
        }
        columns.append("  { key: 'actions', title: '操作', width: 96, className: 'col-actions' },\n");

        StringBuilder rowFields = new StringBuilder();
        for (Col col : cols) {
            rowFields.append("  ").append(col.field()).append(": ").append(tsType(col.javaType())).append('\n');
        }

        StringBuilder queryFields = new StringBuilder();
        for (Col col : queryCols) {
            queryFields.append("  ").append(col.field()).append(": ")
                    .append("LIKE".equalsIgnoreCase(col.queryType()) ? "string" : tsType(col.javaType())).append(" | null\n");
        }
        if (queryCols.isEmpty()) {
            queryFields.append("  keyword: string | null\n");
        }

        StringBuilder queryInit = new StringBuilder("{ ");
        if (queryCols.isEmpty()) {
            queryInit.append("keyword: '' ");
        }
        for (Col col : queryCols) {
            queryInit.append(col.field()).append(": ").append("LIKE".equalsIgnoreCase(col.queryType()) ? "''" : "null").append(", ");
        }
        String queryInitText = queryInit.toString().replaceAll(", $", " ").trim() + " }";

        StringBuilder searchFields = new StringBuilder();
        for (Col col : queryCols) {
            String label = col.comment();
            searchFields.append("      <Field label=\"").append(label).append("\">\n");
            if (isStatus(col)) {
                searchFields.append("        <Select v-model=\"list.query.").append(col.field()).append("\" :options=\"STATUS_OPTIONS\" clearable placeholder=\"全部状态\" />\n");
            } else if ("LIKE".equalsIgnoreCase(col.queryType())) {
                searchFields.append("        <TextInput v-model=\"list.query.").append(col.field())
                        .append("\" icon=\"search\" placeholder=\"请输入").append(label).append("\" clearable @enter=\"list.search()\" />\n");
            } else if (isNumeric(col.javaType())) {
                searchFields.append("        <TextInput v-model=\"list.query.").append(col.field())
                        .append("\" type=\"number\" placeholder=\"").append(label).append("\" />\n");
            } else {
                searchFields.append("        <TextInput v-model=\"list.query.").append(col.field())
                        .append("\" placeholder=\"").append(label).append("\" clearable @enter=\"list.search()\" />\n");
            }
            searchFields.append("      </Field>\n");
        }

        StringBuilder formInterface = new StringBuilder("{ id?: number");
        StringBuilder formInit = new StringBuilder("{ ");
        StringBuilder formFields = new StringBuilder();
        StringBuilder payload = new StringBuilder("{ ");
        StringBuilder rules = new StringBuilder();
        StringBuilder editAssign = new StringBuilder("{ id: row.id");
        for (Col col : formCols) {
            String field = col.field();
            String ts = isNumeric(col.javaType()) ? "number | null" : tsType(col.javaType());
            formInterface.append("; ").append(field).append(": ").append(ts);
            if (isStatus(col)) {
                formInit.append(field).append(": 1, ");
            } else if (isNumeric(col.javaType())) {
                formInit.append(field).append(": null, ");
            } else {
                formInit.append(field).append(": '', ");
            }
            editAssign.append(", ").append(field).append(": row.").append(field);
            payload.append(field).append(": form.").append(field).append(", ");
            if (col.required()) {
                rules.append("    ").append(field).append(": [required('请输入").append(col.comment()).append("')],\n");
            }
        }
        String formInterfaceText = formInterface + " }";
        String formInitText = formInit.toString().replaceAll(", $", " ") + "}";
        String payloadText = payload.toString().replaceAll(", $", " ") + "}";
        String editAssignText = editAssign + " }";

        for (Col col : formCols) {
            String field = col.field();
            String label = col.comment();
            boolean requiredMark = col.required();
            if (isStatus(col)) {
                formFields.append("      <Field label=\"").append(label).append("\" group>\n");
                formFields.append("        <Switch :model-value=\"form.").append(field).append(" === 1\" :label=\"form.")
                        .append(field).append(" === 1 ? '正常' : '停用'\" @update:model-value=\"form.").append(field)
                        .append(" = $event ? 1 : 0\" />\n");
                formFields.append("      </Field>\n");
                continue;
            }
            String formType = col.formType();
            boolean multiline = formType.contains("文本域");
            boolean secret = SENSITIVE_FIELDS.contains(col.column());
            formFields.append("      <Field label=\"").append(label).append('"');
            if (requiredMark) {
                formFields.append(" required");
            }
            if (requiredMark) {
                formFields.append(" :error=\"errors.").append(field).append('"');
            }
            if (multiline) {
                formFields.append(" span");
            }
            formFields.append(">\n");
            if (multiline) {
                formFields.append("        <TextInput v-model=\"form.").append(field)
                        .append("\" multiline :rows=\"3\" placeholder=\"请输入").append(label).append("\" />\n");
            } else if (secret) {
                formFields.append("        <TextInput v-model=\"form.").append(field)
                        .append("\" type=\"password\" revealable placeholder=\"请输入").append(label).append("\" />\n");
            } else if (isNumeric(col.javaType())) {
                formFields.append("        <TextInput v-model=\"form.").append(field)
                        .append("\" type=\"number\" placeholder=\"请输入").append(label).append('"');
                if (requiredMark) {
                    formFields.append(" :invalid=\"!!errors.").append(field).append('"');
                }
                formFields.append(" />\n");
            } else {
                formFields.append("        <TextInput v-model=\"form.").append(field)
                        .append("\" placeholder=\"");
                formFields.append("LocalDateTime".equals(col.javaType()) ? "YYYY-MM-DD HH:mm:ss" : "请输入" + label).append('"');
                if (requiredMark) {
                    formFields.append(" :invalid=\"!!errors.").append(field).append('"');
                }
                formFields.append(" />\n");
            }
            formFields.append("      </Field>\n");
        }
        if (formCols.isEmpty()) {
            formFields.append("      <Field label=\"提示\" span>\n");
            formFields.append("        <p class=\"muted\">该表没有可编辑字段，请在 gen_table_column 中开启字段后重新生成。</p>\n");
            formFields.append("      </Field>\n");
        }

        StringBuilder cellSlots = new StringBuilder();
        for (Col col : listCols) {
            if (isStatus(col)) {
                cellSlots.append("    <template #cell-").append(col.field()).append("=\"{ row }\">")
                        .append("<StatusTag :status=\"Number(row.").append(col.field()).append(")\" /></template>\n");
            }
        }
        cellSlots.append("    <template #cell-actions=\"{ row }\">\n");
        cellSlots.append("      <div class=\"row-actions\">\n");
        cellSlots.append("        <PermissionButton perm=\"").append(module).append(':').append(resource)
                .append(":edit\" icon-only icon=\"edit\" label=\"编辑\" @click=\"openEdit(row)\" />\n");
        cellSlots.append("        <PermissionButton perm=\"").append(module).append(':').append(resource)
                .append(":remove\" icon-only icon=\"trash\" label=\"删除\" danger @click=\"remove(row)\" />\n");
        cellSlots.append("      </div>\n");
        cellSlots.append("    </template>\n");

        StringBuilder code = new StringBuilder();
        code.append("<script setup lang=\"ts\">\n");
        code.append("import { reactive, ref } from 'vue'\n");
        code.append("import { ").append(join(uiImports)).append(" } from '@/components/ui'\n");
        code.append("import { FormDialog, ListPage, PermissionButton } from '@/components/business'\n");
        code.append("import { ").append(resource).append("Api } from '@/api/").append(module).append('/').append(resource).append("'\n");
        code.append("import { useListPage } from '@/composables/list'\n");
        code.append("import { confirm } from '@/composables/confirm'\n");
        code.append("import { pushToast, toastError } from '@/composables/toast'\n");
        code.append("import { required, validate } from '@/utils/validate'\n\n");
        code.append("interface ").append(className).append("Row {\n").append(rowFields).append("}\n\n");
        code.append("type ").append(className).append("Query = {\n").append(queryFields).append("}\n\n");
        if (statusQuery) {
            code.append("const STATUS_OPTIONS = [\n  { label: '正常', value: 1 },\n  { label: '停用', value: 0 },\n]\n\n");
        }
        code.append("const list = useListPage<").append(className).append("Row, ").append(className).append("Query>({\n");
        code.append("  query: ").append(queryInitText).append(",\n");
        code.append("  fetch: params => ").append(resource).append("Api.list(params),\n");
        code.append("})\n\n");
        code.append("const columns: Column[] = [\n").append(columns).append("]\n\n");
        code.append("interface ").append(className).append("Form ").append(formInterfaceText).append("\n");
        code.append("const emptyForm = (): ").append(className).append("Form => (").append(formInitText).append(")\n");
        code.append("const dialog = ref(false)\n");
        code.append("const saving = ref(false)\n");
        code.append("const form = reactive<").append(className).append("Form>(emptyForm())\n");
        code.append("const errors = ref<Partial<Record<keyof ").append(className).append("Form, string>>>({})\n\n");
        code.append("function openCreate() {\n");
        code.append("  Object.assign(form, emptyForm(), { id: undefined })\n");
        code.append("  errors.value = {}\n");
        code.append("  dialog.value = true\n");
        code.append("}\n\n");
        code.append("function openEdit(row: ").append(className).append("Row) {\n");
        code.append("  Object.assign(form, ").append(editAssignText).append(")\n");
        code.append("  errors.value = {}\n");
        code.append("  dialog.value = true\n");
        code.append("}\n\n");
        code.append("async function submit() {\n");
        code.append("  errors.value = validate(form, {\n").append(rules).append("  })\n");
        code.append("  if (Object.keys(errors.value).length) return\n");
        code.append("  saving.value = true\n");
        code.append("  const payload = ").append(payloadText).append("\n");
        code.append("  try {\n");
        code.append("    if (form.id) {\n");
        code.append("      await ").append(resource).append("Api.update(form.id, payload)\n");
        code.append("      pushToast('").append(menuName).append("已更新')\n");
        code.append("    } else {\n");
        code.append("      await ").append(resource).append("Api.create(payload)\n");
        code.append("      pushToast('").append(menuName).append("已新增')\n");
        code.append("    }\n");
        code.append("    dialog.value = false\n");
        code.append("    await list.load()\n");
        code.append("  } catch (error) {\n");
        code.append("    toastError(error, '").append(menuName).append("保存失败')\n");
        code.append("  } finally {\n");
        code.append("    saving.value = false\n");
        code.append("  }\n");
        code.append("}\n\n");
        code.append("async function remove(row: ").append(className).append("Row) {\n");
        code.append("  const label = String(row.").append(listCols.isEmpty() ? "id" : listCols.get(0).field()).append(" ?? row.id)\n");
        code.append("  const ok = await confirm({ title: '删除").append(menuName).append("', message: `确认删除「${label}」吗？`, confirmText: '删除', danger: true })\n");
        code.append("  if (!ok) return\n");
        code.append("  try {\n");
        code.append("    await ").append(resource).append("Api.remove(row.id)\n");
        code.append("    pushToast('").append(menuName).append("已删除')\n");
        code.append("    await list.load()\n");
        code.append("  } catch (error) {\n");
        code.append("    toastError(error, '").append(menuName).append("删除失败')\n");
        code.append("  }\n");
        code.append("}\n\n");
        code.append("async function batchRemove(ids: (string | number)[]) {\n");
        code.append("  const ok = await confirm({ title: '批量删除', message: `确认删除选中的 ${ids.length} 条").append(menuName).append("吗？`, confirmText: '删除', danger: true })\n");
        code.append("  if (!ok) return\n");
        code.append("  try {\n");
        code.append("    await ").append(resource).append("Api.removeBatch(ids.map(Number))\n");
        code.append("    pushToast(`已删除 ${ids.length} 条`)\n");
        code.append("    await list.load()\n");
        code.append("  } catch (error) {\n");
        code.append("    toastError(error, '批量删除失败')\n");
        code.append("  }\n");
        code.append("}\n");
        code.append("</script>\n\n");
        code.append("<template>\n");
        code.append("  <ListPage title=\"").append(menuName).append("\" desc=\"").append(comment)
                .append("\" table-title=\"").append(menuName).append("列表\" :list=\"list\" :columns=\"columns\" selectable>\n");
        code.append("    <template #actions>\n");
        code.append("      <PermissionButton perm=\"").append(module).append(':').append(resource)
                .append(":add\" variant=\"primary\" icon=\"plus\" @click=\"openCreate\">新增").append(menuName).append("</PermissionButton>\n");
        code.append("    </template>\n\n");
        if (!queryCols.isEmpty()) {
            code.append("    <template #search>\n").append(searchFields).append("    </template>\n\n");
        }
        code.append("    <template #batch=\"{ ids }\">\n");
        code.append("      <PermissionButton perm=\"").append(module).append(':').append(resource)
                .append(":remove\" variant=\"text\" size=\"sm\" danger @click=\"batchRemove(ids)\">删除</PermissionButton>\n");
        code.append("    </template>\n\n");
        code.append(cellSlots).append('\n');
        code.append("    <FormDialog :open=\"dialog\" :title=\"form.id ? '编辑").append(menuName).append("' : '新增").append(menuName)
                .append("'\" :saving=\"saving\" @close=\"dialog = false\" @submit=\"submit\">\n");
        code.append(formFields);
        code.append("    </FormDialog>\n");
        code.append("  </ListPage>\n");
        code.append("</template>\n");
        return code.toString();
    }

    /* ── 菜单与按钮权限 SQL（幂等，单条语句即可执行） ── */
    private String menuSql(String tableName, String module, String resource, String menuName, Col key) {
        String permission = module + ":" + resource;
        String path = "/" + module + "/" + resource;
        String component = module + "/" + resource + "/index";
        String base = "(SELECT COALESCE(MAX(id), 0) FROM sys_menu)";
        StringBuilder sql = new StringBuilder();
        sql.append("-- ").append(menuName).append(" 菜单与按钮权限（幂等：重复执行不会插入重复数据）\n");
        sql.append("-- 执行后重启后端 / 刷新前端，并在 src/data/pages.ts 注册组件标识：").append(component).append('\n');
        sql.append("INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, icon, path, component, open_type, builtin, permission, sort, visible, status, create_by, update_by)\n");
        sql.append("SELECT * FROM (\n");
        sql.append("    SELECT ").append(base).append(" + 1 AS id, 0 AS parent_id, '").append(menuName).append("' AS menu_name, 'menu' AS menu_type, 'layers' AS icon, '")
                .append(path).append("' AS path, '").append(component).append("' AS component, 'page' AS open_type, 0 AS builtin, '").append(permission)
                .append(":list' AS permission, 10 AS sort, 1 AS visible, 1 AS status, 'system' AS create_by, 'system' AS update_by FROM DUAL\n");
        sql.append("    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = '").append(permission).append(":list')\n");
        sql.append("    UNION ALL\n");
        sql.append("    SELECT ").append(base).append(" + 2 AS id, ").append(base).append(" + 1 AS parent_id, '新增").append(menuName).append("' AS menu_name, 'btn' AS menu_type, '' AS icon, '' AS path, '' AS component, 'page' AS open_type, 0 AS builtin, '")
                .append(permission).append(":add' AS permission, 1 AS sort, 1 AS visible, 1 AS status, 'system' AS create_by, 'system' AS update_by FROM DUAL\n");
        sql.append("    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = '").append(permission).append(":add')\n");
        sql.append("    UNION ALL\n");
        sql.append("    SELECT ").append(base).append(" + 3 AS id, ").append(base).append(" + 1 AS parent_id, '编辑").append(menuName).append("' AS menu_name, 'btn' AS menu_type, '' AS icon, '' AS path, '' AS component, 'page' AS open_type, 0 AS builtin, '")
                .append(permission).append(":edit' AS permission, 2 AS sort, 1 AS visible, 1 AS status, 'system' AS create_by, 'system' AS update_by FROM DUAL\n");
        sql.append("    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = '").append(permission).append(":edit')\n");
        sql.append("    UNION ALL\n");
        sql.append("    SELECT ").append(base).append(" + 4 AS id, ").append(base).append(" + 1 AS parent_id, '删除").append(menuName).append("' AS menu_name, 'btn' AS menu_type, '' AS icon, '' AS path, '' AS component, 'page' AS open_type, 0 AS builtin, '")
                .append(permission).append(":remove' AS permission, 3 AS sort, 1 AS visible, 1 AS status, 'system' AS create_by, 'system' AS update_by FROM DUAL\n");
        sql.append("    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = '").append(permission).append(":remove')\n");
        sql.append(") AS m;\n\n");
        sql.append("-- 授权：超级管理员（role_id = 1）默认拥有全部菜单。\n");
        sql.append("-- 如需授权给其它角色，把下面的 1 换成目标角色 ID 后单独执行：\n");
        sql.append("-- INSERT IGNORE INTO sys_role_menu (role_id, menu_id)\n");
        sql.append("-- SELECT 1, id FROM sys_menu WHERE permission LIKE '").append(permission).append(":%';\n");
        sql.append("-- 表：").append(tableName).append("（主键字段：").append(key.field()).append("）\n");
        return sql.toString();
    }

    /* ── 集成说明 ── */
    private String readme(String module, String className, String resource, String tableName, String comment, String menuName) {
        boolean needMapperScan = !"system".equals(module);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(menuName).append("（").append(className).append("）\n\n");
        md.append("- 数据表：`").append(tableName).append("`（").append(comment).append("）\n");
        md.append("- 后端包名：`com.scaffold.").append(module).append("`\n");
        md.append("- 接口前缀：`/api/").append(module).append('/').append(resource).append("`\n");
        md.append("- 权限标识：`").append(module).append(':').append(resource).append(":list / :add / :edit / :remove`\n\n");
        md.append("## 一、落位路径\n\n");
        md.append("| 压缩包内路径 | 复制到 |\n| --- | --- |\n");
        md.append("| `backend/src/main/java/...` | `scaffold-system/scaffold-system/src/main/java/...` |\n");
        md.append("| `frontend/src/api/...` | `scaffold-vue/src/api/...` |\n");
        md.append("| `frontend/src/views/...` | `scaffold-vue/src/views/...` |\n");
        md.append("| `sql/").append(tableName).append("_menu.sql` | 直接在 `scaffold_system` 库执行 |\n\n");
        md.append("## 二、四步集成\n\n");
        md.append("1. **后端**：把 `backend/src/main/java/com/scaffold/").append(module).append("` 整个目录复制到 `scaffold-system/scaffold-system/src/main/java/com/scaffold/`。\n");
        if (needMapperScan) {
            md.append("   > 当前模块不是 `system`，需要把 Mapper 包加入扫描：打开 `scaffold-system/scaffold-admin/src/main/java/com/scaffold/admin/ScaffoldAdminApplication.java`，在 `@MapperScan` 里补一行 `\"com.scaffold.").append(module).append(".mapper\"`。\n");
        }
        md.append("2. **前端**：把 `frontend/src` 下的 `api/").append(module).append("`、`views/").append(module).append("` 复制到 `scaffold-vue/src/` 对应位置。\n");
        md.append("3. **注册页面**：在 `scaffold-vue/src/data/pages.ts` 的 `REGISTERED_PAGES` 里加一行\n\n");
        md.append("   ```ts\n");
        md.append("   { key: '").append(module).append('/').append(resource).append("/index', label: '").append(menuName)
                .append("', load: () => import('@/views/").append(module).append('/').append(resource).append("/index.vue') },\n");
        md.append("   ```\n\n");
        md.append("   > 不注册的话，菜单会落到「页面未绑定」占位页。\n");
        md.append("4. **执行 SQL**：运行 `sql/").append(tableName).append("_menu.sql`，刷新页面后左侧出现「").append(menuName).append("」。\n\n");
        md.append("## 三、已包含的能力\n\n");
        md.append("- `GET /api/").append(module).append('/').append(resource).append("` 分页列表（支持查询条件）\n");
        md.append("- `GET /api/").append(module).append('/').append(resource).append("/{id}` 详情\n");
        md.append("- `POST /api/").append(module).append('/').append(resource).append("` 新增\n");
        md.append("- `PUT /api/").append(module).append('/').append(resource).append("/{id}` 修改\n");
        md.append("- `DELETE /api/").append(module).append('/').append(resource).append("/{id}` 删除、`POST /api/").append(module).append('/').append(resource).append("/batch-remove` 批量删除\n");
        md.append("- 必填校验（Service 层）、字符串去空格、操作日志、按钮级权限\n\n");
        md.append("## 四、要注意的地方\n\n");
        md.append("- 主键、字段是否进列表 / 表单 / 查询条件，取决于导入时写入 `gen_table_column` 的配置；密码、密钥类字段已默认排除出列表与查询。\n");
        md.append("- 生成结果只是起点：涉及业务规则（唯一性、状态流转、关联校验）仍需按实际补在 Service 里。\n");
        return md.toString();
    }

    /* ── 列模型 ── */
    private List<Col> buildCols(List<Map<String, Object>> rows) {
        boolean hasId = rows.stream().anyMatch(row -> "id".equalsIgnoreCase(asString(row.get("col"))));
        String keyColumn = hasId ? "id" : (rows.isEmpty() ? "id" : asString(rows.get(0).get("col")));
        List<Col> cols = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String column = asString(row.get("col"));
            String field = asString(row.get("javaField"));
            String javaType = asString(row.get("javaType"));
            String comment = asString(row.get("comment"));
            boolean primary = column.equalsIgnoreCase(keyColumn);
            boolean sensitive = SENSITIVE_FIELDS.contains(column.toLowerCase(Locale.ROOT));
            boolean audit = AUDIT_FIELDS.contains(column.toLowerCase(Locale.ROOT));
            boolean hidden = HIDDEN_FIELDS.contains(column.toLowerCase(Locale.ROOT));
            boolean insert = enabled(row.get("insert")) && !primary && !audit && !hidden;
            boolean edit = enabled(row.get("edit")) && !primary && !audit && !hidden;
            boolean list = enabled(row.get("list")) && !primary && !hidden && !sensitive;
            boolean query = enabled(row.get("query")) && !primary && !hidden && !sensitive;
            cols.add(new Col(
                    column,
                    field,
                    javaType,
                    comment == null || comment.isBlank() ? field : comment,
                    insert,
                    edit,
                    list,
                    query,
                    asString(row.get("queryType")),
                    asString(row.get("formType")),
                    enabled(row.get("required")),
                    primary
            ));
        }
        return cols;
    }

    private record Col(String column, String field, String javaType, String comment,
                       boolean insert, boolean edit, boolean list, boolean query,
                       String queryType, String formType, boolean required, boolean primary) {
    }

    private static boolean isStatus(Col col) {
        return "status".equalsIgnoreCase(col.column()) && "Integer".equals(col.javaType());
    }

    private static boolean isNumeric(String javaType) {
        return "Integer".equals(javaType) || "Long".equals(javaType) || "BigDecimal".equals(javaType) || "Double".equals(javaType);
    }

    private static String tsType(String javaType) {
        return switch (javaType) {
            case "Long", "Integer", "BigDecimal", "Double" -> "number";
            case "Boolean" -> "boolean";
            default -> "string";
        };
    }

    private String tableCommentOf(Map<String, Object> table) {
        String comment = asString(table.get("tableComment"));
        if (comment == null || comment.isBlank()) {
            return asString(table.get("className"));
        }
        return comment.trim();
    }

    private String menuNameOf(Map<String, Object> table) {
        String comment = tableCommentOf(table);
        String base = comment;
        if (base.endsWith("表")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("信息") && base.length() > 2) {
            base = base.substring(0, base.length() - 2);
        }
        if (base.isBlank()) {
            base = asString(table.get("className"));
        }
        return base + "管理";
    }

    private String join(List<String> values) {
        return String.join(", ", values);
    }

    private boolean enabled(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return Boolean.TRUE.equals(value);
    }

    private String nullAsEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Map<String, Object>> booleanRows(List<Map<String, Object>> rows, String... keys) {
        for (Map<String, Object> row : rows) {
            for (String key : keys) {
                Object value = row.get(key);
                if (value instanceof Number number) {
                    row.put(key, number.intValue() == 1);
                }
            }
        }
        return rows;
    }

    private Page pageOf(PageQuery query) {
        int pageNum = query == null ? PageQuery.DEFAULT_PAGE_NUM : query.normalizedPageNum();
        int pageSize = query == null ? PageQuery.DEFAULT_PAGE_SIZE : query.normalizedPageSize();
        return new Page(pageNum, pageSize);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String decapitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String moduleName(String tableName) {
        int split = tableName.indexOf('_');
        String module = split > 0 ? tableName.substring(0, split) : "system";
        return "sys".equals(module) ? "system" : module;
    }

    private String businessName(String tableName) {
        int split = tableName.indexOf('_');
        String raw = split > 0 ? tableName.substring(split + 1) : tableName;
        return lowerCamel(raw);
    }

    private String upperCamel(String value) {
        String lower = lowerCamel(value);
        return lower == null || lower.isBlank() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String lowerCamel(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.isEmpty()) {
                builder.append(part);
            } else {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String javaType(String dataType) {
        return switch (dataType) {
            case "bigint" -> "Long";
            case "int", "integer", "smallint", "tinyint" -> "Integer";
            case "decimal", "numeric" -> "BigDecimal";
            case "datetime", "timestamp", "date", "time" -> "LocalDateTime";
            default -> "String";
        };
    }

    private String formType(String dataType) {
        return switch (dataType) {
            case "text", "longtext", "mediumtext" -> "文本域";
            case "int", "integer", "smallint", "tinyint", "bigint", "decimal", "numeric" -> "数字框";
            case "datetime", "timestamp", "date", "time" -> "日期框";
            default -> "文本框";
        };
    }

    private boolean queryField(String columnName) {
        String lower = columnName.toLowerCase(Locale.ROOT);
        return lower.contains("name") || lower.contains("code") || lower.contains("status") || lower.contains("type");
    }

    private Long numberAsLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private Map<String, Object> ensureTable(Long tableId) {
        Map<String, Object> table = mapper.selectTableById(tableId);
        if (table == null) {
            throw new BusinessException("代码生成表不存在");
        }
        return table;
    }

    private Map<String, Object> tableRow(Map<String, Object> dbTable) {
        String tableName = String.valueOf(dbTable.get("tableName"));
        String businessName = businessName(tableName);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableName", tableName);
        row.put("tableComment", dbTable.get("tableComment"));
        row.put("className", upperCamel(tableName));
        row.put("module", moduleName(tableName));
        row.put("businessName", businessName);
        return row;
    }

    private Map<String, Object> columnRow(Long tableId, Map<String, Object> column) {
        String columnName = String.valueOf(column.get("columnName"));
        String dataType = String.valueOf(column.get("dataType")).toLowerCase(Locale.ROOT);
        boolean primaryLike = "id".equals(columnName);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableId", tableId);
        row.put("columnName", columnName);
        row.put("javaField", lowerCamel(columnName));
        row.put("javaType", javaType(dataType));
        row.put("jdbcType", dataType);
        row.put("columnComment", column.get("columnComment"));
        row.put("insert", primaryLike ? 0 : 1);
        row.put("edit", primaryLike ? 0 : 1);
        row.put("list", primaryLike ? 0 : 1);
        row.put("query", queryField(columnName) ? 1 : 0);
        row.put("queryType", queryField(columnName) ? "LIKE" : "=");
        row.put("formType", formType(dataType));
        row.put("required", "NO".equals(column.get("nullable")) && !primaryLike ? 1 : 0);
        row.put("sort", column.get("sort"));
        return row;
    }

    private record Page(int pageNum, int pageSize) {
        int offset() {
            return (pageNum - 1) * pageSize;
        }
    }
}
