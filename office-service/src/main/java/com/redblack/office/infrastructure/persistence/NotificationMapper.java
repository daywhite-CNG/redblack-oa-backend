package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.NotificationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationMapper extends BaseMapper<NotificationEntity> {
    @Select("SELECT * FROM office_notification WHERE id=#{id} AND recipient_id=#{userId}")
    NotificationEntity findForUser(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            <script>SELECT * FROM office_notification WHERE recipient_id=#{userId}
            <if test="readStatus == 'READ'">AND is_read=TRUE</if>
            <if test="readStatus == 'UNREAD'">AND is_read=FALSE</if>
            <if test="type != null">AND notification_type=#{type}</if>
            ORDER BY created_at DESC, id DESC LIMIT #{offset},#{pageSize}</script>
            """)
    List<NotificationEntity> listForUser(@Param("userId") long userId, @Param("readStatus") String readStatus,
                                         @Param("type") String type, @Param("offset") int offset,
                                         @Param("pageSize") int pageSize);
    @Select("""
            <script>SELECT COUNT(*) FROM office_notification WHERE recipient_id=#{userId}
            <if test="readStatus == 'READ'">AND is_read=TRUE</if>
            <if test="readStatus == 'UNREAD'">AND is_read=FALSE</if>
            <if test="type != null">AND notification_type=#{type}</if></script>
            """)
    long countForUser(@Param("userId") long userId, @Param("readStatus") String readStatus,
                      @Param("type") String type);
    @Update("UPDATE office_notification SET is_read=TRUE,read_at=#{readAt} WHERE id=#{id} AND recipient_id=#{userId} AND is_read=FALSE")
    int markRead(@Param("id") long id, @Param("userId") long userId, @Param("readAt") LocalDateTime readAt);
    @Update("UPDATE office_notification SET is_read=TRUE,read_at=#{readAt} WHERE recipient_id=#{userId} AND is_read=FALSE")
    int markAllRead(@Param("userId") long userId, @Param("readAt") LocalDateTime readAt);
    @Select("SELECT COUNT(*) FROM office_notification WHERE recipient_id=#{userId} AND is_read=FALSE")
    long unreadCount(@Param("userId") long userId);
}
