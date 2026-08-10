package com.redblack.office.application;

import com.redblack.office.domain.FileEntity;
import com.redblack.office.infrastructure.approval.ApprovalClient;
import com.redblack.office.infrastructure.oss.OssProperties;
import com.redblack.office.infrastructure.oss.OssStorage;
import com.redblack.office.infrastructure.persistence.FileMapper;
import com.redblack.office.infrastructure.persistence.NoticeDepartmentMapper;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileApplicationServiceTest {
    @Test
    void storesOssLocationAndEtagForUploadedFile() {
        FileMapper files = mock(FileMapper.class);
        OfficeAuthorizationService authorization = mock(OfficeAuthorizationService.class);
        OssStorage storage = mock(OssStorage.class);
        OssProperties properties = new OssProperties();
        properties.setBucket("redblack-private");
        properties.setPrefix("redblack/v1/");
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
        FileApplicationService service = new FileApplicationService(files, mock(NoticeMapper.class),
                mock(NoticeDepartmentMapper.class), authorization, storage, properties,
                mock(ApprovalClient.class), clock);
        Jwt jwt = mock(Jwt.class);
        when(authorization.requireActive(jwt, "request-1"))
                .thenReturn(new OfficeAuthorizationService.Actor(10003L, null, null));
        when(storage.put(anyString(), eq(new byte[]{1, 2, 3, 4}), eq("application/pdf")))
                .thenReturn("etag-123");
        when(files.insert(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            entity.setId(90001L);
            return 1;
        });

        var result = service.upload(jwt, new FileApplicationService.PreparedUpload(
                new FileApplicationService.UploadFingerprint("evidence.pdf", "application/pdf", 4, "a".repeat(64)),
                "pdf", new byte[]{1, 2, 3, 4}), "request-1");

        assertThat(result.id()).isEqualTo("90001");
        ArgumentCaptor<FileEntity> entityCaptor = ArgumentCaptor.forClass(FileEntity.class);
        verify(files).insert(entityCaptor.capture());
        FileEntity stored = entityCaptor.getValue();
        assertThat(stored.getStorageProvider()).isEqualTo("ALIYUN_OSS");
        assertThat(stored.getBucket()).isEqualTo("redblack-private");
        assertThat(stored.getEtag()).isEqualTo("etag-123");
        assertThat(stored.getStorageStatus()).isEqualTo("AVAILABLE");
        assertThat(stored.getCleanupAttempts()).isZero();
    }
}
