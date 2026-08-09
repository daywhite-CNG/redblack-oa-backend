package com.redblack.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface ApplicationNoMapper {
    @Insert("INSERT IGNORE INTO leave_application_no_sequence(sequence_date, next_value) VALUES(#{date}, 0)")
    int initialize(@Param("date") LocalDate date);

    @Update("UPDATE leave_application_no_sequence SET next_value = LAST_INSERT_ID(next_value + 1) WHERE sequence_date = #{date}")
    int increment(@Param("date") LocalDate date);

    @Select("SELECT LAST_INSERT_ID()")
    long currentConnectionValue();
}
