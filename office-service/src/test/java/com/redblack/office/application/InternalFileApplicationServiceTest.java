package com.redblack.office.application;

import com.redblack.office.domain.FileEntity;
import com.redblack.office.infrastructure.persistence.FileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalFileApplicationServiceTest {
    private FileMapper files;
    private InternalFileApplicationService service;

    @BeforeEach
    void setUp() {
        files = mock(FileMapper.class);
        service = new InternalFileApplicationService(files,
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsReservationWhenAtomicStateCheckLoses() {
        when(files.selectById(1L)).thenReturn(temporaryFile());
        when(files.reserve(eq(1L), eq(10003L), eq("reservation-a"), any(), any())).thenReturn(0);

        assertBindingInvalid(() -> service.reserve("reservation-a", 10003L, List.of(1L),
                "LEAVE_APPLICATION", null));
    }

    @Test
    void rejectsConfirmationWhenFileWasReservedByAnotherRequest() {
        FileEntity file = temporaryFile();
        file.setStatus("RESERVED");
        file.setReservedBy("reservation-b");
        when(files.findBound("LEAVE_APPLICATION", 90001L)).thenReturn(List.of());
        when(files.selectById(1L)).thenReturn(file);
        when(files.confirmReservation(1L, 10003L, "reservation-a", "LEAVE_APPLICATION", 90001L,
                java.time.LocalDateTime.of(2026, 8, 9, 0, 0))).thenReturn(0);

        assertBindingInvalid(() -> service.confirm("reservation-a", 10003L, List.of(1L),
                "LEAVE_APPLICATION", 90001L));
    }

    @Test
    void rejectsPendingFileBeforeBinding() {
        FileEntity file = temporaryFile();
        file.setStorageStatus("PENDING");
        when(files.selectById(1L)).thenReturn(file);

        assertBindingInvalid(() -> service.reserve("reservation-a", 10003L, List.of(1L),
                "LEAVE_APPLICATION", null));
    }

    private FileEntity temporaryFile() {
        FileEntity file = new FileEntity();
        file.setId(1L);
        file.setOwnerId(10003L);
        file.setStatus("TEMPORARY");
        file.setStorageStatus("AVAILABLE");
        return file;
    }

    private void assertBindingInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("FILE_BINDING_INVALID"));
    }
}
