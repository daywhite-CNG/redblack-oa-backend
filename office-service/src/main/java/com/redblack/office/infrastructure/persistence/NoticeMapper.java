package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.NoticeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NoticeMapper extends BaseMapper<NoticeEntity> {
    @Select("""
            <script>
            SELECT n.* FROM office_notice n
            WHERE
            <choose>
              <when test="manage">1=1</when>
              <otherwise>n.status='PUBLISHED' AND (n.scope_type='ALL' OR EXISTS (
                SELECT 1 FROM office_notice_department d WHERE d.notice_id=n.id AND d.department_id=#{departmentId}))</otherwise>
            </choose>
            <if test="keyword != null and keyword != ''">AND (n.title LIKE CONCAT('%',#{keyword},'%') OR n.summary LIKE CONCAT('%',#{keyword},'%'))</if>
            <if test="type != null">AND n.notice_type=#{type}</if>
            <if test="status != null">AND n.status=#{status}</if>
            <if test="readStatus == 'READ'">AND EXISTS (SELECT 1 FROM office_notice_read r WHERE r.notice_id=n.id AND r.user_id=#{userId})</if>
            <if test="readStatus == 'UNREAD'">AND NOT EXISTS (SELECT 1 FROM office_notice_read r WHERE r.notice_id=n.id AND r.user_id=#{userId})</if>
            ORDER BY n.is_pinned DESC, COALESCE(n.published_at,n.updated_at) DESC, n.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<NoticeEntity> listVisible(@Param("manage") boolean manage, @Param("userId") long userId,
                                   @Param("departmentId") long departmentId, @Param("keyword") String keyword,
                                   @Param("type") String type, @Param("status") String status,
                                   @Param("readStatus") String readStatus, @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM office_notice n
            WHERE
            <choose>
              <when test="manage">1=1</when>
              <otherwise>n.status='PUBLISHED' AND (n.scope_type='ALL' OR EXISTS (
                SELECT 1 FROM office_notice_department d WHERE d.notice_id=n.id AND d.department_id=#{departmentId}))</otherwise>
            </choose>
            <if test="keyword != null and keyword != ''">AND (n.title LIKE CONCAT('%',#{keyword},'%') OR n.summary LIKE CONCAT('%',#{keyword},'%'))</if>
            <if test="type != null">AND n.notice_type=#{type}</if>
            <if test="status != null">AND n.status=#{status}</if>
            <if test="readStatus == 'READ'">AND EXISTS (SELECT 1 FROM office_notice_read r WHERE r.notice_id=n.id AND r.user_id=#{userId})</if>
            <if test="readStatus == 'UNREAD'">AND NOT EXISTS (SELECT 1 FROM office_notice_read r WHERE r.notice_id=n.id AND r.user_id=#{userId})</if>
            </script>
            """)
    long countVisible(@Param("manage") boolean manage, @Param("userId") long userId,
                      @Param("departmentId") long departmentId, @Param("keyword") String keyword,
                      @Param("type") String type, @Param("status") String status,
                      @Param("readStatus") String readStatus);

    @Update("UPDATE office_notice SET read_count=read_count+1 WHERE id=#{noticeId}")
    int incrementReadCount(@Param("noticeId") long noticeId);

    @Delete("DELETE FROM office_notice WHERE id=#{id} AND version=#{version} AND status='DRAFT'")
    int deleteDraft(@Param("id") long id, @Param("version") int version);

    @Select("""
            SELECT COUNT(*) FROM office_notice n
            WHERE n.status='PUBLISHED' AND (n.scope_type='ALL' OR EXISTS (
              SELECT 1 FROM office_notice_department d WHERE d.notice_id=n.id AND d.department_id=#{departmentId}))
              AND NOT EXISTS (SELECT 1 FROM office_notice_read r WHERE r.notice_id=n.id AND r.user_id=#{userId})
            """)
    long unreadCount(@Param("userId") long userId, @Param("departmentId") long departmentId);

    @Select("""
            SELECT n.* FROM office_notice n
            WHERE n.status='PUBLISHED' AND (n.scope_type='ALL' OR EXISTS (
              SELECT 1 FROM office_notice_department d WHERE d.notice_id=n.id AND d.department_id=#{departmentId}))
            ORDER BY n.is_pinned DESC, n.published_at DESC LIMIT #{limit}
            """)
    List<NoticeEntity> recentVisible(@Param("departmentId") long departmentId, @Param("limit") int limit);
}
