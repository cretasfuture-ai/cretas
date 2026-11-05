package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Factory indicator contracts, including fail-closed retired Python sources. */
@ExtendWith(MockitoExtension.class)
class IndicatorQueryServiceImplTest {
    @Mock private IndicatorRepository indicatorRepository;
    @Mock private IndicatorVersionRepository indicatorVersionRepository;
    @Mock private IndicatorComputationRepository indicatorComputationRepository;
    @Mock private PythonSmartBIClient pythonSmartBIClient;
    @Mock private IndicatorComputationStrategyRegistry strategyRegistry;
    @InjectMocks private IndicatorQueryServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String CODE = "FACTORY_REVENUE";
    private static final String IND_ID = "ind-f006-revenue";
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    private Indicator indicator(String strategy, BigDecimal value, LocalDateTime computed) {
        Indicator indicator = new Indicator();
        indicator.setId(IND_ID);
        indicator.setFactoryId(FACTORY_ID);
        indicator.setCode(CODE);
        indicator.setName("工厂销售额");
        indicator.setCategory("FINANCE");
        indicator.setUnit("CNY");
        indicator.setIsActive(true);
        indicator.setComputeStrategy(strategy);
        indicator.setCacheTtlSeconds(3600);
        indicator.setLastValue(value);
        indicator.setLastComputedAt(computed);
        return indicator;
    }

    private IndicatorComputation computation(String type, String source) {
        IndicatorComputation computation = new IndicatorComputation();
        computation.setId("cmp-" + IND_ID);
        computation.setIndicatorId(IND_ID);
        computation.setComputeType(type);
        computation.setComputeSource(source);
        computation.setIsActive(true);
        computation.setPriority(1);
        return computation;
    }

    @Test
    void precomputedFactoryValueDoesNotCallRetiredPythonClient() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(indicator("PRECOMPUTED", new BigDecimal("65.43"),
                        LocalDateTime.now().minusHours(2))));
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);
        assertEquals(new BigDecimal("65.43"), result.value());
        assertEquals("precomputed", result.source());
        assertTrue(result.cacheHit());
        verifyNoInteractions(pythonSmartBIClient);
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    void cachedFactoryAggregateUsesStrategyRegistry() {
        Indicator indicator = indicator("CACHED", null, null);
        IndicatorComputation computation = computation("JPA_AGGREGATE", null);
        IndicatorComputationStrategy strategy = org.mockito.Mockito.mock(IndicatorComputationStrategy.class);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(indicator));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(computation));
        when(strategyRegistry.find(CODE)).thenReturn(Optional.of(strategy));
        when(strategy.compute(FACTORY_ID, START, END)).thenReturn(null);
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);
        assertNull(result.value());
        assertEquals(IndicatorValueResult.NO_DATA, result.status());
        assertEquals("REAL_BUSINESS:" + CODE, result.source());
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    void retiredPythonComputationFailsClosedWithoutClientCallOrPersistence() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(indicator("CACHED", null, null)));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(computation("PYTHON_ENDPOINT", "/api/smartbi/indicator/restaurant-dish-margin")));
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);
        assertNull(result.value());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        verifyNoInteractions(pythonSmartBIClient);
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    void unknownComputationFailsClosed() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(indicator("CACHED", null, null)));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(computation("JPA_QUERY", "SELECT 0")));
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);
        assertNull(result.value());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        assertEquals("UNKNOWN:" + CODE, result.source());
    }

    @Test
    void missingIndicatorIsRejected() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.empty());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.computeForCode(CODE, FACTORY_ID, START, END));
        assertTrue(exception.getMessage().contains("Indicator not found"));
    }

    @Test
    void noComputationFailsClosed() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(indicator("CACHED", null, null)));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(Collections.emptyList());
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);
        assertNull(result.value());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        assertEquals("UNKNOWN:" + CODE, result.source());
    }
}
