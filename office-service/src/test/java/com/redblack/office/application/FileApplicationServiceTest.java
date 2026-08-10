package com.redblack.office.application;

import com.redblack.office.domain.FileEntity;
import com.redblack.office.infrastructure.approval.ApprovalClient;
import com.redblack.office.infrastructure.oss.OssProperties;
import com.redblack.office.infrastructure.oss.OssStorage;
import com.redblack.office.infrastructure.persistence.FileMapper;
import com.redblack.office.infrastructure.persistence.NoticeDepartmentMapper;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileApplicationServiceTest {
    private static final byte[] CONTENT = {1, 2, 3, 4};
    private FileMapper files;
    private FileStorageTransactionService transactions;
    private OfficeAuthorizationService authorization;
    private OssStorage storage;
    private FileApplicationService service;
    private Jwt jwt;

    @BeforeEach
    void setUp() throws Exception {
        files = mock(FileMapper.class);
        transactions = mock(FileStorageTransactionService.class);
        authorization = mock(OfficeAuthorizationService.class);
        storage = mock(OssStorage.class);
        OssProperties properties = new OssProperties();
        properties.setBucket("redblack-private");
        properties.setPrefix("redblack/v1/");
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
        service = new FileApplicationService(files, transactions, mock(NoticeMapper.class),
                mock(NoticeDepartmentMapper.class), authorization, storage, properties,
                mock(ApprovalClient.class), clock);
        jwt = mock(Jwt.class);
        when(authorization.requireActive(jwt, "request-1"))
                .thenReturn(new OfficeAuthorizationService.Actor(10003L, null, null));
        when(transactions.createPending(any())).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            return entity.getId();
        });
    }

    @Test
    void createsPersistentPendingRecordBeforeOssUpload() {
        long fileId = service.createPending(jwt, upload(), "request-1");

        assertThat(fileId).isPositive();
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        verify(transactions).createPending(captor.capture());
        FileEntity pending = captor.getValue();
        assertThat(pending.getStorageProvider()).isEqualTo("ALIYUN_OSS");
        assertThat(pending.getBucket()).isEqualTo("redblack-private");
        assertThat(pending.getStorageStatus()).isEqualTo("PENDING");
        assertThat(pending.getCleanupAttempts()).isZero();
        assertThat(pending.getCleanupNextAttemptAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 0, 0));
        verify(storage, never()).put(any(), any(), any(), any());
    }

    @Test
    void uploadsPendingObjectThenMarksItAvailable() {
        FileEntity entity = pendingFile();
        when(files.selectById(90001L)).thenReturn(entity);
        when(storage.metadata("redblack-private", "redblack/v1/file.png"))
                .thenReturn(new OssStorage.StoredMetadata(false, null, 0));
        when(storage.put("redblack-private", "redblack/v1/file.png", CONTENT, "image/png"))
                .thenReturn("etag-123");
        doAnswer(invocation -> {
            entity.setStorageStatus("AVAILABLE");
            entity.setEtag(invocation.getArgument(1));
            return null;
        }).when(transactions).markAvailable(eq(90001L), eq("etag-123"), any());

        var result = service.finishUpload(jwt, upload(), 90001L, "request-1");

        assertThat(result.id()).isEqualTo("90001");
        verify(transactions).markAvailable(eq(90001L), eq("etag-123"), any());
    }

    @Test
    void rejectsDownloadedContentWhenSha256DoesNotMatch() {
        FileEntity entity = pendingFile();
        entity.setStorageStatus("AVAILABLE");
        entity.setEtag("etag-123");
        when(files.selectById(90001L)).thenReturn(entity);
        when(storage.get("redblack-private", "redblack/v1/file.png"))
                .thenReturn(new OssStorage.StoredObject(new byte[]{9, 9, 9, 9}, "etag-123"));

        assertThatThrownBy(() -> service.download(jwt, 90001L, "request-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_INTEGRITY_MISMATCH"));
    }

    @Test
    void rejectsDownloadedContentWhenEtagDoesNotMatch() {
        FileEntity entity = pendingFile();
        entity.setStorageStatus("AVAILABLE");
        entity.setEtag("etag-expected");
        when(files.selectById(90001L)).thenReturn(entity);
        when(storage.get("redblack-private", "redblack/v1/file.png"))
                .thenReturn(new OssStorage.StoredObject(CONTENT, "etag-actual"));

        assertThatThrownBy(() -> service.download(jwt, 90001L, "request-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_INTEGRITY_MISMATCH"));
    }

    @Test
    void keepsDeletePendingAndRecordsRetryWhenOssDeleteFails() {
        FileEntity entity = pendingFile();
        entity.setStorageStatus("AVAILABLE");
        FileEntity deleting = pendingFile();
        deleting.setStorageStatus("DELETE_PENDING");
        when(files.selectById(90001L)).thenReturn(entity, deleting);
        when(transactions.markDeletePending(eq(90001L), eq(10003L), any())).thenReturn(true);
        BusinessException unavailable = new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "FILE_STORAGE_UNAVAILABLE", "对象存储暂不可用");
        org.mockito.Mockito.doThrow(unavailable).when(storage)
                .delete("redblack-private", "redblack/v1/file.png");

        assertThatThrownBy(() -> service.deleteTemporary(jwt, 90001L, "request-1"))
                .isSameAs(unavailable);
        verify(transactions).recordCleanupFailure(eq(deleting), any(), eq("对象存储暂不可用"));
    }

    private FileApplicationService.PreparedUpload upload() {
        return new FileApplicationService.PreparedUpload(
                new FileApplicationService.UploadFingerprint("evidence.png", "image/png", CONTENT.length,
                        sha256(CONTENT)), "png", CONTENT);
    }

    private FileEntity pendingFile() {
        FileEntity entity = new FileEntity();
        entity.setId(90001L);
        entity.setOwnerId(10003L);
        entity.setOriginalName("evidence.png");
        entity.setContentType("image/png");
        entity.setExtension("png");
        entity.setSizeBytes((long) CONTENT.length);
        entity.setSha256(sha256(CONTENT));
        entity.setObjectKey("redblack/v1/file.png");
        entity.setBucket("redblack-private");
        entity.setStorageStatus("PENDING");
        entity.setCleanupAttempts(0);
        entity.setStatus("TEMPORARY");
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 10, 0, 0));
        return entity;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
