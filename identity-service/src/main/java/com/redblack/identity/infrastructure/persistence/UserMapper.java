package com.redblack.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.identity.domain.RoleEntity;
import com.redblack.identity.domain.UserEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    UserEntity findByUsername(String username);

    @Select("""
            SELECT r.* FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.id
            """)
    List<RoleEntity> findRoles(long userId);

    @Select("""
            SELECT DISTINCT p.code FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_role r ON r.id = rp.role_id AND r.status = 'ENABLED'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND p.status = 'ENABLED'
            ORDER BY p.code
            """)
    List<String> findPermissionCodes(long userId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE leader_id = #{userId}")
    long countDirectReports(long userId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE department_id = #{departmentId}")
    long countDepartmentMembers(long departmentId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE department_id = #{departmentId} AND status = 'ENABLED'")
    long countEnabledDepartmentMembers(long departmentId);

    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    long countRoleMembers(long roleId);

    @Select("""
            SELECT u.* FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            WHERE ur.role_id = #{roleId}
            ORDER BY u.id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserEntity> findRoleMembers(@Param("roleId") long roleId,
                                     @Param("offset") long offset,
                                     @Param("limit") long limit);

    @Select("SELECT id FROM sys_user WHERE department_id = #{departmentId}")
    List<Long> findIdsByDepartment(long departmentId);

    @Select("SELECT id FROM sys_user ORDER BY id")
    List<Long> findAllIds();

    @Select("""
            <script>
            SELECT * FROM sys_user WHERE status='ENABLED'
            <if test="departmentIds != null and !departmentIds.isEmpty()">
              AND department_id IN
              <foreach collection="departmentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            ORDER BY id
            </script>
            """)
    List<UserEntity> findEnabledAudience(@Param("departmentIds") List<Long> departmentIds);
}
