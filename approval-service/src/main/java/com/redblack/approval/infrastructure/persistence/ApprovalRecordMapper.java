package com.redblack.approval.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.approval.domain.ApprovalRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecordEntity> {
    @Select("SELECT * FROM approval_record WHERE application_id = #{applicationId} ORDER BY submission_round, operated_at, id")
    List<ApprovalRecordEntity> findByApplication(@Param("applicationId") long applicationId);
}
