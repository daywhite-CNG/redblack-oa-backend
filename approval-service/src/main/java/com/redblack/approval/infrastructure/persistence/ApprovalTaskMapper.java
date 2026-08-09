package com.redblack.approval.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import com.redblack.approval.domain.ApprovalTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ApprovalTaskMapper extends BaseMapper<ApprovalTaskEntity> {
    @Select("SELECT * FROM approval_task WHERE id = #{id} FOR UPDATE")
    ApprovalTaskEntity selectForUpdate(@Param("id") long id);

    @Update("""
            UPDATE approval_task SET
              status = #{entity.status}, comment = #{entity.comment}, processed_at = #{entity.processedAt},
              version = version + 1
            WHERE id = #{entity.id} AND version = #{entity.version}
            """)
    int updateState(@Param("entity") ApprovalTaskEntity entity);

    @Select("""
            SELECT * FROM approval_task
            WHERE application_id = #{applicationId} AND submission_round = #{submissionRound} AND status = 'PENDING'
            LIMIT 1 FOR UPDATE
            """)
    ApprovalTaskEntity findPendingForUpdate(@Param("applicationId") long applicationId,
                                            @Param("submissionRound") int submissionRound);

    @Select("""
            SELECT * FROM approval_task
            WHERE application_id = #{applicationId} AND submission_round = #{submissionRound} AND status = 'PENDING'
            LIMIT 1
            """)
    ApprovalTaskEntity findPending(@Param("applicationId") long applicationId,
                                   @Param("submissionRound") int submissionRound);

    @Select("SELECT COUNT(*) FROM approval_task WHERE application_id = #{applicationId} AND assignee_id = #{userId}")
    long countHandledBy(@Param("applicationId") long applicationId, @Param("userId") long userId);

    @Select("SELECT * FROM approval_task WHERE application_id = #{applicationId} ORDER BY created_at, id")
    List<ApprovalTaskEntity> findByApplication(@Param("applicationId") long applicationId);

    @Select("""
            <script>
            SELECT t.* FROM approval_task t
            JOIN leave_application a ON a.id = t.application_id
            WHERE a.deleted = FALSE AND t.assignee_id = #{actorId}
            <choose>
              <when test="pending">AND t.status = 'PENDING'</when>
              <otherwise>AND t.status != 'PENDING'</otherwise>
            </choose>
            <if test="applicationNo != null and applicationNo != ''">
              AND a.application_no LIKE CONCAT('%', #{applicationNo}, '%')
            </if>
            <if test="applicantName != null and applicantName != ''">
              AND a.applicant_name LIKE CONCAT('%', #{applicantName}, '%')
            </if>
            <if test="departmentId != null">AND a.department_id = #{departmentId}</if>
            <if test="leaveType != null">AND a.leave_type = #{leaveType}</if>
            <if test="urgency != null">AND a.urgency = #{urgency}</if>
            <if test="processedFrom != null">AND t.processed_at &gt;= #{processedFrom}</if>
            <if test="processedTo != null">AND t.processed_at &lt;= #{processedTo}</if>
            ORDER BY ${orderBy}
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<ApprovalTaskEntity> findPage(@Param("actorId") long actorId,
                                      @Param("pending") boolean pending,
                                      @Param("applicationNo") String applicationNo,
                                      @Param("applicantName") String applicantName,
                                      @Param("departmentId") Long departmentId,
                                      @Param("leaveType") LeaveType leaveType,
                                      @Param("urgency") Urgency urgency,
                                      @Param("processedFrom") LocalDateTime processedFrom,
                                      @Param("processedTo") LocalDateTime processedTo,
                                      @Param("orderBy") String orderBy,
                                      @Param("offset") long offset,
                                      @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM approval_task t
            JOIN leave_application a ON a.id = t.application_id
            WHERE a.deleted = FALSE AND t.assignee_id = #{actorId}
            <choose>
              <when test="pending">AND t.status = 'PENDING'</when>
              <otherwise>AND t.status != 'PENDING'</otherwise>
            </choose>
            <if test="applicationNo != null and applicationNo != ''">
              AND a.application_no LIKE CONCAT('%', #{applicationNo}, '%')
            </if>
            <if test="applicantName != null and applicantName != ''">
              AND a.applicant_name LIKE CONCAT('%', #{applicantName}, '%')
            </if>
            <if test="departmentId != null">AND a.department_id = #{departmentId}</if>
            <if test="leaveType != null">AND a.leave_type = #{leaveType}</if>
            <if test="urgency != null">AND a.urgency = #{urgency}</if>
            <if test="processedFrom != null">AND t.processed_at &gt;= #{processedFrom}</if>
            <if test="processedTo != null">AND t.processed_at &lt;= #{processedTo}</if>
            </script>
            """)
    long countPage(@Param("actorId") long actorId,
                   @Param("pending") boolean pending,
                   @Param("applicationNo") String applicationNo,
                   @Param("applicantName") String applicantName,
                   @Param("departmentId") Long departmentId,
                   @Param("leaveType") LeaveType leaveType,
                   @Param("urgency") Urgency urgency,
                   @Param("processedFrom") LocalDateTime processedFrom,
                   @Param("processedTo") LocalDateTime processedTo);
}
