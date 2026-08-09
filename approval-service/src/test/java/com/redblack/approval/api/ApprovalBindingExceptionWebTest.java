package com.redblack.approval.api;

import com.redblack.approval.application.IdempotencyService;
import com.redblack.approval.application.LeaveApplicationService;
import com.redblack.common.trace.TraceHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalBindingExceptionWebTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LeaveApplicationController controller = new LeaveApplicationController(
                mock(LeaveApplicationService.class), mock(IdempotencyService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApprovalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void missingIdempotencyKeyReturnsValidationFailed() throws Exception {
        assertValidation(mvc.perform(post("/api/v1/leave-applications")
                .header(TraceHeaders.REQUEST_ID, "req-missing-header")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")), "req-missing-header");
    }

    @Test
    void nonNumericPathIdReturnsValidationFailed() throws Exception {
        assertValidation(mvc.perform(get("/api/v1/leave-applications/not-a-number")
                .header(TraceHeaders.REQUEST_ID, "req-invalid-path")), "req-invalid-path");
    }

    @Test
    void missingDeleteVersionReturnsValidationFailed() throws Exception {
        assertValidation(mvc.perform(delete("/api/v1/leave-applications/10001")
                .header(TraceHeaders.REQUEST_ID, "req-missing-version")), "req-missing-version");
    }

    private void assertValidation(ResultActions result, String requestId) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(header().string(TraceHeaders.REQUEST_ID, requestId))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
