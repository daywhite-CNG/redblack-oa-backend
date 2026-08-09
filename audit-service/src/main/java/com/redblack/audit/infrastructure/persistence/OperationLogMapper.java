package com.redblack.audit.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redblack.audit.domain.OperationLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
    @Select("""
            <script>SELECT * FROM audit_operation_log WHERE 1=1
            <if test="operatorName != null and operatorName != ''">AND operator_name LIKE CONCAT('%',#{operatorName},'%')</if>
            <if test="module != null and module != ''">AND module_name=#{module}</if>
            <if test="operationType != null and operationType != ''">AND operation_type=#{operationType}</if>
            <if test="result != null and result != ''">AND operation_result=#{result}</if>
            <if test="from != null">AND operated_at &gt;= #{from}</if>
            <if test="to != null">AND operated_at &lt;= #{to}</if>
            ORDER BY operated_at <choose><when test="ascending">ASC</when><otherwise>DESC</otherwise></choose>,id DESC
            LIMIT #{offset},#{pageSize}</script>
            """)
    List<OperationLogEntity> listLogs(@Param("operatorName") String operatorName, @Param("module") String module,
                                      @Param("operationType") String operationType, @Param("result") String result,
                                      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                      @Param("ascending") boolean ascending, @Param("offset") int offset,
                                      @Param("pageSize") int pageSize);
    @Select("""
            <script>SELECT COUNT(*) FROM audit_operation_log WHERE 1=1
            <if test="operatorName != null and operatorName != ''">AND operator_name LIKE CONCAT('%',#{operatorName},'%')</if>
            <if test="module != null and module != ''">AND module_name=#{module}</if>
            <if test="operationType != null and operationType != ''">AND operation_type=#{operationType}</if>
            <if test="result != null and result != ''">AND operation_result=#{result}</if>
            <if test="from != null">AND operated_at &gt;= #{from}</if>
            <if test="to != null">AND operated_at &lt;= #{to}</if></script>
            """)
    long countLogs(@Param("operatorName") String operatorName, @Param("module") String module,
                   @Param("operationType") String operationType, @Param("result") String result,
                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
