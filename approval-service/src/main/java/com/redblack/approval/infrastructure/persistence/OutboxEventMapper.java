package com.redblack.approval.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.approval.domain.OutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {
    @Select("""
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<OutboxEventEntity> findPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event SET status = 'SENT', sent_at = #{sentAt}, last_error = NULL
            WHERE event_id = #{eventId} AND status = 'PENDING'
            """)
    int markSent(@Param("eventId") String eventId, @Param("sentAt") LocalDateTime sentAt);

    @Update("""
            UPDATE outbox_event
            SET attempts = attempts + 1, next_attempt_at = #{nextAttemptAt}, last_error = #{lastError}
            WHERE event_id = #{eventId} AND status = 'PENDING'
            """)
    int markRetry(@Param("eventId") String eventId,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("lastError") String lastError);
}
