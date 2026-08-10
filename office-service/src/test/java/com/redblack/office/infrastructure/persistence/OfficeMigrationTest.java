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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
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
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND
                       ((table_name='office_file' AND column_name IN
                         ('storage_provider','bucket','etag','storage_status','cleanup_attempts',
                          'cleanup_next_attempt_at','cleanup_last_error'))
                        OR (table_name='office_idempotency_record' AND column_name='file_id'))
                     """)) {
            assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(8);
        }
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
                       AND constraint_name IN ('ck_office_file_storage_provider','ck_office_file_storage_status',
                                               'fk_office_idempotency_file')
                     """)) {
            assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(3);
        }
    }
}
