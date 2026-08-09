package com.redblack.approval.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("redblack_approval")
            .withUsername("redblack_approval")
            .withPassword("approval-test");

    @Test
    void migratesAnEmptyMysql84DatabaseAndIsRepeatable() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword()); var statement = connection.createStatement()) {
            try (var tables = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name IN (
                      'leave_application', 'leave_application_attachment', 'approval_task',
                      'approval_record', 'approval_idempotency_record', 'outbox_event',
                      'leave_application_no_sequence')
                    """)) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(7);
            }
        }
    }
}
