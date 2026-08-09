package com.redblack.identity.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AssignmentMapper {
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    void deleteUserRoles(long userId);

    @Insert("""
            <script>
            INSERT INTO sys_user_role (user_id, role_id) VALUES
            <foreach collection='roleIds' item='roleId' separator=','>(#{userId}, #{roleId})</foreach>
            </script>
            """)
    void insertUserRoles(@Param("userId") long userId, @Param("roleIds") List<Long> roleIds);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} ORDER BY role_id")
    List<Long> findUserRoleIds(long userId);

    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId} ORDER BY user_id")
    List<Long> findRoleUserIds(long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    void deleteRolePermissions(long roleId);

    @Insert("""
            <script>
            INSERT INTO sys_role_permission (role_id, permission_id) VALUES
            <foreach collection='permissionIds' item='permissionId' separator=','>(#{roleId}, #{permissionId})</foreach>
            </script>
            """)
    void insertRolePermissions(@Param("roleId") long roleId,
                               @Param("permissionIds") List<Long> permissionIds);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId} ORDER BY permission_id")
    List<Long> findRolePermissionIds(long roleId);

    @Delete("DELETE FROM sys_role_department WHERE role_id = #{roleId}")
    void deleteRoleDepartments(long roleId);

    @Insert("""
            <script>
            INSERT INTO sys_role_department (role_id, department_id) VALUES
            <foreach collection='departmentIds' item='departmentId' separator=','>(#{roleId}, #{departmentId})</foreach>
            </script>
            """)
    void insertRoleDepartments(@Param("roleId") long roleId,
                               @Param("departmentIds") List<Long> departmentIds);

    @Select("SELECT department_id FROM sys_role_department WHERE role_id = #{roleId} ORDER BY department_id")
    List<Long> findRoleDepartmentIds(long roleId);

    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    void deleteRoleMenus(long roleId);

    @Insert("""
            INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
            SELECT #{roleId}, m.id FROM sys_menu m
            JOIN sys_role_permission rp ON rp.permission_id = m.permission_id
            WHERE rp.role_id = #{roleId}
            """)
    void insertRoleMenusForPermissions(long roleId);

    @Insert("""
            INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
            SELECT #{roleId}, m.parent_id FROM sys_role_menu rm
            JOIN sys_menu m ON m.id = rm.menu_id
            WHERE rm.role_id = #{roleId} AND m.parent_id IS NOT NULL
            """)
    int insertRoleMenuParents(long roleId);

    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id IN (SELECT role_id FROM sys_user_role WHERE user_id = #{userId})")
    List<Long> findUserMenuIds(long userId);

    @Select("SELECT id FROM sys_role ORDER BY id")
    List<Long> findAllRoleIds();
}
