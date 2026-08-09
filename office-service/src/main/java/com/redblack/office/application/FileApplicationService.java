package com.redblack.office.application;

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
import org.springframework.transaction.annotation.Transactional;
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
    private static final Set<String> EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx");
    private static final Map<String, Set<String>> TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "jpg", Set.of("image/jpeg"), "jpeg", Set.of("image/jpeg"), "png", Set.of("image/png"),
            "doc", Set.of("application/msword"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "xls", Set.of("application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    private final FileMapper files;
    private final NoticeMapper notices;
    private final NoticeDepartmentMapper noticeDepartments;
    private final OfficeAuthorizationService authorization;
    private final OssStorage storage;
    private final OssProperties properties;
    private final ApprovalClient approval;
    private final Clock clock;

    public FileApplicationService(FileMapper files, NoticeMapper notices, NoticeDepartmentMapper noticeDepartments,
                                  OfficeAuthorizationService authorization, OssStorage storage,
                                  OssProperties properties, ApprovalClient approval, Clock clock) {
        this.files = files;
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
                throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "不支持的文件类型");
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

    @Transactional
    public FileSummary upload(Jwt jwt, PreparedUpload upload, String requestId) {
        long ownerId = authorization.requireActive(jwt, requestId).id();
        LocalDateTime now = now();
        String prefix = properties.getPrefix() == null ? "redblack/v1/" : properties.getPrefix();
        if (!prefix.endsWith("/")) prefix += "/";
        String objectKey = prefix + LocalDate.now(clock) + "/" + ownerId + "/" + UUID.randomUUID() + "." + upload.extension();
        storage.put(objectKey, upload.content(), upload.fingerprint().contentType());
        FileEntity entity = new FileEntity();
        entity.setOwnerId(ownerId);
        entity.setOriginalName(upload.fingerprint().fileName());
        entity.setContentType(upload.fingerprint().contentType());
        entity.setExtension(upload.extension());
        entity.setSizeBytes(upload.fingerprint().size());
        entity.setSha256(upload.fingerprint().sha256());
        entity.setObjectKey(objectKey);
        entity.setStatus("TEMPORARY");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try { files.insert(entity); }
        catch (RuntimeException exception) { storage.delete(objectKey); throw exception; }
        return summary(entity);
    }

    public FileSummary metadata(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = require(fileId);
        if (!canAccess(entity, actor, requestId)) throw BusinessException.notFound("文件不存在");
        return summary(entity);
    }

    public Download download(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = require(fileId);
        if (!canAccess(entity, actor, requestId)) throw BusinessException.notFound("文件不存在");
        return new Download(entity.getOriginalName(), entity.getContentType(), storage.get(entity.getObjectKey()));
    }

    @Transactional
    public void deleteTemporary(Jwt jwt, long fileId, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        FileEntity entity = require(fileId);
        if (!entity.getOwnerId().equals(actor.id())) throw BusinessException.notFound("文件不存在");
        if (!"TEMPORARY".equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "FILE_ALREADY_BOUND", "已绑定文件不能删除");
        }
        storage.delete(entity.getObjectKey());
        if (files.deleteById(fileId) != 1) throw BusinessException.notFound("文件不存在");
    }

    @Scheduled(fixedDelayString = "${redblack.file.cleanup-delay:3600000}")
    public void cleanExpiredTemporary() {
        LocalDateTime current = now();
        files.releaseExpiredReservations(current);
        LocalDateTime cutoff = current.minus(Duration.ofHours(24));
        for (FileEntity entity : files.findExpiredTemporary(cutoff, 100)) {
            try {
                storage.delete(entity.getObjectKey());
                files.deleteById(entity.getId());
            } catch (BusinessException ignored) {
                return;
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
