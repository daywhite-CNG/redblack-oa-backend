package com.redblack.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.identity.domain.MenuEntity;
import org.apache.ibatis.annotations.Select;

public interface MenuMapper extends BaseMapper<MenuEntity> {
    @Select("SELECT COUNT(*) FROM sys_menu WHERE parent_id = #{menuId}")
    long countChildren(long menuId);

    @Select("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = #{menuId}")
    long countRoleReferences(long menuId);

    @Select("SELECT COALESCE(CAST(UNIX_TIMESTAMP(MAX(updated_at)) * 1000 AS UNSIGNED), 1) FROM sys_menu")
    long findMenuTreeVersion();

    @Select("""
            WITH RECURSIVE descendants AS (
                SELECT id, parent_id FROM sys_menu WHERE id = #{menuId}
                UNION ALL
                SELECT m.id, m.parent_id FROM sys_menu m JOIN descendants d ON m.parent_id = d.id
            )
            SELECT COUNT(*) FROM descendants WHERE id = #{candidateParentId}
            """)
    long candidateIsDescendant(long menuId, long candidateParentId);
}
