package com.redblack.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.*;
import com.redblack.identity.domain.IdentityEnums.MenuType;
import com.redblack.identity.infrastructure.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MenuApplicationService {
    private final MenuMapper menuMapper;
    private final PermissionMapper permissionMapper;
    private final AssignmentMapper assignmentMapper;
    private final UserMapper userMapper;
    private final AuthorizationSnapshotService authorization;
    private final IdentityViewAssembler assembler;
    private final OutboxService outbox;

    public MenuApplicationService(MenuMapper menuMapper,
                                  PermissionMapper permissionMapper,
                                  AssignmentMapper assignmentMapper,
                                  UserMapper userMapper,
                                  AuthorizationSnapshotService authorization,
                                  IdentityViewAssembler assembler,
                                  OutboxService outbox) {
        this.menuMapper = menuMapper;
        this.permissionMapper = permissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.userMapper = userMapper;
        this.authorization = authorization;
        this.assembler = assembler;
        this.outbox = outbox;
    }

    public List<MenuNode> tree(Jwt jwt) {
        authorization.require(jwt, "system:menu:read");
        return assembler.fullMenuTree();
    }

    public MenuNode get(Jwt jwt, long menuId) {
        authorization.require(jwt, "system:menu:read");
        return assembler.menu(required(menuId));
    }

    public void authorizeCreate(Jwt jwt) {
        authorization.require(jwt, "system:menu:create");
    }

    @Transactional
    public MenuNode create(Jwt jwt, CreateMenuRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:menu:create");
        Long parentId = nullableId(request.parentId());
        validateShape(request.type(), parentId, request.routePath(), request.component(), request.permission());
        validateParent(parentId, null);
        MenuEntity menu = new MenuEntity();
        apply(menu, request.parentId(), request.name(), request.icon(), request.type(), request.routePath(),
                request.component(), request.permission(), request.sortOrder(), request.status(), request.visible());
        menu.setVersion(1);
        menuMapper.insert(menu);
        rebuildRoleMenus();
        outbox.audit("MENU_CREATED", "MENU", Long.toString(menu.getId()), actor.userId(), requestId);
        return assembler.menu(required(menu.getId()));
    }

    @Transactional
    public MenuNode update(Jwt jwt, long menuId, UpdateMenuRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:menu:update");
        MenuEntity menu = required(menuId);
        if (!menu.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        Long parentId = nullableId(request.parentId());
        validateShape(request.type(), parentId, request.routePath(), request.component(), request.permission());
        validateParent(parentId, menuId);
        if (parentId != null && (parentId == menuId || menuMapper.candidateIsDescendant(menuId, parentId) > 0)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "菜单不能移动到自身或下级节点");
        }
        if (request.type() == MenuType.BUTTON && menuMapper.countChildren(menuId) > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "含子节点的菜单不能改为按钮");
        }
        apply(menu, request.parentId(), request.name(), request.icon(), request.type(), request.routePath(),
                request.component(), request.permission(), request.sortOrder(), request.status(), request.visible());
        if (menuMapper.updateById(menu) != 1) {
            throw BusinessException.versionConflict();
        }
        rebuildRoleMenus();
        outbox.audit("MENU_UPDATED", "MENU", Long.toString(menuId), actor.userId(), requestId);
        return assembler.menu(required(menuId));
    }

    @Transactional
    public MenuNode changeStatus(Jwt jwt, long menuId, ChangeStatusRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:menu:update");
        MenuEntity menu = required(menuId);
        if (!menu.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        menu.setStatus(request.status());
        if (menuMapper.updateById(menu) != 1) {
            throw BusinessException.versionConflict();
        }
        rebuildRoleMenus();
        outbox.audit("MENU_STATUS_CHANGED", "MENU", Long.toString(menuId), actor.userId(), requestId);
        return assembler.menu(required(menuId));
    }

    @Transactional
    public void delete(Jwt jwt, long menuId, int version, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:menu:delete");
        MenuEntity menu = required(menuId);
        if (!menu.getVersion().equals(version)) {
            throw BusinessException.versionConflict();
        }
        if (menuMapper.countChildren(menuId) > 0 || menuMapper.countRoleReferences(menuId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "菜单仍有子节点或角色引用");
        }
        menuMapper.deleteById(menuId);
        rebuildRoleMenus();
        outbox.audit("MENU_DELETED", "MENU", Long.toString(menuId), actor.userId(), requestId);
    }

    private void apply(MenuEntity menu,
                       String parentId,
                       String name,
                       String icon,
                       MenuType type,
                       String routePath,
                       String component,
                       String permission,
                       int sortOrder,
                       com.redblack.identity.domain.IdentityEnums.EnabledStatus status,
                       boolean visible) {
        menu.setParentId(nullableId(parentId));
        menu.setName(name);
        menu.setIcon(blankToNull(icon));
        menu.setType(type);
        menu.setRoutePath(blankToNull(routePath));
        menu.setComponent(blankToNull(component));
        menu.setPermissionId(permissionId(permission));
        menu.setSortOrder(sortOrder);
        menu.setStatus(status);
        menu.setVisible(visible);
    }

    private void validateShape(MenuType type, Long parentId, String routePath, String component, String permission) {
        if (type == MenuType.MENU && (!hasText(routePath) || !hasText(component))) {
            throw invalidShape("菜单必须配置路由和组件");
        }
        if (type == MenuType.BUTTON && (parentId == null || hasText(routePath) || hasText(component) || !hasText(permission))) {
            throw invalidShape("按钮必须有父节点和权限标识，且不能配置路由或组件");
        }
    }

    private void validateParent(Long parentId, Long menuId) {
        if (parentId == null) {
            return;
        }
        MenuEntity parent = required(parentId);
        if (parent.getType() == MenuType.BUTTON || parentId.equals(menuId)) {
            throw invalidShape("按钮不能作为父节点");
        }
    }

    private Long permissionId(String permission) {
        if (!hasText(permission)) {
            return null;
        }
        PermissionEntity entity = permissionMapper.selectOne(new LambdaQueryWrapper<PermissionEntity>()
                .eq(PermissionEntity::getCode, permission));
        if (entity == null) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "权限标识不存在");
        }
        return entity.getId();
    }

    private void rebuildRoleMenus() {
        for (Long roleId : assignmentMapper.findAllRoleIds()) {
            assignmentMapper.deleteRoleMenus(roleId);
            assignmentMapper.insertRoleMenusForPermissions(roleId);
            while (assignmentMapper.insertRoleMenuParents(roleId) > 0) {
                // Build all directory ancestors.
            }
        }
        userMapper.findAllIds().forEach(authorization::evict);
    }

    private MenuEntity required(long menuId) {
        MenuEntity menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw BusinessException.notFound("菜单不存在");
        }
        return menu;
    }

    private Long nullableId(String value) {
        return hasText(value) ? parseId(value) : null;
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "ID 格式不正确");
        }
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private BusinessException invalidShape(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
    }
}
