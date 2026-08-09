package com.redblack.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AttachmentMapper {
    @Select("SELECT file_id FROM leave_application_attachment WHERE application_id = #{applicationId} ORDER BY sort_order")
    List<Long> findIds(@Param("applicationId") long applicationId);

    @Delete("DELETE FROM leave_application_attachment WHERE application_id = #{applicationId}")
    int deleteByApplication(@Param("applicationId") long applicationId);

    @Insert("INSERT INTO leave_application_attachment(application_id, file_id, sort_order, created_at) VALUES(#{applicationId}, #{fileId}, #{sortOrder}, #{createdAt})")
    int insert(@Param("applicationId") long applicationId,
               @Param("fileId") long fileId,
               @Param("sortOrder") int sortOrder,
               @Param("createdAt") LocalDateTime createdAt);
}
