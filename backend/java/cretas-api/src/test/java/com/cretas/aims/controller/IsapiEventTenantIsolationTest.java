package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.isapi.IsapiEventLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.cretas.aims.service.isapi.IsapiAlertAnalysisService;
import com.cretas.aims.service.isapi.IsapiDeviceDiscoveryService;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄像头事件按 id 取 / 处理 / 批量处理: 只认 URL 里本厂的事件。事件表没有租户过滤,
 * 以前 A 厂按 id 能读到 B 厂事件 (含 AI 分析结果、抓拍地址) 并把它标成已处理。
 */
class IsapiEventTenantIsolationTest {

    private IsapiEventLogRepository events;
    private IsapiDeviceController controller;

    private static IsapiEventLog event(long id, String factoryId) {
        IsapiEventLog e = new IsapiEventLog();
        e.setId(id);
        e.setFactoryId(factoryId);
        e.setEventType("fielddetection");
        return e;
    }

    @BeforeEach
    void setUp() {
        events = mock(IsapiEventLogRepository.class);
        when(events.findById(7L)).thenReturn(Optional.of(event(7L, "F006")));
        when(events.findById(8L)).thenReturn(Optional.of(event(8L, "F007")));
        when(events.findAllById(anyList())).thenReturn(List.of(event(7L, "F006"), event(8L, "F007")));
        when(events.markAsProcessed(anyList(), any(), anyString())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        controller = new IsapiDeviceController(mock(IsapiDeviceService.class), mock(IsapiDeviceDiscoveryService.class),
                mock(IsapiEventSubscriptionService.class), events, mock(IsapiAlertAnalysisService.class));
    }

    @Test
    void detailOfOwnEventIsReturnedAndAnotherFactorysEventIsNotFound() {
        assertThat(controller.getEventDetail("F006", 7L).getData().getId()).isEqualTo(7L);

        assertThatThrownBy(() -> controller.getEventDetail("F006", 8L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("事件不存在");
    }

    @Test
    void processingAnotherFactorysEventIsRejectedAndNothingIsSaved() {
        controller.processEvent("F006", 7L, "u1", "ok");
        verify(events).save(any(IsapiEventLog.class));

        assertThatThrownBy(() -> controller.processEvent("F006", 8L, "u1", "ok"))
                .isInstanceOf(BusinessException.class);
        verify(events, org.mockito.Mockito.times(1)).save(any(IsapiEventLog.class));
    }

    @Test
    void batchProcessOnlyTouchesOwnEvents() {
        ApiResponse<Map<String, Object>> r = controller.batchProcessEvents("F006", List.of(7L, 8L), "u1", null);

        verify(events).markAsProcessed(eq(List.of(7L)), any(), eq("u1"));
        assertThat(r.getData()).containsEntry("processedCount", 1).containsEntry("requestedCount", 2);
    }

    @Test
    void batchProcessWithOnlyForeignIdsTouchesNothing() {
        when(events.findAllById(anyList())).thenReturn(List.of(event(8L, "F007")));

        ApiResponse<Map<String, Object>> r = controller.batchProcessEvents("F006", List.of(8L), "u1", null);

        verify(events, never()).markAsProcessed(anyList(), any(), anyString());
        assertThat(r.getData()).containsEntry("processedCount", 0);
    }
}
