package com.redblack.office.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.domain.FileEntity;
import com.redblack.office.domain.NoticeEntity;
import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.infrastructure.oss.OssProperties;
import com.redblack.office.infrastructure.oss.OssStorage;
import com.redblack.office.infrastructure.approval.ApprovalClient;
import com.redblack.office.infrastructure.persistence.FileMapper;
import com.redblack.office.infrastructure.persistence.NoticeDepartmentMapper;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FileApplicationService {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Duration TEMPORARY_TTL = Duration.ofHours(24);
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(5);
    private static final Set<String> EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx");
    private static final Map<String, Set<String>> TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "jpg", Set.of("image/jpeg"), "jpeg", Set.of("image/jpeg"), "png", Set.of("image/png"),
            "doc", Set.of("application/msword"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "xls", Set.of("application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    private final FileMapper files;
    private final FileStorageTransactionService transactions;
    private final NoticeMapper notices;
    private final NoticeDepartmentMapper noticeDepartments;
    private final OfficeAuthorizationService authorization;
    private final OssStorage storage;
    private final OssProperties properties;
    private final ApprovalClient approval;
    private final Clock clock;

    public FileApplicationService(FileMapper files, FileStorageTransactionService transactions,
                                  NoticeMapper notices, NoticeDepartmentMapper noticeDepartments,
                                  OfficeAuthorizationService authorization, OssStorage storage,
                                  OssProperties properties, ApprovalClient approval, Clock clock) {
        this.files = files;
        this.transactions = transactions;
        this.notices = notices;
        this.noticeDepartments = noticeDepartments;
        this.authorization = authorization;
        this.storage = storage;
        this.properties = properties;
        this.approval = approval;
        this.clock = clock;
    }

    public PreparedUpload prepare(MultipartFile file) {
        try {
            String name = safeName(file.getOriginalFilename());
            if (file.isEmpty()) throw invalid("文件不能为空");
            if (file.getSize() > MAX_SIZE) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件不能超过 20MB");
            }
            String extension = extension(name);
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
            if (!EXTENSIONS.contains(extension) || !TYPES.get(extension).contains(contentType)) {
                throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_NOT_ALLOWED", "不支持的文件类型");
            }
            byte[] content = file.getBytes();
            if (!signature(extension, content)) {
                throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_SIGNATURE_MISMATCH", "文件内容与扩展名不匹配");
            }
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            return new PreparedUpload(new UploadFingerprint(name, contentType, content.length, sha), extension, content);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法读取上传文件");
        }
    }

    public void authorizeUpload(Jwt jwt, String requestId) { authorization.requireActive(jwt, requestId); }

    public long createPending(Jwt jwt, PreparedUpload upload, String requestId) {
        long ownerId = authorization.requireActive(jwt, requestId).id();
        LocalDateTime now = now();
        String prefix = properties.getPrefix() == null ? "redblack/v1/" : properties.getPrefix();
        if (!prefix.endsWith("/")) prefix += "/";
        String objectKey = prefix + LocalDate.now(clock) + "/" + ownerId + "/" + UUID.randomUUID() + "." + upload.extension();
        FileEntity entity = new FileEntity();
        entity.setId(IdWorker.getId());
        entity.setOwnerId(ownerId);
        entity.setOriginalName(upload.fingerprint().fileName());
        entity.setContentType(upload.fingerprint().contentType());
        entity.setExtension(upload.extension());
        entity.setSizeBytes(upload.fingerprint().size());
        entity.setSha256(upload.fingerprint().sha256());
        entity.setObjectKey(objectKey);
        entity.setStorageProvider("ALIYUN_OSS");
        entity.setBucket(properties.getBucket());
        entity.setStorageStatus("PENDING");
        entity.setCleanupAttempts(0);
        entity.setCleanupNextAttemptAt(now.plus(TEMPORARY_TTL));
        entity.setStatus("TEMPORARY");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return transactions.createPending(entity);
    }

    public FileSummary finishUpload(Jwt jwt, PreparedUpload upload, long fileId, String requestId) {
        long ownerId = authorization.requireActive(jwt, requestId).id();
        FileEntity entity = require(fileId);
        if (!entity.getOwnerId().equals(ownerId) || !matches(entity, upload)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "同一幂等键不能用于不同文件");
        }
        if ("AVAILABLE".equals(entity.getStorageStatus())) return summary(entity);
        if (!"PENDING".equals(entity.getStorageStatus())) throw BusinessException.notFound("文件不存在");
        OssStorage.StoredMetadata remote = storage.metadata(entity.getBucket(), entity.getObjectKey());
        String etag;
        if (remote.exists()) {
            if (remote.contentLength() != entity.getSizeBytes()) throw integrityMismatch();
            OssStorage.StoredObject stored = storage.get(entity.getBucket(), entity.getObjectKey());
            verifyIntegrity(entity, stored);
            etag = stored.etag();
        } else {
            etag = storage.put(entity.getBucket(), entity.getObjectKey(), upload.content(), entity.getContentType());
        }
        transactions.markAvailable(fileId, etag, now());
        return summary(requireAvailable(fileId));
    }

    public FileSummary metadata(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = requireAvailable(fileId);
        if (!canAccess(entity, actor, requestId)) throw BusinessException.notFound("文件不存在");
        return summary(entity);
    }

    public Download download(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = requireAvailable(fileId);
        if (!canAccess(entity, actor, requestId)) throw BusinessException.notFound("文件不存在");
        OssStorage.StoredObject stored = storage.get(entity.getBucket(), entity.getObjectKey());
        verifyIntegrity(entity, stored);
        return new Download(entity.getOriginalName(), entity.getContentType(), stored.content());
    }

    public void deleteTemporary(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = requireAvailable(fileId);
        if (!entity.getOwnerId().equals(actor.id())) throw BusinessException.notFound("文件不存在");
        if (!"TEMPORARY".equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "FILE_ALREADY_BOUND", "已绑定文件不能删除");
        }
        LocalDateTime current = now();
        if (!transactions.markDeletePending(fileId, actor.id(), current)) {
            throw BusinessException.notFound("文件不存在");
        }
        entity = require(fileId);
        try {
            storage.delete(entity.getBucket(), entity.getObjectKey());
        } catch (BusinessException exception) {
            transactions.recordCleanupFailure(entity, current, exception.getMessage());
            throw exception;
        }
        if (!transactions.delete(fileId, "DELETE_PENDING")) throw BusinessException.notFound("文件不存在");
    }

    @Scheduled(fixedDelayString = "${redblack.file.cleanup-delay:3600000}")
    public void cleanExpiredTemporary() {
        LocalDateTime current = now();
        files.releaseExpiredReservations(current);
        LocalDateTime cutoff = current.minus(TEMPORARY_TTL);
        for (FileEntity entity : transactions.claimCleanupBatch(
                current, cutoff, current.plus(CLEANUP_LEASE), 100)) {
            try {
                storage.delete(entity.getBucket(), entity.getObjectKey());
                transactions.delete(entity.getId(), entity.getStorageStatus());
            } catch (RuntimeException exception) {
                transactions.recordCleanupFailure(entity, current, exception.getMessage());
            }
        }
    }

    private boolean canAccess(FileEntity entity, OfficeAuthorizationService.Actor actor, String requestId) {
        if ("TEMPORARY".equals(entity.getStatus())) return entity.getOwnerId().equals(actor.id());
        if ("NOTICE".equals(entity.getBoundBusinessType())) {
            NoticeEntity notice = notices.selectById(entity.getBoundBusinessId());
            if (notice == null) return false;
            boolean visible = "PUBLISHED".equals(notice.getStatus()) && ("ALL".equals(notice.getScopeType())
                    || noticeDepartments.includes(notice.getId(), actor.departmentId()) > 0);
            return visible || actor.has("notice:update") || actor.has("notice:publish") || actor.has("notice:withdraw");
        }
        if ("LEAVE_APPLICATION".equals(entity.getBoundBusinessType())) {
            return approval.canReadFile(entity.getBoundBusinessId(), actor.id(), requestId);
        }
        return entity.getOwnerId().equals(actor.id());
    }

    private FileEntity require(long id) {
        FileEntity entity = files.selectById(id);
        if (entity == null) throw BusinessException.notFound("文件不存在");
        return entity;
    }
    private FileEntity requireAvailable(long id) {
        FileEntity entity = require(id);
        if (!"AVAILABLE".equals(entity.getStorageStatus())) throw BusinessException.notFound("文件不存在");
        return entity;
    }
    private boolean matches(FileEntity entity, PreparedUpload upload) {
        UploadFingerprint fingerprint = upload.fingerprint();
        return entity.getOriginalName().equals(fingerprint.fileName())
                && entity.getContentType().equals(fingerprint.contentType())
                && entity.getSizeBytes() == fingerprint.size()
                && entity.getSha256().equals(fingerprint.sha256());
    }
    private void verifyIntegrity(FileEntity entity, OssStorage.StoredObject stored) {
        String actualSha = sha256(stored.content());
        boolean etagMatches = entity.getEtag() == null || stored.etag() == null
                || normalizeEtag(entity.getEtag()).equals(normalizeEtag(stored.etag()));
        if (stored.content().length != entity.getSizeBytes() || !entity.getSha256().equals(actualSha) || !etagMatches) {
            throw integrityMismatch();
        }
    }
    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private String normalizeEtag(String value) { return value.replace("\"", "").trim(); }
    private BusinessException integrityMismatch() {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_INTEGRITY_MISMATCH",
                "文件完整性校验失败");
    }
    private FileSummary summary(FileEntity entity) {
        FileStatus status = "BOUND".equals(entity.getStatus()) ? FileStatus.BOUND : FileStatus.TEMPORARY;
        return new FileSummary(String.valueOf(entity.getId()), entity.getOriginalName(), entity.getContentType(),
                entity.getSizeBytes(), status, entity.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
    private String safeName(String value) {
        if (value == null || value.isBlank()) throw invalid("文件名不能为空");
        String name = Paths.get(value).getFileName().toString();
        if (name.length() > 255 || name.indexOf('\0') >= 0) throw invalid("文件名无效");
        return name;
    }
    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 1 || index == name.length() - 1 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }
    private boolean signature(String extension, byte[] bytes) {
        if (bytes.length < 4) return false;
        return switch (extension) {
            case "pdf" -> bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
            case "jpg", "jpeg" -> (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "png" -> (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "doc", "xls" -> (bytes[0] & 0xff) == 0xd0 && (bytes[1] & 0xff) == 0xcf
                    && (bytes[2] & 0xff) == 0x11 && (bytes[3] & 0xff) == 0xe0;
            case "docx", "xlsx" -> bytes[0] == 'P' && bytes[1] == 'K';
            default -> false;
        };
    }
    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    public record UploadFingerprint(String fileName, String contentType, long size, String sha256) { }
    public record PreparedUpload(UploadFingerprint fingerprint, String extension, byte[] content) { }
    public record Download(String fileName, String contentType, byte[] content) { }
}
