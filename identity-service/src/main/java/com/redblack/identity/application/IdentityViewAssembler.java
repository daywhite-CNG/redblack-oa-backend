package com.redblack.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.*;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.infrastructure.persistence.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IdentityViewAssembler {
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final MenuMapper menuMapper;
    private final AssignmentMapper assignmentMapper;

    public IdentityViewAssembler(UserMapper userMapper,
                                 DepartmentMapper departmentMapper,
                                 RoleMapper roleMapper,
                                 PermissionMapper permissionMapper,
                                 MenuMapper menuMapper,
                                 AssignmentMapper assignmentMapper) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.menuMapper = menuMapper;
        this.assignmentMapper = assignmentMapper;
    }

    public CurrentUser currentUser(UserEntity user, AuthorizationSnapshot snapshot) {
        DepartmentEntity department = departmentMapper.selectById(user.getDepartmentId());
        return new CurrentUser(id(user.getId()), user.getUsername(), user.getName(), user.getAvatarUrl(),
                department == null ? null : new DepartmentRef(id(department.getId()), department.getName()),
                snapshot.roles(), snapshot.permissions(), authorizedMenuTree(user.getId()));
    }

    public UserView user(UserEntity user) {
        DepartmentEntity department = departmentMapper.selectById(user.getDepartmentId());
        UserEntity leader = user.getLeaderId() == null ? null : userMapper.selectById(user.getLeaderId());
        List<RoleRef> roles = userMapper.findRoles(user.getId()).stream()
                .map(role -> new RoleRef(id(role.getId()), role.getCode(), role.getName()))
                .toList();
        return new UserView(id(user.getId()), user.getUsername(), user.getName(), user.getGender(), user.getPhone(),
                user.getEmail(), user.getAvatarUrl(),
                department == null ? null : new DepartmentRef(id(department.getId()), department.getName()),
                leader == null ? null : ref(leader), roles, user.getStatus(), user.getRemark(),
                offset(user.getCreatedAt()), offset(user.getUpdatedAt()), user.getVersion());
    }

    public UserRef ref(UserEntity user) {
        return new UserRef(id(user.getId()), user.getName(), id(user.getDepartmentId()));
    }

    public List<DepartmentNode> departmentTree(Collection<Long> allowedIds) {
        List<DepartmentEntity> departments = departmentMapper.selectList(new LambdaQueryWrapper<DepartmentEntity>()
                .orderByAsc(DepartmentEntity::getSortOrder).orderByAsc(DepartmentEntity::getId));
        Set<Long> allowed = allowedIds == null ? null : new HashSet<>(allowedIds);
        Map<Long, List<DepartmentEntity>> children = departments.stream()
                .filter(department -> allowed == null || allowed.contains(department.getId()))
                .collect(Collectors.groupingBy(department -> department.getParentId() == null
                                || (allowed != null && !allowed.contains(department.getParentId())) ? 0L : department.getParentId(),
                        LinkedHashMap::new, Collectors.toList()));
        return children.getOrDefault(0L, List.of()).stream().map(department -> departmentNode(department, children)).toList();
    }

    public DepartmentNode department(DepartmentEntity department) {
        return departmentNode(department, Map.of());
    }

    public RoleView role(RoleEntity role) {
        List<String> customDepartments = assignmentMapper.findRoleDepartmentIds(role.getId()).stream()
                .map(String::valueOf).toList();
        return new RoleView(id(role.getId()), role.getCode(), role.getName(), role.getDataScope(), customDepartments,
                role.getStatus(), userMapper.countRoleMembers(role.getId()), Boolean.TRUE.equals(role.getSystemRole()),
                role.getRemark(), offset(role.getCreatedAt()), offset(role.getUpdatedAt()), role.getVersion());
    }

    public RolePermissionView rolePermissions(RoleEntity role) {
        return new RolePermissionView(id(role.getId()),
                assignmentMapper.findRolePermissionIds(role.getId()).stream().map(String::valueOf).toList(),
                role.getDataScope(), assignmentMapper.findRoleDepartmentIds(role.getId()).stream()
                .map(String::valueOf).toList(), role.getVersion());
    }

    public List<MenuNode> fullMenuTree() {
        return menuTree(menuMapper.selectList(new LambdaQueryWrapper<MenuEntity>()
                .orderByAsc(MenuEntity::getSortOrder).orderByAsc(MenuEntity::getId)));
    }

    public MenuNode menu(MenuEntity menu) {
        Map<Long, String> permissionCodes = permissionCodes();
        return menuNode(menu, Map.of(), permissionCodes);
    }

    private List<MenuNode> authorizedMenuTree(long userId) {
        Set<Long> ids = new HashSet<>(assignmentMapper.findUserMenuIds(userId));
        List<MenuEntity> menus = menuMapper.selectList(new LambdaQueryWrapper<MenuEntity>()
                        .eq(MenuEntity::getStatus, EnabledStatus.ENABLED)
                        .eq(MenuEntity::getVisible, true)
                        .orderByAsc(MenuEntity::getSortOrder).orderByAsc(MenuEntity::getId)).stream()
                .filter(menu -> ids.contains(menu.getId()))
                .toList();
        return menuTree(menus);
    }

    private List<MenuNode> menuTree(List<MenuEntity> menus) {
        Map<Long, List<MenuEntity>> children = menus.stream()
                .collect(Collectors.groupingBy(menu -> menu.getParentId() == null ? 0L : menu.getParentId(),
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, String> permissionCodes = permissionCodes();
        return children.getOrDefault(0L, List.of()).stream()
                .map(menu -> menuNode(menu, children, permissionCodes)).toList();
    }

    private DepartmentNode departmentNode(DepartmentEntity department,
                                          Map<Long, List<DepartmentEntity>> children) {
        UserEntity leader = department.getLeaderId() == null ? null : userMapper.selectById(department.getLeaderId());
        List<DepartmentNode> childNodes = children.getOrDefault(department.getId(), List.of()).stream()
                .map(child -> departmentNode(child, children)).toList();
        return new DepartmentNode(id(department.getId()), id(department.getParentId()), department.getName(),
                leader == null ? null : ref(leader), department.getSortOrder(), department.getStatus(),
                userMapper.countEnabledDepartmentMembers(department.getId()), offset(department.getCreatedAt()),
                department.getVersion(), childNodes);
    }

    private MenuNode menuNode(MenuEntity menu,
                              Map<Long, List<MenuEntity>> children,
                              Map<Long, String> permissionCodes) {
        List<MenuNode> childNodes = children.getOrDefault(menu.getId(), List.of()).stream()
                .map(child -> menuNode(child, children, permissionCodes)).toList();
        return new MenuNode(id(menu.getId()), id(menu.getParentId()), menu.getName(), menu.getIcon(), menu.getType(),
                menu.getRoutePath(), menu.getComponent(), permissionCodes.get(menu.getPermissionId()),
                menu.getSortOrder(), menu.getStatus(), Boolean.TRUE.equals(menu.getVisible()), menu.getVersion(), childNodes);
    }

    private Map<Long, String> permissionCodes() {
        return permissionMapper.selectList(null).stream()
                .collect(Collectors.toMap(PermissionEntity::getId, PermissionEntity::getCode));
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
