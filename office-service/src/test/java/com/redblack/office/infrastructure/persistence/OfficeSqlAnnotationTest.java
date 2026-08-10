package com.redblack.office.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfficeSqlAnnotationTest {
    @Test
    void staticAnnotationSqlDoesNotContainXmlEntities() {
        List<Class<?>> mappers = List.of(OutboxEventMapper.class, WorkbenchTaskMapper.class,
                WorkbenchApplicationMapper.class, IdempotencyMapper.class, FileMapper.class,
                NotificationMapper.class);

        for (Class<?> mapper : mappers) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = sql(method);
                if (sql != null && !sql.stripLeading().startsWith("<script>")) {
                    assertThat(sql).as(mapper.getSimpleName() + "." + method.getName())
                            .doesNotContain("&lt;", "&gt;");
                }
            }
        }
    }

    @Test
    void fileStateTransitionsAreAtomicAndCleanupUsesLockedBatches() throws Exception {
        assertThat(sql(FileMapper.class.getDeclaredMethod("reserve", long.class, long.class,
                String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class)))
                .contains("storage_status='AVAILABLE'");
        assertThat(sql(FileMapper.class.getDeclaredMethod("confirmReservation", long.class, long.class,
                String.class, String.class, long.class, java.time.LocalDateTime.class)))
                .contains("storage_status='AVAILABLE'");
        assertThat(sql(FileMapper.class.getDeclaredMethod("findCleanupCandidatesForUpdate",
                java.time.LocalDateTime.class, java.time.LocalDateTime.class, int.class)))
                .contains("FOR UPDATE SKIP LOCKED", "cleanup_next_attempt_at <= #{now}");
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) return String.join(" ", select.value());
        Update update = method.getAnnotation(Update.class);
        if (update != null) return String.join(" ", update.value());
        Delete delete = method.getAnnotation(Delete.class);
        return delete == null ? null : String.join(" ", delete.value());
    }
}
