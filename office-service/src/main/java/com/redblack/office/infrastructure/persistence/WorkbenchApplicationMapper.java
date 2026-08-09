package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.WorkbenchApplicationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkbenchApplicationMapper extends BaseMapper<WorkbenchApplicationEntity> {
    @Insert("""
            INSERT INTO office_workbench_application
              (application_id,application_no,applicant_id,applicant_name,department_id,department_name,
               leave_type,start_time,end_time,duration_hours,urgency,status,submission_round,application_version,
               created_at,submitted_at,updated_at)
            VALUES (#{applicationId},#{applicationNo},#{applicantId},#{applicantName},#{departmentId},#{departmentName},
               #{leaveType},#{startTime},#{endTime},#{durationHours},#{urgency},#{status},#{submissionRound},#{applicationVersion},
               #{createdAt},#{submittedAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE application_no=VALUES(application_no),applicant_id=VALUES(applicant_id),
               applicant_name=VALUES(applicant_name),department_id=VALUES(department_id),department_name=VALUES(department_name),
               leave_type=COALESCE(VALUES(leave_type),leave_type),start_time=COALESCE(VALUES(start_time),start_time),
               end_time=COALESCE(VALUES(end_time),end_time),duration_hours=COALESCE(VALUES(duration_hours),duration_hours),
               urgency=COALESCE(VALUES(urgency),urgency),status=VALUES(status),submission_round=VALUES(submission_round),
               application_version=VALUES(application_version),
               submitted_at=COALESCE(VALUES(submitted_at),submitted_at),updated_at=VALUES(updated_at)
            """)
    int upsert(WorkbenchApplicationEntity entity);

    @Select("SELECT COUNT(*) FROM office_workbench_application WHERE applicant_id=#{userId}")
    long countByApplicant(@Param("userId") long userId);
    @Select("""
            SELECT COALESCE(SUM(duration_hours),0) FROM office_workbench_application
            WHERE applicant_id=#{userId} AND status='APPROVED' AND start_time >= #{from} AND start_time < #{to}
            """)
    BigDecimal approvedHours(@Param("userId") long userId, @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);
    @Select("SELECT * FROM office_workbench_application WHERE applicant_id=#{userId} ORDER BY updated_at DESC LIMIT #{limit}")
    List<WorkbenchApplicationEntity> recent(@Param("userId") long userId, @Param("limit") int limit);
    @Select("""
            SELECT leave_type AS itemKey,leave_type AS label,COUNT(*) AS value
            FROM office_workbench_application WHERE applicant_id=#{userId} AND leave_type IS NOT NULL
            GROUP BY leave_type ORDER BY leave_type
            """)
    List<ChartRow> leaveTypeDistribution(@Param("userId") long userId);
    @Select("""
            SELECT status AS itemKey,status AS label,COUNT(*) AS value
            FROM office_workbench_application WHERE applicant_id=#{userId}
            GROUP BY status ORDER BY status
            """)
    List<ChartRow> statusDistribution(@Param("userId") long userId);
    record ChartRow(String itemKey, String label, BigDecimal value) { }
}
