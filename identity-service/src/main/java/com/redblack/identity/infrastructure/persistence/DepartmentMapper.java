package com.redblack.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.identity.domain.DepartmentEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DepartmentMapper extends BaseMapper<DepartmentEntity> {
    @Select("SELECT COUNT(*) FROM sys_department WHERE parent_id = #{departmentId}")
    long countChildren(long departmentId);

    @Select("SELECT COUNT(*) FROM sys_department WHERE leader_id = #{userId}")
    long countLeaderReferences(long userId);

    @Select("SELECT COUNT(*) FROM sys_role_department WHERE department_id = #{departmentId}")
    long countRoleReferences(long departmentId);

    @Select("""
            WITH RECURSIVE descendants AS (
                SELECT id, parent_id FROM sys_department WHERE id = #{departmentId}
                UNION ALL
                SELECT d.id, d.parent_id FROM sys_department d
                JOIN descendants x ON d.parent_id = x.id
            )
            SELECT COUNT(*) FROM descendants WHERE id = #{candidateParentId}
            """)
    long candidateIsDescendant(long departmentId, long candidateParentId);

    @Select("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM sys_department WHERE id = #{departmentId}
                UNION ALL
                SELECT d.id FROM sys_department d JOIN descendants x ON d.parent_id = x.id
            )
            SELECT id FROM descendants ORDER BY id
            """)
    List<Long> findDepartmentAndDescendantIds(long departmentId);
}
