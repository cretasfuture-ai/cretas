package com.cretas.aims.audit.activity;

import com.cretas.aims.audit.RequestAuditSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActivityEventRecorderContractTest {
    @Test
    void clientEventUsesVerifiedIdentityAndDropsUnapprovedValues() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        when(store.submit(any(ActivityEvent.class))).thenReturn(true);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/activity-events");
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", 42L);
        request.addHeader("X-Activity-Client", "web-admin");
        int accepted = recorder.recordClient(request, List.of(new ActivityEventRecorder.ClientEvent(
                "event-1", "click", "purchase.submit", "/orders/123456?token=secret",
                Map.of("entityId", "PO-123", "quantity", 12, "password", "secret", "name", "Alice"),
                "corr-123", "action-123", System.currentTimeMillis())));
        assertThat(accepted).isEqualTo(1);
        ArgumentCaptor<ActivityEvent> captured = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(store).submit(captured.capture());
        ActivityEvent event = captured.getValue();
        assertThat(event.factoryId()).isEqualTo("F006");
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.route()).isEqualTo("/orders/{id}");
        assertThat(event.detail()).containsEntry("entityId", "PO-123").containsEntry("quantity", 12)
                .doesNotContainKeys("password", "name");
    }

    @Test
    void forgedFactoryAndUnsafeActionProduceNoRows() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/activity-events");
        request.setAttribute("factoryId", "F999");
        request.setAttribute("userId", 42L);
        var event = new ActivityEventRecorder.ClientEvent("event-2", "click", "save", "/orders", Map.of(), null, null, null);
        assertThat(recorder.recordClient(request, List.of(event))).isZero();
        request.setAttribute("factoryId", "F006");
        assertThat(recorder.recordClient(request, List.of(new ActivityEventRecorder.ClientEvent(
                "event-3", "click", "email:alice@example.com", "/orders", Map.of(), null, null, System.currentTimeMillis())))).isZero();
        verifyNoInteractions(store);
    }

    @Test
    void apiOutcomeUsesRouteTemplateAndCorrelationWithoutPayload() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/orders/12345/submit");
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", 42L);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/mobile/{factoryId}/orders/{orderId}/submit");
        request.setAttribute("X-Correlation-ID", "corr-123");
        request.setAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE, Boolean.FALSE);
        request.setAttribute(RequestAuditSupport.RESPONSE_ERROR_CODE_ATTRIBUTE, "NO_STOCK");
        request.addHeader("X-Client-Action-ID", "action-123");
        recorder.recordApi(request, 200, 35);
        ArgumentCaptor<ActivityEvent> captured = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(store).submit(captured.capture());
        ActivityEvent event = captured.getValue();
        assertThat(event.route()).isEqualTo("/api/mobile/{factoryId}/orders/{orderId}/submit");
        assertThat(event.detail()).containsEntry("businessSuccess", false).containsEntry("errorCode", "NO_STOCK")
                .containsEntry("elapsedMs", 35L);
        assertThat(event.correlationId()).isEqualTo("corr-123");
        assertThat(event.clientActionId()).isEqualTo("action-123");
    }

    @Test
    void repeatedReadIsCollapsedButChangedOutcomeIsRecorded() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/F006/orders");
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", 42L);
        recorder.recordApi(request, 200, 10);
        recorder.recordApi(request, 200, 11);
        recorder.recordApi(request, 500, 12);
        verify(store, times(2)).submit(any(ActivityEvent.class));
    }

    @Test
    void distinctHumanReadsRemainCorrelated() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/mobile/F006/orders/12345");
        first.setAttribute("factoryId", "F006");
        first.setAttribute("userId", 42L);
        first.addHeader("X-Client-Action-ID", "open-order-1");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/mobile/F006/orders/67890");
        second.setAttribute("factoryId", "F006");
        second.setAttribute("userId", 42L);
        second.addHeader("X-Client-Action-ID", "open-order-2");
        recorder.recordApi(first, 200, 10);
        recorder.recordApi(second, 200, 10);
        verify(store, times(2)).submit(any(ActivityEvent.class));
    }

    @Test
    void staleExtremeAndOversizedIdentifiersCannotPoisonAdjacentEvents() {
        ActivityEventStore store = mock(ActivityEventStore.class);
        when(store.submit(any(ActivityEvent.class))).thenReturn(true);
        ActivityEventRecorder recorder = new ActivityEventRecorder(store);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/activity-events");
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", 42L);
        long now = System.currentTimeMillis();
        assertThat(recorder.recordClient(request, List.of(
                new ActivityEventRecorder.ClientEvent("old", "click", "save", "/orders", Map.of(), null, null, Long.MIN_VALUE),
                new ActivityEventRecorder.ClientEvent("x".repeat(65), "click", "save", "/orders", Map.of(), null, null, now),
                new ActivityEventRecorder.ClientEvent("good", "click", "save", "/orders", Map.of(), null, null, now)
        ))).isEqualTo(1);
        verify(store, times(1)).submit(any(ActivityEvent.class));
    }
}
