package com.scaffold.system.service;

import com.scaffold.common.domain.PageQuery;
import com.scaffold.common.domain.PageResult;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.datascope.DataScope;
import com.scaffold.framework.datascope.DataScopeContext;
import com.scaffold.framework.datascope.DataScopeCriteria;
import com.scaffold.system.domain.dto.ConfigWriteRequest;
import com.scaffold.system.domain.dto.DeptWriteRequest;
import com.scaffold.system.domain.dto.DictDataWriteRequest;
import com.scaffold.system.domain.dto.DictTypeWriteRequest;
import com.scaffold.system.domain.dto.MenuWriteRequest;
import com.scaffold.system.domain.dto.PostWriteRequest;
import com.scaffold.system.domain.dto.RoleWriteRequest;
import com.scaffold.system.domain.dto.UserPasswordResetRequest;
import com.scaffold.system.domain.dto.UserStatusRequest;
import com.scaffold.system.domain.dto.UserWriteRequest;
import com.scaffold.system.domain.vo.IdResponse;
import com.scaffold.system.mapper.SystemManagementMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SystemManagementService {

    /** 超级管理员角色编码：绕过角色-菜单分配，直接获得全部菜单 */
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final SystemManagementMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final SystemPermissionResolver permissionResolver;
    private final com.scaffold.framework.security.TokenStore tokenStore;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public SystemManagementService(SystemManagementMapper mapper, PasswordEncoder passwordEncoder,
                                   SystemPermissionResolver permissionResolver,
                                   com.scaffold.framework.security.TokenStore tokenStore,
                                   org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.permissionResolver = permissionResolver;
        this.tokenStore = tokenStore;
        this.eventPublisher = eventPublisher;
    }

    /** 用户页所需的引用选项，避免为只读用户申请角色、岗位和参数管理权限。 */
    public Map<String, Object> userOptions() {
        String username = currentUsername();
        boolean canEdit = permissionResolver.hasPermission(username, "system:user:add")
                || permissionResolver.hasPermission(username, "system:user:edit");
        boolean canSetPassword = permissionResolver.hasPermission(username, "system:user:add")
                || permissionResolver.hasPermission(username, "system:user:resetPwd");
        List<Map<String, Object>> depts = mapper.selectDepts(null, null).stream()
                .map(row -> {
                    Map<String, Object> option = new LinkedHashMap<>();
                    for (String key : List.of("id", "parentId", "name", "status")) option.put(key, row.get(key));
                    return option;
                }).toList();
        List<Map<String, Object>> roles = canEdit ? mapper.selectRoles(null, 1, 0, 5000).stream()
                .map(row -> Map.of("id", row.get("id"), "name", row.get("name"))).toList() : List.of();
        List<Map<String, Object>> posts = canEdit ? mapper.selectPosts(null, 1, 0, 5000).stream()
                .map(row -> Map.of("id", row.get("id"), "name", row.get("postName"))).toList() : List.of();
        String initialPassword = canSetPassword ? mapper.selectConfigs("sys.user.initPassword", 0, 10).stream()
                .filter(row -> "sys.user.initPassword".equals(row.get("key")))
                .map(row -> String.valueOf(row.get("value"))).findFirst().orElse("") : "";
        return Map.of("depts", treeOf(depts), "roles", roles, "posts", posts, "initPassword", initialPassword);
    }

    public List<Map<String, Object>> dictOptions(String dictType) {
        return mapper.selectDictOptions(dictType);
    }

    @DataScope
    public PageResult<Map<String, Object>> users(PageQuery query, String keyword, Integer status, Long deptId, Long postId) {
        Page page = pageOf(query);
        DataScopeCriteria scope = DataScopeContext.current();
        long total = mapper.countUsers(keyword, status, deptId, postId, scope);
        List<Map<String, Object>> rows = mapper.selectUsers(keyword, status, deptId, postId, scope, page.offset(), page.pageSize());
        // 在线标识：令牌量级小（几百以内），一次枚举分组后逐行比对
        Set<String> onlineUsernames = tokenStore.tokensByUsername().keySet();
        for (Map<String, Object> row : rows) {
            row.put("online", onlineUsernames.contains(String.valueOf(row.get("username"))));
        }
        return PageResult.of(rows, total, page.pageNum(), page.pageSize());
    }

    /**
     * 强制下线：撤销该用户当前全部令牌。
     *
     * @return 撤销的会话数；0 表示该用户本就不在线
     */
    @Transactional
    public int kickUser(Long id) {
        Map<String, Object> row = mapper.selectUserById(id);
        if (row == null || Integer.parseInt(String.valueOf(row.get("deleted"))) == 1) {
            throw new BusinessException("用户不存在");
        }
        String username = String.valueOf(row.get("username"));
        return tokenStore.revokeAll(username);
    }

    @DataScope
    public String exportUsers(String keyword, Integer status, Long deptId, Long postId) {
        List<Map<String, Object>> rows = mapper.selectUsers(keyword, status, deptId, postId, DataScopeContext.current(), 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(
                        value(row, "username"),
                        value(row, "nickname"),
                        value(row, "phone"),
                        value(row, "email"),
                        value(row, "deptName"),
                        value(row, "postName"),
                        value(row, "roleName"),
                        userStatusText(row.get("status")),
                        value(row, "createTime"),
                        value(row, "lastLogin")
                ))
                .toList();
        return csv(
                List.of("登录账号", "用户昵称", "手机号", "邮箱", "部门", "岗位", "角色", "状态", "创建时间", "最近登录"),
                data
        );
    }

    public PageResult<Map<String, Object>> roles(PageQuery query, String keyword, Integer status) {
        Page page = pageOf(query);
        long total = mapper.countRoles(keyword, status);
        return PageResult.of(roleRows(mapper.selectRoles(keyword, status, page.offset(), page.pageSize())), total, page.pageNum(), page.pageSize());
    }

    /**
     * 权限关系图：角色、菜单与「用户-角色」关联一次返回，供溯源视图、菜单星图与问答规则在前端组合。
     * 用户节点只对有用户查询权限的账号返回，并遵守数据权限。
     */
    @DataScope
    public Map<String, Object> permissionGraph() {
        List<Map<String, Object>> roles = roleRows(mapper.selectRoles(null, null, 0, 5000)).stream()
                .map(row -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    for (String key : List.of("id", "name", "code", "dataScope", "status", "builtin", "userCount", "menuIds")) node.put(key, row.get(key));
                    return node;
                }).toList();
        List<Map<String, Object>> menus = mapper.selectMenus(null, null).stream()
                .map(row -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    for (String key : List.of("id", "parentId", "name", "type", "icon", "path", "permission", "sort", "visible", "status")) node.put(key, row.get(key));
                    return node;
                }).toList();
        boolean canSeeUsers = permissionResolver.hasPermission(currentUsername(), "system:user:list");
        List<Map<String, Object>> users = canSeeUsers ? mapper.selectGraphUsers(DataScopeContext.current()) : List.of();
        Set<Object> userIds = new HashSet<>();
        users.forEach(row -> userIds.add(numberAsLong(row.get("id"))));
        List<List<Long>> userRoles = canSeeUsers ? mapper.selectUserRoleLinks().stream()
                .filter(row -> userIds.contains(numberAsLong(row.get("userId"))))
                .map(row -> List.of(numberAsLong(row.get("userId")), numberAsLong(row.get("roleId"))))
                .toList() : List.of();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("users", users);
        graph.put("roles", roles);
        graph.put("menus", menus);
        graph.put("userRoles", userRoles);
        graph.put("usersVisible", canSeeUsers);
        return graph;
    }

    public Map<String, Object> roleMenus(Long roleId) {
        return Map.of("menuIds", mapper.selectRoleMenuIds(roleId));
    }

    public String exportRoles(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectRoles(keyword, status, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "name"), value(row, "code"), value(row, "dataScope"),
                        userStatusText(row.get("status")), value(row, "createTime"), value(row, "remark")))
                .toList();
        return csv(List.of("角色名称", "权限字符", "数据权限", "状态", "创建时间", "备注"), data);
    }

    public List<Map<String, Object>> menus(String keyword, Integer status) {
        return treeOf(mapper.selectMenus(keyword, status));
    }

    public String exportMenus(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectMenus(keyword, status);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "name"), value(row, "type"), value(row, "icon"),
                        value(row, "path"), value(row, "component"), value(row, "permission"),
                        value(row, "sort"), userStatusText(row.get("status"))))
                .toList();
        return csv(List.of("菜单名称", "类型", "图标", "路由地址", "组件路径", "权限标识", "排序", "状态"), data);
    }

    public List<Map<String, Object>> depts(String keyword, Integer status) {
        return treeOf(mapper.selectDepts(keyword, status));
    }

    public String exportDepts(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectDepts(keyword, status);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "name"), value(row, "leader"), value(row, "phone"),
                        value(row, "email"), value(row, "sort"), userStatusText(row.get("status"))))
                .toList();
        return csv(List.of("部门名称", "负责人", "联系电话", "邮箱", "排序", "状态"), data);
    }

    public PageResult<Map<String, Object>> posts(PageQuery query, String keyword, Integer status) {
        Page page = pageOf(query);
        long total = mapper.countPosts(keyword, status);
        return PageResult.of(mapper.selectPosts(keyword, status, page.offset(), page.pageSize()), total, page.pageNum(), page.pageSize());
    }

    public String exportPosts(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectPosts(keyword, status, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "postCode"), value(row, "postName"), value(row, "sort"),
                        userStatusText(row.get("status")), value(row, "createTime"), value(row, "remark")))
                .toList();
        return csv(List.of("岗位编码", "岗位名称", "排序", "状态", "创建时间", "备注"), data);
    }

    public PageResult<Map<String, Object>> dictTypes(PageQuery query, String keyword, Integer status) {
        Page page = pageOf(query);
        long total = mapper.countDictTypes(keyword, status);
        return PageResult.of(mapper.selectDictTypes(keyword, status, page.offset(), page.pageSize()), total, page.pageNum(), page.pageSize());
    }

    public String exportDictTypes(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectDictTypes(keyword, status, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "dictName"), value(row, "dictType"),
                        userStatusText(row.get("status")), value(row, "createTime"), value(row, "remark")))
                .toList();
        return csv(List.of("字典名称", "字典类型", "状态", "创建时间", "备注"), data);
    }

    public List<Map<String, Object>> dictData(String dictType) {
        return booleanRows(mapper.selectDictData(dictType), "def");
    }

    public PageResult<Map<String, Object>> configs(PageQuery query, String keyword) {
        Page page = pageOf(query);
        long total = mapper.countConfigs(keyword);
        return PageResult.of(booleanRows(mapper.selectConfigs(keyword, page.offset(), page.pageSize()), "builtin"), total, page.pageNum(), page.pageSize());
    }

    public String exportConfigs(String keyword) {
        List<Map<String, Object>> rows = mapper.selectConfigs(keyword, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(value(row, "name"), value(row, "key"), value(row, "value"),
                        value(row, "type"), value(row, "createTime"), value(row, "remark")))
                .toList();
        return csv(List.of("参数名称", "参数键名", "参数键值", "系统内置", "创建时间", "备注"), data);
    }

    public PageResult<Map<String, Object>> operLogs(PageQuery query, String keyword, Integer result) {
        Page page = pageOf(query);
        long total = mapper.countOperLogs(keyword, result);
        return PageResult.of(mapper.selectOperLogs(keyword, result, page.offset(), page.pageSize()), total, page.pageNum(), page.pageSize());
    }

    public PageResult<Map<String, Object>> loginLogs(PageQuery query, String keyword, Integer status) {
        Page page = pageOf(query);
        long total = mapper.countLoginLogs(keyword, status);
        return PageResult.of(mapper.selectLoginLogs(keyword, status, page.offset(), page.pageSize()), total, page.pageNum(), page.pageSize());
    }

    public String exportOperLogs(String keyword, Integer result) {
        List<Map<String, Object>> rows = mapper.selectOperLogs(keyword, result, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(
                        value(row, "module"),
                        value(row, "action"),
                        value(row, "method"),
                        value(row, "url"),
                        value(row, "params"),
                        value(row, "operName"),
                        value(row, "dept"),
                        value(row, "ip"),
                        value(row, "location"),
                        value(row, "browser"),
                        value(row, "os"),
                        value(row, "cost"),
                        resultText(row.get("result")),
                        value(row, "operTime"),
                        value(row, "errorMsg")
                ))
                .toList();
        return csv(
                List.of("模块", "类型", "请求方式", "请求地址", "请求参数", "操作人", "部门", "IP", "地点", "浏览器", "系统", "耗时", "结果", "操作时间", "异常信息"),
                data
        );
    }

    public String exportLoginLogs(String keyword, Integer status) {
        List<Map<String, Object>> rows = mapper.selectLoginLogs(keyword, status, 0, 5000);
        List<List<Object>> data = rows.stream()
                .map(row -> csvRow(
                        value(row, "username"),
                        value(row, "ip"),
                        value(row, "location"),
                        value(row, "browser"),
                        value(row, "os"),
                        resultText(row.get("status")),
                        value(row, "msg"),
                        value(row, "loginTime")
                ))
                .toList();
        return csv(
                List.of("登录账号", "IP", "地点", "浏览器", "系统", "状态", "描述", "登录时间"),
                data
        );
    }

    @Transactional
    public void deleteOperLog(Long id) {
        if (mapper.deleteOperLog(id) == 0) {
            throw new BusinessException("操作日志不存在");
        }
    }

    @Transactional
    public void clearOperLogs() {
        mapper.clearOperLogs();
    }

    @Transactional
    public void deleteLoginLog(Long id) {
        if (mapper.deleteLoginLog(id) == 0) {
            throw new BusinessException("登录日志不存在");
        }
    }

    @Transactional
    public void clearLoginLogs() {
        mapper.clearLoginLogs();
    }

    @Transactional
    public IdResponse createPost(PostWriteRequest request) {
        String postCode = required(request.getPostCode(), "岗位编码不能为空");
        String postName = required(request.getPostName(), "岗位名称不能为空");
        if (mapper.countPostCode(postCode, null) > 0) {
            throw new BusinessException("岗位编码已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("postCode", postCode);
        row.put("postName", postName);
        row.put("sort", defaultInt(request.getSort(), 0));
        row.put("status", defaultInt(request.getStatus(), 1));
        row.put("remark", request.getRemark());
        mapper.insertPost(row, currentUsername());
        return new IdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updatePost(Long id, PostWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("postName", trimToNull(request.getPostName()));
        row.put("sort", request.getSort());
        row.put("status", request.getStatus());
        row.put("remark", request.getRemark());
        if (mapper.updatePost(id, row, currentUsername()) == 0) {
            throw new BusinessException("岗位不存在或已删除");
        }
    }

    @Transactional
    public void deletePost(Long id) {
        if (mapper.softDeletePost(id, currentUsername()) == 0) {
            throw new BusinessException("岗位不存在或已删除");
        }
    }

    @Transactional
    public IdResponse createUser(UserWriteRequest request) {
        String username = required(request.getUsername(), "登录账号不能为空");
        String nickname = required(request.getNickname(), "用户昵称不能为空");
        if (mapper.countUsername(username, null) > 0) {
            throw new BusinessException("登录账号已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("username", username);
        row.put("password", passwordEncoder.encode(defaultString(request.getPassword(), "admin123")));
        row.put("nickname", nickname);
        row.put("phone", request.getPhone());
        row.put("email", request.getEmail());
        row.put("deptId", request.getDeptId());
        row.put("status", defaultInt(request.getStatus(), 1));
        mapper.insertUser(row, currentUsername());
        Long userId = numberAsLong(row.get("id"));
        replaceUserRoles(userId, request.getRoleIds());
        replaceUserPosts(userId, request.getPostIds());
        return new IdResponse(userId);
    }

    @Transactional
    public void updateUser(Long id, UserWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nickname", trimToNull(request.getNickname()));
        row.put("phone", request.getPhone());
        row.put("email", request.getEmail());
        row.put("deptId", request.getDeptId());
        row.put("status", request.getStatus());
        if (mapper.updateUser(id, row, currentUsername()) == 0) {
            throw new BusinessException("用户不存在或已删除");
        }
        if (request.getRoleIds() != null) {
            guardSelfSuperAdmin(id, request.getRoleIds());
            replaceUserRoles(id, request.getRoleIds());
        }
        if (request.getPostIds() != null) {
            replaceUserPosts(id, request.getPostIds());
        }
    }

    /**
     * 不允许把自己身上的超级管理员角色摘掉。
     *
     * <p>超级管理员绕过角色-菜单分配直接获得全部菜单；一旦该角色被移除而
     * 新角色又没配菜单，账号就会变成「登录成功但没有任何菜单、进不去系统」，
     * 且现场很难看出是自己刚才那次改操作导致的。
     */
    private void guardSelfSuperAdmin(Long userId, List<Long> newRoleIds) {
        if (!String.valueOf(userId).equals(currentUserId())) {
            return;
        }
        Long superAdminRoleId = mapper.selectRoleIdByCode(SUPER_ADMIN_ROLE_CODE);
        if (superAdminRoleId == null) {
            return;
        }
        List<Long> currentRoleIds = mapper.selectUserRoleIds(userId);
        boolean hadSuperAdmin = currentRoleIds.contains(superAdminRoleId);
        boolean stillHas = newRoleIds != null && newRoleIds.contains(superAdminRoleId);
        if (hadSuperAdmin && !stillHas) {
            throw new BusinessException("不能移除自己的超级管理员角色，否则将失去全部菜单权限且无法再进入系统");
        }
    }

    /** 当前登录用户的 id（无法解析时返回空串） */
    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return "";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        String name = String.valueOf(principal);
        Map<String, Object> row = mapper.selectUserProfileByUsername(name);
        Object id = row == null ? null : row.get("id");
        return id == null ? "" : String.valueOf(id);
    }

    @Transactional
    public void updateUserStatus(Long id, UserStatusRequest request) {
        Integer status = request.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("用户状态只能为正常或停用");
        }
        if (id == 1L && status == 0) {
            throw new BusinessException("内置管理员不允许停用");
        }
        if (mapper.updateUserStatus(id, status, currentUsername()) == 0) {
            throw new BusinessException("用户不存在或已删除");
        }
    }

    @Transactional
    public void resetUserPassword(Long id, UserPasswordResetRequest request) {
        String password = defaultString(request.getPassword(), "Yy@123456");
        if (password.length() < 6 || password.length() > 64) {
            throw new BusinessException("密码长度必须在6到64位之间");
        }
        if (mapper.resetUserPassword(id, passwordEncoder.encode(password), currentUsername()) == 0) {
            throw new BusinessException("用户不存在或已删除");
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        if (id == 1L) {
            throw new BusinessException("内置管理员不允许删除");
        }
        if (mapper.softDeleteUser(id, currentUsername()) == 0) {
            throw new BusinessException("用户不存在或已删除");
        }
    }

    @Transactional
    public IdResponse createRole(RoleWriteRequest request) {
        String name = required(request.getName(), "角色名称不能为空");
        String code = required(request.getCode(), "角色编码不能为空");
        if (mapper.countRoleCode(code, null) > 0) {
            throw new BusinessException("角色编码已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("code", code);
        row.put("dataScope", defaultString(request.getDataScope(), "SELF"));
        row.put("sort", defaultInt(request.getSort(), 0));
        row.put("status", defaultInt(request.getStatus(), 1));
        row.put("remark", request.getRemark());
        mapper.insertRole(row, currentUsername());
        Long roleId = numberAsLong(row.get("id"));
        replaceRoleMenus(roleId, request.getMenuIds());
        return new IdResponse(roleId);
    }

    @Transactional
    public void updateRole(Long id, RoleWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", trimToNull(request.getName()));
        row.put("dataScope", trimToNull(request.getDataScope()));
        row.put("sort", request.getSort());
        row.put("status", request.getStatus());
        row.put("remark", request.getRemark());
        if (mapper.updateRole(id, row, currentUsername()) == 0) {
            throw new BusinessException("角色不存在、已删除或为内置角色");
        }
        if (request.getMenuIds() != null) {
            replaceRoleMenus(id, request.getMenuIds());
        }
    }

    @Transactional
    public void deleteRole(Long id) {
        if (mapper.countRoleUsers(id) > 0) {
            throw new BusinessException("角色已分配用户，不能删除");
        }
        mapper.deleteRoleMenus(id);
        if (mapper.softDeleteRole(id, currentUsername()) == 0) {
            throw new BusinessException("角色不存在、已删除或为内置角色");
        }
    }

    @Transactional
    public IdResponse createDept(DeptWriteRequest request) {
        String name = required(request.getName(), "部门名称不能为空");
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("parentId", parentId);
        row.put("ancestors", ancestorsFor(parentId));
        row.put("name", name);
        row.put("leader", request.getLeader());
        row.put("phone", request.getPhone());
        row.put("email", request.getEmail());
        row.put("sort", defaultInt(request.getSort(), 0));
        row.put("status", defaultInt(request.getStatus(), 1));
        mapper.insertDept(row, currentUsername());
        return new IdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateDept(Long id, DeptWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("parentId", request.getParentId());
        row.put("ancestors", request.getParentId() == null ? null : ancestorsFor(request.getParentId()));
        row.put("name", trimToNull(request.getName()));
        row.put("leader", request.getLeader());
        row.put("phone", request.getPhone());
        row.put("email", request.getEmail());
        row.put("sort", request.getSort());
        row.put("status", request.getStatus());
        if (mapper.updateDept(id, row, currentUsername()) == 0) {
            throw new BusinessException("部门不存在或已删除");
        }
    }

    @Transactional
    public void deleteDept(Long id) {
        if (mapper.countDeptChildren(id) > 0) {
            throw new BusinessException("存在下级部门，不能删除");
        }
        if (mapper.countDeptUsers(id) > 0) {
            throw new BusinessException("部门已关联用户，不能删除");
        }
        if (mapper.softDeleteDept(id, currentUsername()) == 0) {
            throw new BusinessException("部门不存在或已删除");
        }
    }

    @Transactional
    public IdResponse createMenu(MenuWriteRequest request) {
        String name = required(request.getName(), "菜单名称不能为空");
        Long menuId = mapper.nextMenuId();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", menuId);
        row.put("parentId", request.getParentId() == null ? 0L : request.getParentId());
        row.put("name", name);
        row.put("type", defaultString(request.getType(), "menu"));
        row.put("icon", request.getIcon());
        row.put("path", request.getPath());
        row.put("component", request.getComponent());
        row.put("openType", resolveOpenType(request.getOpenType(), request.getComponent()));
        row.put("permission", request.getPermission());
        row.put("sort", defaultInt(request.getSort(), 0));
        row.put("visible", defaultInt(request.getVisible(), 1));
        row.put("status", defaultInt(request.getStatus(), 1));
        mapper.insertMenu(row, currentUsername());
        return new IdResponse(menuId);
    }

    @Transactional
    public void updateMenu(Long id, MenuWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("parentId", request.getParentId());
        row.put("name", trimToNull(request.getName()));
        row.put("type", trimToNull(request.getType()));
        row.put("icon", request.getIcon());
        row.put("path", request.getPath());
        row.put("component", request.getComponent());
        row.put("openType", normalizeOpenType(request.getOpenType(), request.getComponent()));
        row.put("permission", request.getPermission());
        row.put("sort", request.getSort());
        row.put("visible", request.getVisible());
        row.put("status", request.getStatus());
        if (mapper.updateMenu(id, row, currentUsername()) == 0) {
            throw new BusinessException("菜单不存在");
        }
    }

    @Transactional
    public void deleteMenu(Long id) {
        if (mapper.countMenuChildren(id) > 0) {
            throw new BusinessException("存在子菜单，不能删除");
        }
        if (mapper.countMenuRoles(id) > 0) {
            throw new BusinessException("菜单已分配角色，不能删除");
        }
        if (mapper.deleteMenu(id) == 0) {
            throw new BusinessException("菜单不存在");
        }
    }

    @Transactional
    public IdResponse createDictType(DictTypeWriteRequest request) {
        String dictName = required(request.getDictName(), "字典名称不能为空");
        String dictType = required(request.getDictType(), "字典类型不能为空");
        if (mapper.countDictType(dictType, null) > 0) {
            throw new BusinessException("字典类型已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dictName", dictName);
        row.put("dictType", dictType);
        row.put("status", defaultInt(request.getStatus(), 1));
        row.put("remark", request.getRemark());
        mapper.insertDictType(row, currentUsername());
        return new IdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateDictType(Long id, DictTypeWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dictName", trimToNull(request.getDictName()));
        row.put("status", request.getStatus());
        row.put("remark", request.getRemark());
        if (mapper.updateDictType(id, row, currentUsername()) == 0) {
            throw new BusinessException("字典类型不存在");
        }
    }

    @Transactional
    public void deleteDictType(Long id) {
        String dictType = mapper.selectDictTypeCode(id);
        if (dictType == null) {
            throw new BusinessException("字典类型不存在");
        }
        if (mapper.countDictDataByType(dictType) > 0) {
            throw new BusinessException("字典类型存在字典数据，不能删除");
        }
        mapper.deleteDictType(id);
    }

    @Transactional
    public IdResponse createDictData(DictDataWriteRequest request) {
        String dictType = required(request.getDictType(), "字典类型不能为空");
        String label = required(request.getLabel(), "字典标签不能为空");
        String value = required(request.getValue(), "字典值不能为空");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dictType", dictType);
        row.put("label", label);
        row.put("value", value);
        row.put("sort", defaultInt(request.getSort(), 0));
        row.put("status", defaultInt(request.getStatus(), 1));
        row.put("tone", request.getTone());
        row.put("def", boolAsInt(request.getDef()));
        row.put("remark", request.getRemark());
        mapper.insertDictData(row, currentUsername());
        return new IdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateDictData(Long id, DictDataWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dictType", trimToNull(request.getDictType()));
        row.put("label", trimToNull(request.getLabel()));
        row.put("value", trimToNull(request.getValue()));
        row.put("sort", request.getSort());
        row.put("status", request.getStatus());
        row.put("tone", request.getTone());
        row.put("def", request.getDef() == null ? null : boolAsInt(request.getDef()));
        row.put("remark", request.getRemark());
        if (mapper.updateDictData(id, row, currentUsername()) == 0) {
            throw new BusinessException("字典数据不存在");
        }
    }

    @Transactional
    public void deleteDictData(Long id) {
        if (mapper.deleteDictData(id) == 0) {
            throw new BusinessException("字典数据不存在");
        }
    }

    @Transactional
    public IdResponse createConfig(ConfigWriteRequest request) {
        String name = required(request.getName(), "参数名称不能为空");
        String key = required(request.getKey(), "参数键名不能为空");
        String value = required(request.getValue(), "参数键值不能为空");
        if (mapper.countConfigKey(key, null) > 0) {
            throw new BusinessException("参数键名已存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("key", key);
        row.put("value", value);
        row.put("type", defaultString(request.getType(), "N"));
        row.put("builtin", boolAsInt(request.getBuiltin()));
        row.put("remark", request.getRemark());
        mapper.insertConfig(row, currentUsername());
        eventPublisher.publishEvent(new ConfigChangedEvent(key));
        return new IdResponse(numberAsLong(row.get("id")));
    }

    @Transactional
    public void updateConfig(Long id, ConfigWriteRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", trimToNull(request.getName()));
        row.put("value", trimToNull(request.getValue()));
        row.put("type", trimToNull(request.getType()));
        row.put("builtin", request.getBuiltin() == null ? null : boolAsInt(request.getBuiltin()));
        row.put("remark", request.getRemark());
        if (mapper.updateConfig(id, row, currentUsername()) == 0) {
            throw new BusinessException("参数配置不存在或为内置参数");
        }
        // update/delete 只拿得到 id，发布全量失效（缓存重建只是一条主键查询，代价可忽略）
        eventPublisher.publishEvent(new ConfigChangedEvent(null));
    }

    @Transactional
    public void deleteConfig(Long id) {
        if (mapper.deleteConfig(id) == 0) {
            throw new BusinessException("参数配置不存在或为内置参数");
        }
        eventPublisher.publishEvent(new ConfigChangedEvent(null));
    }

    private Page pageOf(PageQuery query) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        return new Page(pageNum, pageSize, (pageNum - 1) * pageSize);
    }

    private List<Map<String, Object>> treeOf(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put("children", new ArrayList<Map<String, Object>>());
            byId.put(numberAsLong(node.get("id")), node);
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> node : byId.values()) {
            Map<String, Object> parent = byId.get(numberAsLong(node.get("parentId")));
            if (parent == null) {
                roots.add(node);
            } else {
                childrenOf(parent).add(node);
            }
        }
        return roots;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> childrenOf(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    private List<Map<String, Object>> booleanRows(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .map(row -> booleanRow(row, key))
                .toList();
    }

    private List<Map<String, Object>> roleRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> copy = booleanRow(row, "builtin");
                    String ids = String.valueOf(copy.getOrDefault("menuIdsText", ""));
                    List<Long> menuIds = ids.isBlank() || "null".equals(ids)
                            ? List.of()
                            : Arrays.stream(ids.split(","))
                                    .filter(id -> !id.isBlank())
                                    .map(Long::parseLong)
                                    .toList();
                    copy.remove("menuIdsText");
                    copy.put("menuIds", menuIds);
                    return copy;
                })
                .toList();
    }

    private Map<String, Object> booleanRow(Map<String, Object> row, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(key, numberAsLong(copy.get(key)) == 1L);
        return copy;
    }

    private Long numberAsLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        mapper.deleteUserRoles(userId);
        if (roleIds != null) {
            roleIds.forEach(roleId -> mapper.insertUserRole(userId, roleId));
        }
    }

    private void replaceUserPosts(Long userId, List<Long> postIds) {
        mapper.deleteUserPosts(userId);
        if (postIds != null) {
            postIds.forEach(postId -> mapper.insertUserPost(userId, postId));
        }
    }

    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        mapper.deleteRoleMenus(roleId);
        if (menuIds != null) {
            menuIds.forEach(menuId -> mapper.insertRoleMenu(roleId, menuId));
        }
    }

    private String ancestorsFor(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return "";
        }
        String parentAncestors = mapper.selectDeptAncestors(parentId);
        if (parentAncestors == null) {
            throw new BusinessException("上级部门不存在或已删除");
        }
        return parentAncestors.isBlank() ? String.valueOf(parentId) : parentAncestors + "," + parentId;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : String.valueOf(authentication.getPrincipal());
    }

    private String required(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BusinessException(message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String defaultString(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    /**
     * 新增菜单时归一化打开方式：未传按 page 处理，iframe 必须给出完整地址。
     */
    private String resolveOpenType(String openType, String component) {
        String trimmed = trimToNull(openType);
        if (trimmed == null) {
            return "page";
        }
        validateOpenType(trimmed, component);
        return trimmed;
    }

    /**
     * 编辑菜单时归一化打开方式：未传表示不修改，交给 SQL 的 COALESCE 保留原值。
     * 局部更新（如仅切换显示开关）不能把已有 iframe 菜单重置为 page。
     */
    private String normalizeOpenType(String openType, String component) {
        String trimmed = trimToNull(openType);
        if (trimmed == null) {
            return null;
        }
        validateOpenType(trimmed, component);
        return trimmed;
    }

    private void validateOpenType(String openType, String component) {
        if (!"page".equals(openType) && !"iframe".equals(openType) && !"external".equals(openType)) {
            throw new BusinessException("打开方式只能是 page、iframe 或 external");
        }
        if ("iframe".equals(openType) || "external".equals(openType)) {
            String url = trimToNull(component);
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new BusinessException("嵌入网站与新标签页打开需填写以 http:// 或 https:// 开头的地址");
            }
        }
    }

    private String csv(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append('\n');
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(csvCell(row.get(i)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<Object> csvRow(Object... values) {
        return Arrays.asList(values);
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains("\"")) {
            text = text.replace("\"", "\"\"");
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text + "\"";
        }
        return text;
    }

    private Object value(Map<String, Object> row, String key) {
        return row.get(key);
    }

    private String resultText(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 1 ? "成功" : "失败";
        }
        return "1".equals(String.valueOf(value)) ? "成功" : "失败";
    }

    private String userStatusText(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 1 ? "正常" : "停用";
        }
        return "1".equals(String.valueOf(value)) ? "正常" : "停用";
    }

    private int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private int boolAsInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private record Page(int pageNum, int pageSize, int offset) {
    }
}
