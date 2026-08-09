package com.redblack.office.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OfficeMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("redblack_office").withUsername("office").withPassword("office-test");
    @Test
    void emptyMysql84MigratesRepeatably() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN
                     ('office_notice','office_notice_department','office_notice_read','office_notification','office_file',
                      'office_workbench_application','office_workbench_task','office_inbox_event',
                      'office_outbox_event','office_idempotency_record')
                     """)) {
            assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(10);
        }
    }
}
