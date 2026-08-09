package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.OutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {
    @Select("""
            SELECT * FROM office_outbox_event WHERE status='PENDING' AND next_attempt_at <= #{now}
            ORDER BY created_at LIMIT #{limit}
            """)
    List<OutboxEventEntity> findPending(@Param("now") LocalDateTime now, @Param("limit") int limit);
    @Update("UPDATE office_outbox_event SET status='SENT',sent_at=#{sentAt},last_error=NULL WHERE event_id=#{id} AND status='PENDING'")
    int markSent(@Param("id") String id, @Param("sentAt") LocalDateTime sentAt);
    @Update("""
            UPDATE office_outbox_event SET attempts=attempts+1,next_attempt_at=#{next},last_error=#{error}
            WHERE event_id=#{id} AND status='PENDING'
            """)
    int markRetry(@Param("id") String id, @Param("next") LocalDateTime next, @Param("error") String error);
}
