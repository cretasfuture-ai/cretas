package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.repository.indicator.*;
import com.cretas.aims.service.indicator.impl.IndicatorQueryServiceImpl;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    void unimplementedIndicatorBridgeCannotReturnSyntheticZero() {
        assertThatThrownBy(() -> realClient().fetchIndicatorValue("F-TEST", "/unimplemented",
                LocalDate.now(), LocalDate.now()))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("not implemented");
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
