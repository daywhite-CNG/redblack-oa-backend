package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.FileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface FileMapper extends BaseMapper<FileEntity> {
    @Update("""
            UPDATE office_file SET status='RESERVED',reserved_by=#{reservationId},reserved_until=#{reservedUntil},updated_at=#{updatedAt}
            WHERE id=#{id} AND owner_id=#{ownerId}
              AND (status='TEMPORARY' OR (status='RESERVED' AND reserved_by=#{reservationId}))
            """)
    int reserve(@Param("id") long id, @Param("ownerId") long ownerId,
                @Param("reservationId") String reservationId,
                @Param("reservedUntil") LocalDateTime reservedUntil,
                @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE office_file SET status='BOUND',reserved_by=NULL,reserved_until=NULL,
              bound_business_type=#{type},bound_business_id=#{businessId},updated_at=#{updatedAt}
            WHERE id=#{id} AND owner_id=#{ownerId}
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
            WHERE id=#{id} AND owner_id=#{ownerId} AND status='TEMPORARY'
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
}
