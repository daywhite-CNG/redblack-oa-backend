package com.redblack.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface IdempotencyMapper {
    @Insert("""
            INSERT IGNORE INTO approval_idempotency_record
                (actor_id, http_method, request_path, idempotency_key, request_hash, status, created_at, expires_at)
            VALUES
                (#{actorId}, #{method}, #{path}, #{key}, #{hash}, 'PROCESSING', #{createdAt}, #{expiresAt})
            """)
    int claim(@Param("actorId") long actorId,
              @Param("method") String method,
              @Param("path") String path,
              @Param("key") String key,
              @Param("hash") String hash,
              @Param("createdAt") LocalDateTime createdAt,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT request_hash AS requestHash, status, response_body AS responseBody, expires_at AS expiresAt
            FROM approval_idempotency_record
            WHERE actor_id = #{actorId} AND http_method = #{method}
              AND request_path = #{path} AND idempotency_key = #{key}
            """)
    IdempotencyRecord find(@Param("actorId") long actorId,
                           @Param("method") String method,
                           @Param("path") String path,
                           @Param("key") String key);

    @Update("""
            UPDATE approval_idempotency_record
            SET status = 'COMPLETED', response_body = CAST(#{responseBody} AS JSON)
            WHERE actor_id = #{actorId} AND http_method = #{method}
              AND request_path = #{path} AND idempotency_key = #{key} AND status = 'PROCESSING'
            """)
    int complete(@Param("actorId") long actorId,
                 @Param("method") String method,
                 @Param("path") String path,
                 @Param("key") String key,
                 @Param("responseBody") String responseBody);

    @Delete("""
            DELETE FROM approval_idempotency_record
            WHERE actor_id = #{actorId} AND http_method = #{method}
              AND request_path = #{path} AND idempotency_key = #{key} AND expires_at <= #{now}
            """)
    int deleteExpired(@Param("actorId") long actorId,
                      @Param("method") String method,
                      @Param("path") String path,
                      @Param("key") String key,
                      @Param("now") LocalDateTime now);

    record IdempotencyRecord(String requestHash, String status, String responseBody, LocalDateTime expiresAt) {
    }
}
