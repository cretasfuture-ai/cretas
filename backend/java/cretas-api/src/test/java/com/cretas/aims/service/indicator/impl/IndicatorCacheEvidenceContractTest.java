package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.indicator.*;
import com.cretas.aims.repository.indicator.*;
import com.cretas.aims.service.indicator.strategy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndicatorCacheEvidenceContractTest {
    private final IndicatorRepository indicators = mock(IndicatorRepository.class);
    private final IndicatorVersionRepository versions = mock(IndicatorVersionRepository.class);
    private final IndicatorComputationRepository computations = mock(IndicatorComputationRepository.class);
    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final IndicatorComputationStrategyRegistry registry = mock(IndicatorComputationStrategyRegistry.class);
    private final IndicatorComputationStrategy business = mock(IndicatorComputationStrategy.class);
    private final IndicatorQueryServiceImpl service = new IndicatorQueryServiceImpl(indicators, versions, computations, python, registry);
    private final LocalDate start = LocalDate.of(2026, 10, 1);
    private final LocalDate end = LocalDate.of(2026, 10, 15);

    private Indicator root(String type, boolean registered) {
        Indicator indicator = new Indicator();
        indicator.setId("cache-test"); indicator.setCode("CACHE_TEST"); indicator.setFactoryId("F-TEST");
        indicator.setComputeStrategy("CACHED"); indicator.setCacheTtlSeconds(3600);
        indicator.setLastValue(BigDecimal.ZERO); indicator.setLastComputedAt(LocalDateTime.now().minusSeconds(10));
        when(indicators.findByCodeAndFactoryIdAndDeletedAtIsNull("CACHE_TEST", "F-TEST")).thenReturn(Optional.of(indicator));
        IndicatorComputation computation = new IndicatorComputation(); computation.setComputeType(type);
        when(computations.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull("cache-test"))
                .thenReturn(type == null ? List.of() : List.of(computation));
        if (registered) when(registry.find("CACHE_TEST")).thenReturn(Optional.of(business));
        return indicator;
    }

    private void rejectsUnverifiedZero(String type) {
        Indicator indicator = root(type, false);
        var result = service.computeForCode("CACHE_TEST", "F-TEST", start, end);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.value()).isNull(); assertThat(result.computedAt()).isNull();
        assertThat(indicator.getLastValue()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(versions, never()).save(any()); verify(indicators, never()).save(any());
    }

    @Test void absentConfigurationCannotPromoteAnOldZero() { rejectsUnverifiedZero(null); }
    @Test void placeholderJpaQueryCannotPromoteAnOldZero() { rejectsUnverifiedZero("JPA_QUERY"); }
    @Test void missingBusinessStrategyCannotPromoteAnOldZero() { rejectsUnverifiedZero("JPA_AGGREGATE"); }

    private void evidence(Indicator indicator, LocalDate periodStart) {
        IndicatorVersion version = new IndicatorVersion();
        version.setFactoryId("F-TEST"); version.setPeriodStart(periodStart); version.setPeriodEnd(end);
        version.setComputedAt(indicator.getLastComputedAt()); version.setValue(BigDecimal.ZERO);
        version.setComputeSource("REAL_BUSINESS:CACHE_TEST");
        when(versions.findFirstByIndicatorIdOrderByComputedAtDesc("cache-test")).thenReturn(Optional.of(version));
    }

    @Test void verifiedBusinessZeroRemainsAvailableAndRetainsItsSource() {
        Indicator indicator = root("JPA_AGGREGATE", true); evidence(indicator, start);
        var result = service.computeForCode("CACHE_TEST", "F-TEST", start, end);
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.cacheHit()).isTrue(); assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.source()).isEqualTo("REAL_BUSINESS:CACHE_TEST");
        verifyNoInteractions(python); verify(business, never()).compute(any(), any(), any());
    }

    @Test void anotherPeriodIsRecomputedInsteadOfRelabelingTheOldZero() {
        Indicator indicator = root("JPA_AGGREGATE", true); evidence(indicator, start.minusMonths(1));
        when(business.compute("F-TEST", start, end)).thenReturn(BigDecimal.TEN);
        var result = service.computeForCode("CACHE_TEST", "F-TEST", start, end);
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(result.cacheHit()).isFalse(); assertThat(result.source()).isEqualTo("REAL_BUSINESS:CACHE_TEST");
        verify(versions).save(any()); verifyNoInteractions(python);
    }

    @Test void changedFormulaDoesNotRelabelAnOldCachedValueOrItsHistory() {
        Indicator indicator = root("JPA_AGGREGATE", true); evidence(indicator, start);
        IndicatorVersion old = new IndicatorVersion();
        old.setFactoryId("F-TEST"); old.setPeriodStart(start); old.setPeriodEnd(end);
        old.setComputedAt(indicator.getLastComputedAt()); old.setValue(BigDecimal.ZERO);
        old.setComputeSource("REAL_BUSINESS:CACHE_TEST:actual-flow-v2");
        when(versions.findFirstByIndicatorIdOrderByComputedAtDesc("cache-test")).thenReturn(Optional.of(old));
        when(business.getSourceVersion()).thenReturn("actual-flow-v3");
        when(business.compute("F-TEST", start, end)).thenReturn(new BigDecimal("80"));
        var result = service.computeForCode("CACHE_TEST", "F-TEST", start, end);
        assertThat(result.cacheHit()).isFalse();
        assertThat(result.value()).isEqualByComparingTo("80");
        assertThat(result.source()).isEqualTo("REAL_BUSINESS:CACHE_TEST:actual-flow-v3");
        verify(versions).save(argThat(version -> version.getValue().compareTo(new BigDecimal("80")) == 0
                && "REAL_BUSINESS:CACHE_TEST:actual-flow-v3".equals(version.getComputeSource())));
        assertThat(old.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(old.getComputeSource()).isEqualTo("REAL_BUSINESS:CACHE_TEST:actual-flow-v2");
        verify(business).compute("F-TEST", start, end);
    }
}
