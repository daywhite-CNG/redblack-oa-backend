package com.redblack.office.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.office.application.InternalFileApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.redblack.office.api.OfficeApiModels.FileSummary;

@RestController
@RequestMapping("/internal/v1/files")
public class InternalFileController {
    private final InternalFileApplicationService service;
    public InternalFileController(InternalFileApplicationService service) { this.service = service; }

    @PostMapping("/reservations")
    ApiResponse<Void> reserve(@RequestBody ReservationRequest body, HttpServletRequest request) {
        service.reserve(body.reservationId(), Long.parseLong(body.ownerId()),
                body.fileIds().stream().map(Long::parseLong).toList(), body.businessType(),
                body.businessId() == null ? null : Long.parseLong(body.businessId()));
        return ApiResponse.success("预留成功", null, RequestIds.get(request));
    }

    @PostMapping("/metadata")
    ApiResponse<List<FileSummary>> metadata(@RequestBody MetadataRequest body, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.metadata(body.fileIds().stream().map(Long::parseLong).toList()),
                RequestIds.get(request));
    }

    public record ReservationRequest(String reservationId, String ownerId, List<String> fileIds,
                                     String businessType, String businessId) { }
    public record MetadataRequest(List<String> fileIds) { }
}
