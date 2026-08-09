package com.redblack.identity.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class IdentityMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("redblack_identity")
            .withUsername("redblack")
            .withPassword("redblack-test");

    @Test
    void migratesAnEmptyDatabaseAndKeepsBaselineIdempotent() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            try (var users = statement.executeQuery("SELECT username, password_hash FROM sys_user ORDER BY id")) {
                int count = 0;
                while (users.next()) {
                    count++;
                    assertThat(new BCryptPasswordEncoder(12)
                            .matches("123456", users.getString("password_hash"))).isTrue();
                }
                assertThat(count).isEqualTo(3);
            }
            try (var roles = statement.executeQuery("SELECT COUNT(*) FROM sys_role")) {
                assertThat(roles.next()).isTrue();
                assertThat(roles.getInt(1)).isEqualTo(3);
            }
        }
    }
}
