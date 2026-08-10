package com.redblack.office.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface IdempotencyMapper {
    @Insert("""
            INSERT IGNORE INTO office_idempotency_record
              (actor_id,http_method,request_path,idempotency_key,request_hash,status,created_at,expires_at)
            VALUES (#{actorId},#{method},#{path},#{key},#{hash},'PROCESSING',#{createdAt},#{expiresAt})
            """)
    int claim(@Param("actorId") long actorId, @Param("method") String method, @Param("path") String path,
              @Param("key") String key, @Param("hash") String hash, @Param("createdAt") LocalDateTime createdAt,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT request_hash AS requestHash,status,response_body AS responseBody,file_id AS fileId,
              created_at AS createdAt,expires_at AS expiresAt
            FROM office_idempotency_record WHERE actor_id=#{actorId} AND http_method=#{method}
              AND request_path=#{path} AND idempotency_key=#{key}
            """)
    Record find(@Param("actorId") long actorId, @Param("method") String method,
                @Param("path") String path, @Param("key") String key);

    @Update("""
            UPDATE office_idempotency_record SET status='COMPLETED',response_body=CAST(#{body} AS JSON),
              file_id=#{fileId}
            WHERE actor_id=#{actorId} AND http_method=#{method} AND request_path=#{path}
              AND idempotency_key=#{key} AND status='PROCESSING'
            """)
    int complete(@Param("actorId") long actorId, @Param("method") String method,
                 @Param("path") String path, @Param("key") String key, @Param("body") String body,
                 @Param("fileId") Long fileId);

    @Update("""
            UPDATE office_idempotency_record SET file_id=#{fileId}
            WHERE actor_id=#{actorId} AND http_method=#{method} AND request_path=#{path}
              AND idempotency_key=#{key} AND status='PROCESSING' AND file_id IS NULL
            """)
    int associateFile(@Param("actorId") long actorId, @Param("method") String method,
                      @Param("path") String path, @Param("key") String key,
                      @Param("fileId") long fileId);

    @Delete("""
            DELETE FROM office_idempotency_record WHERE actor_id=#{actorId} AND http_method=#{method}
              AND request_path=#{path} AND idempotency_key=#{key} AND expires_at <= #{now}
            """)
    int deleteExpired(@Param("actorId") long actorId, @Param("method") String method,
                      @Param("path") String path, @Param("key") String key, @Param("now") LocalDateTime now);

    record Record(String requestHash, String status, String responseBody, Long fileId,
                  LocalDateTime createdAt, LocalDateTime expiresAt) { }
}
