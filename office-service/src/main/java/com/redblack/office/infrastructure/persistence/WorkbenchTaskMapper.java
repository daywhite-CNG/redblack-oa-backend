package com.redblack.office.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.office.domain.WorkbenchTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkbenchTaskMapper extends BaseMapper<WorkbenchTaskEntity> {
    @Insert("""
            INSERT INTO office_workbench_task(task_id,application_id,assignee_id,title,urgency,status,created_at,updated_at)
            VALUES(#{taskId},#{applicationId},#{assigneeId},#{title},#{urgency},#{status},#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE assignee_id=VALUES(assignee_id),title=VALUES(title),urgency=VALUES(urgency),
              status=VALUES(status),updated_at=VALUES(updated_at)
            """)
    int upsert(WorkbenchTaskEntity entity);
    @Update("UPDATE office_workbench_task SET status=#{status},updated_at=#{updatedAt} WHERE task_id=#{taskId}")
    int updateStatus(@Param("taskId") long taskId, @Param("status") String status,
                     @Param("updatedAt") LocalDateTime updatedAt);
    @Select("SELECT COUNT(*) FROM office_workbench_task WHERE assignee_id=#{userId} AND status='PENDING'")
    long countPending(@Param("userId") long userId);
    @Select("SELECT * FROM office_workbench_task WHERE assignee_id=#{userId} AND status='PENDING' ORDER BY created_at LIMIT #{limit}")
    List<WorkbenchTaskEntity> pending(@Param("userId") long userId, @Param("limit") int limit);
    @Select("""
            SELECT DATE(updated_at) AS day,COUNT(*) AS value FROM office_workbench_task
            WHERE assignee_id=#{userId} AND status IN ('APPROVED','REJECTED') AND updated_at >= #{from}
            GROUP BY DATE(updated_at) ORDER BY day
            """)
    List<TrendRow> trend(@Param("userId") long userId, @Param("from") LocalDateTime from);
    record TrendRow(LocalDate day, BigDecimal value) { }
}
