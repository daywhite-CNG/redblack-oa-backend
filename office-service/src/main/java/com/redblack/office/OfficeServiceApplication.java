package com.redblack.office;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.redblack.office.infrastructure.persistence")
public class OfficeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OfficeServiceApplication.class, args);
    }
}

