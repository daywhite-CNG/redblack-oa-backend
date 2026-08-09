package com.redblack.office.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface NoticeReadMapper {
    @Insert("INSERT IGNORE INTO office_notice_read(notice_id,user_id,read_at) VALUES(#{noticeId},#{userId},#{readAt})")
    int markRead(@Param("noticeId") long noticeId, @Param("userId") long userId,
                 @Param("readAt") LocalDateTime readAt);
    @Select("SELECT COUNT(*) FROM office_notice_read WHERE notice_id=#{noticeId} AND user_id=#{userId}")
    int isRead(@Param("noticeId") long noticeId, @Param("userId") long userId);
}
