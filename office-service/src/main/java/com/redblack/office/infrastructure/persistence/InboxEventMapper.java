package com.redblack.office.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface InboxEventMapper {
    @Insert("""
            INSERT IGNORE INTO office_inbox_event(event_id,topic,event_type,payload,status,attempts,next_attempt_at,received_at)
            VALUES(#{eventId},#{topic},#{eventType},CAST(#{payload} AS JSON),'PENDING',0,#{receivedAt},#{receivedAt})
            """)
    int receive(@Param("eventId") String eventId, @Param("topic") String topic,
                @Param("eventType") String eventType, @Param("payload") String payload,
                @Param("receivedAt") LocalDateTime receivedAt);
    @Select("SELECT status,attempts FROM office_inbox_event WHERE event_id=#{eventId}")
    State state(@Param("eventId") String eventId);
    @Update("UPDATE office_inbox_event SET status='PROCESSED',processed_at=#{at},last_error=NULL WHERE event_id=#{eventId} AND status='PENDING'")
    int markProcessed(@Param("eventId") String eventId, @Param("at") LocalDateTime at);
    @Update("""
            UPDATE office_inbox_event SET attempts=attempts+1,next_attempt_at=#{next},last_error=#{error}
            WHERE event_id=#{eventId} AND status='PENDING'
            """)
    int markFailure(@Param("eventId") String eventId, @Param("next") LocalDateTime next, @Param("error") String error);
    @Update("UPDATE office_inbox_event SET status='DEAD',last_error=#{error} WHERE event_id=#{eventId} AND status='PENDING'")
    int markDead(@Param("eventId") String eventId, @Param("error") String error);
    record State(String status, int attempts) { }
}
