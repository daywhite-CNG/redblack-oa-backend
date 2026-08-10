package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.FileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface FileMapper extends BaseMapper<FileEntity> {
    @Update("""
            UPDATE office_file SET status='RESERVED',reserved_by=#{reservationId},reserved_until=#{reservedUntil},updated_at=#{updatedAt}
            WHERE id=#{id} AND owner_id=#{ownerId} AND storage_status='AVAILABLE'
              AND (status='TEMPORARY' OR (status='RESERVED' AND reserved_by=#{reservationId}))
            """)
    int reserve(@Param("id") long id, @Param("ownerId") long ownerId,
                @Param("reservationId") String reservationId,
                @Param("reservedUntil") LocalDateTime reservedUntil,
                @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE office_file SET status='BOUND',reserved_by=NULL,reserved_until=NULL,
              bound_business_type=#{type},bound_business_id=#{businessId},updated_at=#{updatedAt}
            WHERE id=#{id} AND owner_id=#{ownerId} AND storage_status='AVAILABLE'
              AND (status='TEMPORARY' OR (status='RESERVED' AND reserved_by=#{reservationId}))
            """)
    int confirmReservation(@Param("id") long id, @Param("ownerId") long ownerId,
                           @Param("reservationId") String reservationId,
                           @Param("type") String type, @Param("businessId") long businessId,
                           @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT * FROM office_file WHERE bound_business_type=#{type} AND bound_business_id=#{businessId} ORDER BY created_at,id")
    List<FileEntity> findBound(@Param("type") String type, @Param("businessId") long businessId);
    @Update("""
            UPDATE office_file SET status='BOUND',reserved_by=NULL,reserved_until=NULL,
              bound_business_type=#{type},bound_business_id=#{businessId},updated_at=#{updatedAt}
            WHERE id=#{id} AND owner_id=#{ownerId} AND status='TEMPORARY' AND storage_status='AVAILABLE'
            """)
    int bind(@Param("id") long id, @Param("ownerId") long ownerId, @Param("type") String type,
             @Param("businessId") long businessId, @Param("updatedAt") LocalDateTime updatedAt);
    @Update("""
            UPDATE office_file SET status='TEMPORARY',bound_business_type=NULL,bound_business_id=NULL,updated_at=#{updatedAt}
            WHERE bound_business_type=#{type} AND bound_business_id=#{businessId}
            """)
    int unbindAll(@Param("type") String type, @Param("businessId") long businessId,
                  @Param("updatedAt") LocalDateTime updatedAt);
    @Select("SELECT * FROM office_file WHERE status='TEMPORARY' AND created_at < #{cutoff} ORDER BY created_at LIMIT #{limit}")
    List<FileEntity> findExpiredTemporary(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
    @Update("""
            UPDATE office_file SET status='TEMPORARY',reserved_by=NULL,reserved_until=NULL,updated_at=#{now}
            WHERE status='RESERVED' AND reserved_until < #{now}
            """)
    int releaseExpiredReservations(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE office_file SET storage_status='AVAILABLE',etag=#{etag},cleanup_attempts=0,
              cleanup_next_attempt_at=NULL,cleanup_last_error=NULL,updated_at=#{now}
            WHERE id=#{id} AND storage_status='PENDING'
            """)
    int markAvailable(@Param("id") long id, @Param("etag") String etag, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE office_file SET storage_status='DELETE_PENDING',cleanup_attempts=0,
              cleanup_next_attempt_at=#{now},cleanup_last_error=NULL,updated_at=#{now}
            WHERE id=#{id} AND owner_id=#{ownerId} AND status='TEMPORARY' AND storage_status='AVAILABLE'
            """)
    int markDeletePending(@Param("id") long id, @Param("ownerId") long ownerId,
                          @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM office_file
            WHERE (storage_status IN ('PENDING','DELETE_PENDING')
                    AND cleanup_next_attempt_at IS NOT NULL AND cleanup_next_attempt_at <= #{now})
               OR (storage_status='AVAILABLE' AND status='TEMPORARY' AND created_at < #{temporaryCutoff})
            ORDER BY COALESCE(cleanup_next_attempt_at,created_at),id
            LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<FileEntity> findCleanupCandidatesForUpdate(@Param("now") LocalDateTime now,
                                                     @Param("temporaryCutoff") LocalDateTime temporaryCutoff,
                                                     @Param("limit") int limit);

    @Update("""
            UPDATE office_file SET storage_status=#{storageStatus},cleanup_next_attempt_at=#{leaseUntil},updated_at=#{now}
            WHERE id=#{id} AND storage_status=#{expectedStatus}
            """)
    int claimCleanup(@Param("id") long id, @Param("expectedStatus") String expectedStatus,
                     @Param("storageStatus") String storageStatus,
                     @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE office_file SET cleanup_attempts=#{attempts},cleanup_next_attempt_at=#{nextAttemptAt},
              cleanup_last_error=#{lastError},updated_at=#{now}
            WHERE id=#{id} AND storage_status=#{storageStatus}
            """)
    int recordCleanupFailure(@Param("id") long id, @Param("storageStatus") String storageStatus,
                             @Param("attempts") int attempts,
                             @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                             @Param("lastError") String lastError, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM office_file WHERE id=#{id} AND storage_status=#{storageStatus}")
    int deleteByStorageStatus(@Param("id") long id, @Param("storageStatus") String storageStatus);
}
