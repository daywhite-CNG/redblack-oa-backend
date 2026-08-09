package com.redblack.office.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface NoticeDepartmentMapper {
    @Insert("INSERT INTO office_notice_department(notice_id,department_id) VALUES(#{noticeId},#{departmentId})")
    int insert(@Param("noticeId") long noticeId, @Param("departmentId") long departmentId);
    @Delete("DELETE FROM office_notice_department WHERE notice_id=#{noticeId}")
    int deleteByNotice(@Param("noticeId") long noticeId);
    @Select("SELECT department_id FROM office_notice_department WHERE notice_id=#{noticeId} ORDER BY department_id")
    List<Long> findByNotice(@Param("noticeId") long noticeId);
    @Select("SELECT COUNT(*) FROM office_notice_department WHERE notice_id=#{noticeId} AND department_id=#{departmentId}")
    int includes(@Param("noticeId") long noticeId, @Param("departmentId") long departmentId);
}
