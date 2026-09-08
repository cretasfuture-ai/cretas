package com.cretas.aims.audit;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.service.datacenter.OperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestAuditInterceptorContractTest {

    private OperationLogService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OperationLogService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuditController())
                .addInterceptors(new RequestAuditInterceptor(service))
                .setControllerAdvice(new RequestAuditResponseAdvice())
                .build();
    }

    @Test
    void actualControllerMutationProducesOneSuccessfulOperationLog() throws Exception {
        mvc.perform(post("/api/mobile/F006/materials/42")
                        .requestAttr("factoryId", "F006")
                        .requestAttr("userId", 9L)
                        .requestAttr("username", "operator"))
                .andExpect(status().isOk());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(service, times(1)).record(captor.capture());
        OperationLog log = captor.getValue();
        assertThat(log.getFactoryId()).isEqualTo("F006");
        assertThat(log.getUserId()).isEqualTo(9L);
        assertThat(log.getSuccess()).isTrue();
        assertThat(log.getEntityType()).isEqualTo("materials");
        assertThat(log.getEntityId()).isEqualTo("42");
    }

    @Test
    void apiResponseFailureProducesFailureOperationLog() throws Exception {
        mvc.perform(post("/api/mobile/F006/materials/fail")
                        .requestAttr("factoryId", "F006")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(service).record(captor.capture());
        assertThat(captor.getValue().getSuccess()).isFalse();
        assertThat(captor.getValue().getErrorMessage()).contains("failed");
    }

    @Test
    void responseEntityFourHundredStatusProducesFailureOperationLog() throws Exception {
        mvc.perform(post("/api/mobile/F006/materials/http-fail")
                        .requestAttr("factoryId", "F006")
                        .requestAttr("userId", 9L))
                .andExpect(status().isBadRequest());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(service).record(captor.capture());
        assertThat(captor.getValue().getSuccess()).isFalse();
    }

    @Test
    void forgedFactoryIsRejectedWhileQueryPostIsAuditedAsOtherRequest() throws Exception {
        mvc.perform(post("/api/mobile/F006/materials/42")
                        .requestAttr("factoryId", "F999")
                        .requestAttr("userId", 9L));
        mvc.perform(post("/api/mobile/F006/materials/search")
                        .requestAttr("factoryId", "F006")
                        .requestAttr("userId", 9L));
        verify(service, times(1)).record(any());
        verify(service, never()).record(argThat(log -> "CREATE".equals(log.getAction())));
    }

    @Test
    void explicitLoggableMarkerPreventsDuplicateOperationAudit() throws Exception {
        mvc.perform(post("/api/mobile/F006/materials/42")
                        .requestAttr("factoryId", "F006")
                        .requestAttr("userId", 9L)
                        .requestAttr(RequestAuditSupport.EXPLICIT_AUDIT_ATTRIBUTE, Boolean.TRUE));
        verify(service, never()).record(any());
    }

    @Test
    void authorizedPlatformActorUsesTargetFactoryAndRejectionDoesNotBreakResponse() throws Exception {
        doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(service).record(any());
        mvc.perform(post("/api/mobile/F006/materials/42")
                        .requestAttr("factoryId", "PLATFORM")
                        .requestAttr("role", "platform_admin")
                        .requestAttr("userId", 8L))
                .andExpect(status().isOk());
        verify(service).record(argThat(log -> "F006".equals(log.getFactoryId()) && log.getUserId() == 8L));
    }

    @RestController
    static class AuditController {
        @PostMapping("/api/mobile/{factoryId}/materials/{id}")
        public ApiResponse<String> create(@PathVariable String factoryId, @PathVariable String id) {
            return ApiResponse.success("created");
        }

        @PostMapping("/api/mobile/{factoryId}/materials/fail")
        public ApiResponse<String> fail() { return ApiResponse.error("failed"); }

        @PostMapping("/api/mobile/{factoryId}/materials/search")
        public ApiResponse<List<String>> search() { return ApiResponse.success(List.of("one")); }

        @PostMapping("/api/mobile/{factoryId}/materials/http-fail")
        public ResponseEntity<ApiResponse<String>> httpFail() {
            return ResponseEntity.badRequest().body(ApiResponse.error("bad request"));
        }
    }
}
