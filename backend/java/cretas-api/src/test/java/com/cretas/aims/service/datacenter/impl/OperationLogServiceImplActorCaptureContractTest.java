package com.cretas.aims.service.datacenter.impl;

import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.service.datacenter.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

class OperationLogServiceImplActorCaptureContractTest {

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void recordDiffCapturesActorBeforeDelegatingToAsyncRecord() {
        OperationLogRepository repository = mock(OperationLogRepository.class);
        OperationLogService asyncProxy = mock(OperationLogService.class);
        OperationLogServiceImpl service = new OperationLogServiceImpl(repository, asyncProxy);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/mobile/F006/materials/42");
        request.setAttribute("userId", 73L);
        request.setAttribute("username", "operator");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));

        service.recordDiff("F006", "MATERIAL", "Material", "42",
                Map.of("name", "old"), Map.of("name", "new"), "changed");

        var captor = forClass(OperationLog.class);
        verify(asyncProxy).record(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(73L);
        assertThat(captor.getValue().getUsername()).isEqualTo("operator");
        verifyNoInteractions(repository);
    }
}
