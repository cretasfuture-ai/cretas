package com.cretas.aims.service.config;

import com.cretas.aims.entity.config.SchedulerRunResolution;
import com.cretas.aims.entity.config.SchedulerRunResult;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.SchedulerRunResolutionRepository;
import com.cretas.aims.repository.config.SchedulerRunResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("定时运行历史与未知结果裁定")
class SchedulerRunServiceTest {

    @Mock private SchedulerRunResultRepository results;
    @Mock private SchedulerRunResolutionRepository resolutions;
    private SchedulerRunService service;

    @BeforeEach
    void setUp() {
        service = new SchedulerRunService(results, resolutions);
    }

    private SchedulerRunResult run(String status) {
        return SchedulerRunResult.builder().runId("r-1").configId(1L).factoryId("F001").taskCode("daily")
                .startedAt(LocalDateTime.now().minusMinutes(1)).finishedAt(LocalDateTime.now())
                .status(status).rawStatus(status).build();
    }

    @Test
    @DisplayName("历史、最近一次都只按调用者的工厂查（工厂隔离）")
    void historyAndLatestAreFactoryScoped() {
        when(results.findByFactoryIdAndTaskCodeOrderByStartedAtDesc(eq("F001"), eq("daily"), any()))
                .thenReturn(new PageImpl<>(List.of(run("SUCCESS")), PageRequest.of(0, 20), 1));
        when(results.findFirstByFactoryIdAndTaskCodeOrderByStartedAtDesc("F002", "daily")).thenReturn(Optional.empty());

        assertEquals(1, service.history("F001", "daily", 1, 20).getContent().size());
        assertTrue(service.latest("F002", "daily").isEmpty(), "another factory's runs are never returned");
        verify(results, never()).findFirstByFactoryIdAndTaskCodeOrderByStartedAtDesc("F001", "daily");
    }

    @Test
    @DisplayName("UNKNOWN 运行可以裁定为成功：带说明、留审计行、生效状态变为裁定值，原始状态不改")
    void resolveUnknownToSuccessIsAudited() {
        SchedulerRunResult r = run("UNKNOWN");
        when(results.findForUpdate("r-1", "F001")).thenReturn(Optional.of(r));

        var view = service.resolve("F001", "r-1", "SUCCESS", " 已核对库存，确实执行了 ", 9L, "factory_super_admin");

        assertEquals("UNKNOWN", view.status(), "the recorded status stays what it was");
        assertEquals("SUCCESS", view.effectiveStatus());
        assertEquals("已核对库存，确实执行了", view.resolutionNote());
        ArgumentCaptor<SchedulerRunResolution> audit = ArgumentCaptor.forClass(SchedulerRunResolution.class);
        verify(resolutions).save(audit.capture());
        assertEquals("UNKNOWN", audit.getValue().getFromStatus());
        assertEquals("SUCCESS", audit.getValue().getToStatus());
        assertEquals(9L, audit.getValue().getResolvedBy());
        assertEquals("F001", audit.getValue().getFactoryId());
    }

    @Test
    @DisplayName("没填说明、结果值非法、已裁定、本来就明确、别的工厂的运行：全部拒绝且不写审计")
    void resolveRefusals() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.resolve("F001", "r-1", "SUCCESS", "  ", 9L, "x")).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.resolve("F001", "r-1", "UNKNOWN", "n", 9L, "x")).getCode());

        SchedulerRunResult ruled = run("UNKNOWN");
        ruled.setResolution("FAILED");
        when(results.findForUpdate("ruled", "F001")).thenReturn(Optional.of(ruled));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.resolve("F001", "ruled", "SUCCESS", "n", 9L, "x")).getCode());

        when(results.findForUpdate("ok", "F001")).thenReturn(Optional.of(run("SUCCESS")));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.resolve("F001", "ok", "FAILED", "n", 9L, "x")).getCode());

        when(results.findForUpdate("r-1", "F002")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.resolve("F002", "r-1", "SUCCESS", "n", 9L, "x")).getCode());

        verify(resolutions, never()).save(any());
        verify(results, never()).save(any());
    }

    @Test
    @DisplayName("未裁定的 UNKNOWN 运行的生效状态是 UNKNOWN，不是成功")
    void unruledUnknownIsNeverCountedAsSuccess() {
        assertEquals("UNKNOWN", run("UNKNOWN").effectiveStatus());
    }
}
