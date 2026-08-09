package com.redblack.office.application;

import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.domain.FileEntity;
import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.infrastructure.persistence.FileMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class InternalFileApplicationService {
    private final FileMapper files;
    private final Clock clock;

    public InternalFileApplicationService(FileMapper files, Clock clock) {
        this.files = files;
        this.clock = clock;
    }

    @Transactional
    public void reserve(String reservationId, long ownerId, List<Long> fileIds,
                        String businessType, Long businessId) {
        if (reservationId == null || reservationId.isBlank() || reservationId.length() > 100
                || ownerId <= 0 || !"LEAVE_APPLICATION".equals(businessType)
                || fileIds == null || fileIds.stream().anyMatch(id -> id == null || id <= 0)
                || fileIds.size() > 10 || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw invalid();
        }
        LocalDateTime now = now();
        for (Long fileId : fileIds) {
            FileEntity file = files.selectById(fileId);
            if (file == null || !file.getOwnerId().equals(ownerId)) throw invalid();
            if ("BOUND".equals(file.getStatus())) {
                if (businessId != null && businessType.equals(file.getBoundBusinessType())
                        && businessId.equals(file.getBoundBusinessId())) continue;
                throw invalid();
            }
            if (files.reserve(fileId, ownerId, reservationId, now.plus(Duration.ofMinutes(10)), now) != 1) {
                throw invalid();
            }
        }
    }

    @Transactional
    public void confirm(String reservationId, long ownerId, List<Long> fileIds,
                        String businessType, long businessId) {
        Set<Long> desired = Set.copyOf(fileIds);
        LocalDateTime now = now();
        for (FileEntity existing : files.findBound(businessType, businessId)) {
            if (!desired.contains(existing.getId())) {
                existing.setStatus("TEMPORARY");
                existing.setBoundBusinessType(null);
                existing.setBoundBusinessId(null);
                existing.setUpdatedAt(now);
                files.updateById(existing);
            }
        }
        for (Long id : desired) {
            FileEntity file = files.selectById(id);
            if (file == null || !file.getOwnerId().equals(ownerId)) throw invalid();
            if ("BOUND".equals(file.getStatus()) && businessType.equals(file.getBoundBusinessType())
                    && file.getBoundBusinessId() != null && businessId == file.getBoundBusinessId()) continue;
            if (files.confirmReservation(id, ownerId, reservationId, businessType, businessId, now) != 1) {
                throw invalid();
            }
        }
    }

    public List<FileSummary> metadata(List<Long> fileIds) {
        if (fileIds == null || fileIds.size() > 10) throw invalid();
        return fileIds.stream().map(id -> {
            FileEntity file = files.selectById(id);
            if (file == null) throw invalid();
            FileStatus status = "BOUND".equals(file.getStatus()) ? FileStatus.BOUND : FileStatus.TEMPORARY;
            return new FileSummary(file.getId().toString(), file.getOriginalName(), file.getContentType(),
                    file.getSizeBytes(), status, file.getCreatedAt().atOffset(ZoneOffset.UTC));
        }).toList();
    }

    private BusinessException invalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_BINDING_INVALID",
                "附件不存在、已绑定或不属于指定用户");
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
