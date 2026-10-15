package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.repository.indicator.*;
import com.cretas.aims.service.indicator.impl.IndicatorQueryServiceImpl;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonIndicatorSourceContractTest {
    private PythonSmartBIClient realClient() {
        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setUrl("http://127.0.0.1:9");
        config.setConnectTimeout(100);
        config.setTimeout(100);
        return new PythonSmartBIClient(config, new OkHttpClient(), new ObjectMapper(),
                new PythonServiceCircuitBreaker(), "unused-local-fixture");
    }

    @Test
    void unknownIndicatorBridgeCannotReturnSyntheticZero() {
        assertThatThrownBy(() -> realClient().fetchIndicatorValue("F-TEST", "/unimplemented",
                LocalDate.now(), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test
    void typedBridgeCarriesTenantWindowSecretAndRealZero() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"data":{"factory_id":"F-TEST","period_start":"2026-10-01","period_end":"2026-10-15","value":0,"status":"AVAILABLE","source":"REAL_BUSINESS:RESTAURANT_DISH_MARGIN","computed_at":"2026-10-15T12:00:00","unit":"%"}}
                    """).addHeader("Content-Type", "application/json"));
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            PythonSmartBIClient client = new PythonSmartBIClient(config, new OkHttpClient(),
                    new ObjectMapper(), new PythonServiceCircuitBreaker(), "internal-secret");
            assertThat(client.fetchIndicatorValue("F-TEST", "/api/smartbi/restaurant-ops/gross-margin",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo(
                    "/api/smartbi/indicator/restaurant-dish-margin?factory_id=F-TEST&period_start=2026-10-01&period_end=2026-10-15");
            assertThat(request.getHeader("X-Factory-Id")).isEqualTo("F-TEST");
            assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("internal-secret");
        }
    }

    @Test
    void typedBridgeRejectsCrossFactoryAndWrongWindowResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"data":{"factory_id":"OTHER","period_start":"2026-10-01","period_end":"2026-10-15","value":4,"status":"AVAILABLE","computed_at":"2026-10-15T12:00:00"}}
                    """).addHeader("Content-Type", "application/json"));
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            PythonSmartBIClient client = new PythonSmartBIClient(config, new OkHttpClient(),
                    new ObjectMapper(), new PythonServiceCircuitBreaker(), "internal-secret");
            assertThatThrownBy(() -> client.fetchIndicatorValue("F-TEST",
                    "/api/smartbi/indicator/restaurant-dish-margin",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("scope");
        }
    }

    @Test
    void typedBridgeRejectsCorrectFactoryWithWrongWindow() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"data":{"factory_id":"F-TEST","period_start":"2026-09-01","period_end":"2026-09-30","value":4,"status":"AVAILABLE","source":"REAL_BUSINESS:RESTAURANT_DISH_MARGIN","computed_at":"2026-10-15T12:00:00","unit":"%"}}
                    """).addHeader("Content-Type", "application/json"));
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            PythonSmartBIClient client = new PythonSmartBIClient(config, new OkHttpClient(),
                    new ObjectMapper(), new PythonServiceCircuitBreaker(), "internal-secret");
            assertThatThrownBy(() -> client.fetchIndicatorValue("F-TEST",
                    "/api/smartbi/indicator/restaurant-dish-margin",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("scope");
        }
    }

    @Test
    void typedBridgeKeepsNoDataAsNull() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"data":{"factory_id":"F-TEST","period_start":"2026-10-01","period_end":"2026-10-15","value":null,"status":"NO_DATA","source":"REAL_BUSINESS:RESTAURANT_DISH_MARGIN","computed_at":null,"unit":"%"}}
                    """).addHeader("Content-Type", "application/json"));
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            PythonSmartBIClient client = new PythonSmartBIClient(config, new OkHttpClient(),
                    new ObjectMapper(), new PythonServiceCircuitBreaker(), "internal-secret");
            assertThat(client.fetchIndicatorValue("F-TEST", "/api/smartbi/indicator/restaurant-dish-margin",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15))).isNull();
        }
    }

    @Test
    void anotherIndicatorCodeCannotReuseTheDishMarginBridge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            IndicatorRepository indicators = mock(IndicatorRepository.class);
            IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
            IndicatorComputationRepository computations = mock(IndicatorComputationRepository.class);
            Indicator indicator = new Indicator();
            indicator.setId("other-indicator");
            indicator.setCode("OTHER_KPI");
            indicator.setFactoryId("F-TEST");
            indicator.setComputeStrategy("REALTIME");
            IndicatorComputation computation = new IndicatorComputation();
            computation.setComputeType("PYTHON_ENDPOINT");
            computation.setComputeSource("/api/smartbi/indicator/restaurant-dish-margin");
            when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull("OTHER_KPI", "F-TEST"))
                    .thenReturn(Optional.of(indicator));
            when(computations.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull("other-indicator"))
                    .thenReturn(List.of(computation));
            var service = new IndicatorQueryServiceImpl(indicators, versions, computations,
                    new PythonSmartBIClient(config, new OkHttpClient(), new ObjectMapper(),
                            new PythonServiceCircuitBreaker(), "internal-secret"),
                    new IndicatorComputationStrategyRegistry(List.of()));
            var result = service.computeForCode("OTHER_KPI", "F-TEST",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.value()).isNull();
            assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void anotherIndicatorCachedZeroCannotReuseTheDishMarginBridge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            PythonSmartBIConfig config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl(server.url("/").toString());
            config.setMaxRetries(0);
            IndicatorRepository indicators = mock(IndicatorRepository.class);
            IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
            IndicatorComputationRepository computations = mock(IndicatorComputationRepository.class);
            Indicator indicator = new Indicator();
            indicator.setId("other-cached-indicator");
            indicator.setCode("OTHER_KPI");
            indicator.setFactoryId("F-TEST");
            indicator.setComputeStrategy("CACHED");
            indicator.setCacheTtlSeconds(3600);
            indicator.setLastValue(BigDecimal.ZERO);
            indicator.setLastComputedAt(LocalDateTime.now().minusSeconds(10));
            IndicatorComputation computation = new IndicatorComputation();
            computation.setComputeType("PYTHON_ENDPOINT");
            computation.setComputeSource("/api/smartbi/indicator/restaurant-dish-margin");
            when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull("OTHER_KPI", "F-TEST"))
                    .thenReturn(Optional.of(indicator));
            when(computations.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull("other-cached-indicator"))
                    .thenReturn(List.of(computation));
            var service = new IndicatorQueryServiceImpl(indicators, versions, computations,
                    new PythonSmartBIClient(config, new OkHttpClient(), new ObjectMapper(),
                            new PythonServiceCircuitBreaker(), "internal-secret"),
                    new IndicatorComputationStrategyRegistry(List.of()));
            var result = service.computeForCode("OTHER_KPI", "F-TEST",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.value()).isNull();
            assertThat(result.cacheHit()).isFalse();
            assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
            verify(versions, never()).findFirstByIndicatorIdOrderByComputedAtDesc(anyString());
        }
    }

    @Test
    void realBridgeFailureReachesTheServiceWithoutCreatingAZeroSnapshot() {
        assertUnavailable("REALTIME", null, null);
    }

    @Test
    void anOldZeroInsideTtlCannotHideAnUnimplementedPythonSource() {
        assertUnavailable("CACHED", BigDecimal.ZERO, LocalDateTime.now());
    }

    private void assertUnavailable(String strategy, BigDecimal previous, LocalDateTime timestamp) {
        IndicatorRepository indicators = mock(IndicatorRepository.class);
        IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
        IndicatorComputationRepository computations = mock(IndicatorComputationRepository.class);
        Indicator indicator = new Indicator();
        indicator.setId("unconnected");
        indicator.setCode("UNCONNECTED");
        indicator.setFactoryId("F-TEST");
        indicator.setComputeStrategy(strategy);
        indicator.setLastValue(previous);
        indicator.setLastComputedAt(timestamp);
        indicator.setCacheTtlSeconds(3600);
        IndicatorComputation computation = new IndicatorComputation();
        computation.setComputeType("PYTHON_ENDPOINT");
        computation.setComputeSource("/unimplemented");
        when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull("UNCONNECTED", "F-TEST"))
                .thenReturn(Optional.of(indicator));
        when(computations.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull("unconnected"))
                .thenReturn(List.of(computation));
        var service = new IndicatorQueryServiceImpl(indicators, versions, computations,
                realClient(), new IndicatorComputationStrategyRegistry(List.of()));
        var result = service.computeForCode("UNCONNECTED", "F-TEST", LocalDate.now(), LocalDate.now());
        assertThat(result.value()).isNull();
        assertThat(result.computedAt()).isNull();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.source()).isEqualTo("PYTHON_ENDPOINT:UNCONNECTED");
        assertThat(indicator.getLastValue()).isEqualTo(previous);
        verify(versions, never()).save(any());
        verify(indicators, never()).save(any());
    }
}
