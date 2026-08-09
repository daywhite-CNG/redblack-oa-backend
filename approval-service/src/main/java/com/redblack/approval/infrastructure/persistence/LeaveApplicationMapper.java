package com.redblack.approval.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.LeaveApplicationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface LeaveApplicationMapper extends BaseMapper<LeaveApplicationEntity> {
    @Select("SELECT * FROM leave_application WHERE id = #{id} AND deleted = FALSE FOR UPDATE")
    LeaveApplicationEntity selectForUpdate(@Param("id") long id);

    @Update("""
            UPDATE leave_application
            SET deleted = TRUE, deleted_at = #{deletedAt}, updated_at = #{deletedAt}, version = version + 1
            WHERE id = #{id} AND version = #{version} AND deleted = FALSE
            """)
    int softDelete(@Param("id") long id,
                   @Param("version") int version,
                   @Param("deletedAt") LocalDateTime deletedAt);

    @Update("""
            UPDATE leave_application SET
              applicant_name = #{entity.applicantName},
              department_id = #{entity.departmentId},
              department_name = #{entity.departmentName},
              leader_id = #{entity.leaderId},
              leader_name = #{entity.leaderName},
              leave_type = #{entity.leaveType},
              start_time = #{entity.startTime},
              end_time = #{entity.endTime},
              leave_duration_hours = #{entity.leaveDurationHours},
              urgency = #{entity.urgency},
              reason = #{entity.reason},
              handover_user_id = #{entity.handoverUserId},
              handover_user_name = #{entity.handoverUserName},
              contact_phone = #{entity.contactPhone},
              status = #{entity.status},
              submission_round = #{entity.submissionRound},
              current_approver_id = #{entity.currentApproverId},
              current_approver_name = #{entity.currentApproverName},
              submitted_at = #{entity.submittedAt},
              updated_at = #{entity.updatedAt},
              version = version + 1
            WHERE id = #{entity.id} AND version = #{entity.version} AND deleted = FALSE
            """)
    int updateAll(@Param("entity") LeaveApplicationEntity entity);

    @Select("""
            <script>
            SELECT a.* FROM leave_application a
            WHERE a.deleted = FALSE
            <choose>
              <when test="mine">
                AND a.applicant_id = #{actorId}
              </when>
              <otherwise>
                AND (
                  a.applicant_id = #{actorId}
                  OR EXISTS (SELECT 1 FROM approval_task t
                             WHERE t.application_id = a.id AND t.assignee_id = #{actorId})
                  <if test="allowScoped">
                    OR (#{allScoped} = TRUE
                      <if test="departmentIds != null and departmentIds.size() > 0">
                        OR a.department_id IN
                        <foreach collection="departmentIds" item="departmentId" open="(" separator="," close=")">
                          #{departmentId}
                        </foreach>
                      </if>
                    )
                  </if>
                )
              </otherwise>
            </choose>
            <if test="applicationNo != null and applicationNo != ''">
              AND a.application_no LIKE CONCAT('%', #{applicationNo}, '%')
            </if>
            <if test="applicantName != null and applicantName != ''">
              AND a.applicant_name LIKE CONCAT('%', #{applicantName}, '%')
            </if>
            <if test="departmentId != null">AND a.department_id = #{departmentId}</if>
            <if test="leaveType != null">AND a.leave_type = #{leaveType}</if>
            <if test="status != null">AND a.status = #{status}</if>
            <if test="submittedFrom != null">AND a.submitted_at &gt;= #{submittedFrom}</if>
            <if test="submittedTo != null">AND a.submitted_at &lt;= #{submittedTo}</if>
            ORDER BY ${orderBy}
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<LeaveApplicationEntity> findPage(@Param("actorId") long actorId,
                                          @Param("mine") boolean mine,
                                          @Param("allowScoped") boolean allowScoped,
                                          @Param("allScoped") boolean allScoped,
                                          @Param("departmentIds") List<Long> departmentIds,
                                          @Param("applicationNo") String applicationNo,
                                          @Param("applicantName") String applicantName,
                                          @Param("departmentId") Long departmentId,
                                          @Param("leaveType") LeaveType leaveType,
                                          @Param("status") LeaveStatus status,
                                          @Param("submittedFrom") LocalDateTime submittedFrom,
                                          @Param("submittedTo") LocalDateTime submittedTo,
                                          @Param("orderBy") String orderBy,
                                          @Param("offset") long offset,
                                          @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM leave_application a
            WHERE a.deleted = FALSE
            <choose>
              <when test="mine">
                AND a.applicant_id = #{actorId}
              </when>
              <otherwise>
                AND (
                  a.applicant_id = #{actorId}
                  OR EXISTS (SELECT 1 FROM approval_task t
                             WHERE t.application_id = a.id AND t.assignee_id = #{actorId})
                  <if test="allowScoped">
                    OR (#{allScoped} = TRUE
                      <if test="departmentIds != null and departmentIds.size() > 0">
                        OR a.department_id IN
                        <foreach collection="departmentIds" item="departmentId" open="(" separator="," close=")">
                          #{departmentId}
                        </foreach>
                      </if>
                    )
                  </if>
                )
              </otherwise>
            </choose>
            <if test="applicationNo != null and applicationNo != ''">
              AND a.application_no LIKE CONCAT('%', #{applicationNo}, '%')
            </if>
            <if test="applicantName != null and applicantName != ''">
              AND a.applicant_name LIKE CONCAT('%', #{applicantName}, '%')
            </if>
            <if test="departmentId != null">AND a.department_id = #{departmentId}</if>
            <if test="leaveType != null">AND a.leave_type = #{leaveType}</if>
            <if test="status != null">AND a.status = #{status}</if>
            <if test="submittedFrom != null">AND a.submitted_at &gt;= #{submittedFrom}</if>
            <if test="submittedTo != null">AND a.submitted_at &lt;= #{submittedTo}</if>
            </script>
            """)
    long countPage(@Param("actorId") long actorId,
                   @Param("mine") boolean mine,
                   @Param("allowScoped") boolean allowScoped,
                   @Param("allScoped") boolean allScoped,
                   @Param("departmentIds") List<Long> departmentIds,
                   @Param("applicationNo") String applicationNo,
                   @Param("applicantName") String applicantName,
                   @Param("departmentId") Long departmentId,
                   @Param("leaveType") LeaveType leaveType,
                   @Param("status") LeaveStatus status,
                   @Param("submittedFrom") LocalDateTime submittedFrom,
                   @Param("submittedTo") LocalDateTime submittedTo);
}
