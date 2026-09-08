package com.cretas.aims.controller;

import com.cretas.aims.security.PriceMaskResolver;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.net.SocketTimeoutException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrintControllerTransportTest {
    final RestTemplate client = mock(RestTemplate.class);
    final PrintController controller = new PrintController(client, "http://python", mock(PriceMaskResolver.class));
    final HttpEntity<Map<String,Object>> request = new HttpEntity<>(Map.of("factoryId", "F006"));
    @AfterEach void clearMdc() { MDC.clear(); }

    @Test void droppedConnectionRetriesOnceAndPropagatesCorrelation() {
        MDC.put("correlationId", "print:test-1");
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(client.exchange(eq("http://python/render"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("connection closed", new NoHttpResponseException("closed")))
                .thenReturn(ResponseEntity.ok(pdf));
        assertThat(controller.renderPdf("http://python/render", request, "material-requisition").getBody()).isEqualTo(pdf);
        verify(client, times(2)).exchange(eq("http://python/render"), eq(HttpMethod.POST),
                argThat((HttpEntity<?> r) -> "print:test-1".equals(r.getHeaders().getFirst("X-Correlation-ID"))), eq(byte[].class));
    }

    @Test void repeatedConnectionFailureRemainsFailure() {
        when(client.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("closed", new NoHttpResponseException("closed")));
        assertThatThrownBy(() -> controller.renderPdf("http://python/render", request, "material-requisition"))
                .isInstanceOf(ResourceAccessException.class);
        verify(client, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class));
    }

    @Test void timeoutDoesNotDoubleRenderingBudget() {
        when(client.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> controller.renderPdf("http://python/render", request, "material-requisition"))
                .isInstanceOf(ResourceAccessException.class);
        verify(client).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class));
    }

    @Test void httpRejectionIsNotRetried() {
        when(client.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.renderPdf("http://python/render", request, "material-requisition"))
                .isInstanceOf(HttpClientErrorException.class);
        verify(client).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class));
    }
}
