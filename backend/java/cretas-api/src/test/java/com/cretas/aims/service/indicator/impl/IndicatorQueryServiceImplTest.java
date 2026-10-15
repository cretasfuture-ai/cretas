package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IndicatorQueryServiceImpl 单元测试 — Phase 1 Sprint 1 Day 4 (2026-05-22).
 *
 * <p>覆盖策略分支:
 * <ol>
 *   <li>PRECOMPUTED — 直接返 lastValue, 不调 Python 不落 version</li>
 *   <li>PRECOMPUTED + lastValue=null — 保持 null / NOT_COMPUTED, 不伪造时间</li>
 *   <li>CACHED + 命中 TTL — 返 lastValue, 不调 Python</li>
 *   <li>CACHED + 过期 — 调 Python + 更新 lastValue + 落 version</li>
 *   <li>CACHED + 首次 (lastComputedAt=null) — 调 Python + 更新</li>
 *   <li>REALTIME — 调 Python + 落 version, 不更新 lastValue</li>
 *   <li>未知 strategy — 抛 BusinessException</li>
 *   <li>指标不存在 — 抛 BusinessException</li>
 *   <li>computation 不是 PYTHON_ENDPOINT — FAILED, 不伪装成业务 ZERO</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
@DisplayName("IndicatorQueryServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class IndicatorQueryServiceImplTest {

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private IndicatorVersionRepository indicatorVersionRepository;

    @Mock
    private IndicatorComputationRepository indicatorComputationRepository;

    @Mock
    private PythonSmartBIClient pythonSmartBIClient;

    /** Sprint 12 Phase B: 新增 JPA_AGGREGATE 路由依赖. 现有测试场景都是 PYTHON_ENDPOINT
     *  或 non-PYTHON_ENDPOINT default 分支, registry.find() 不会被调用 (Mockito 默认 empty). */
    @Mock
    private IndicatorComputationStrategyRegistry strategyRegistry;

    @InjectMocks
    private IndicatorQueryServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String CODE = "RESTAURANT_DISH_MARGIN";
    private static final String IND_ID = "ind-f006-rdm";
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    private Indicator newIndicator(String strategy, BigDecimal lastValue,
                                   LocalDateTime lastComputed, Integer ttl) {
        Indicator ind = new Indicator();
        ind.setId(IND_ID);
        ind.setFactoryId(FACTORY_ID);
        ind.setCode(CODE);
        ind.setName("菜品毛利率");
        ind.setCategory("RESTAURANT");
        ind.setUnit("%");
        ind.setIsActive(true);
        ind.setComputeStrategy(strategy);
        ind.setCacheTtlSeconds(ttl);
        ind.setLastValue(lastValue);
        ind.setLastComputedAt(lastComputed);
        return ind;
    }

    private IndicatorComputation newComputation(String type, String source) {
        IndicatorComputation c = new IndicatorComputation();
        c.setId("cmp-" + IND_ID);
        c.setIndicatorId(IND_ID);
        c.setComputeType(type);
        c.setComputeSource(source);
        c.setIsActive(true);
        c.setPriority(1);
        return c;
    }

    // ====================================================================
    // PRECOMPUTED branch
    // ====================================================================

    @Test
    @DisplayName("PRECOMPUTED: 返 lastValue, 标记 cacheHit=true, source=precomputed, 不调 Python")
    void computesPrecomputedFromLastValue() {
        Indicator ind = newIndicator("PRECOMPUTED", new BigDecimal("65.4321"),
                LocalDateTime.now().minusHours(2), 3600);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(new BigDecimal("65.4321"), result.value());
        assertTrue(result.cacheHit());
        assertEquals("precomputed", result.source());
        verify(pythonSmartBIClient, never())
                .fetchIndicatorValue(anyString(), anyString(), any(), any());
        verify(indicatorVersionRepository, never()).save(any());
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("PRECOMPUTED + lastValue=null: 保持 null / NOT_COMPUTED, 不伪造 now")
    void computesPrecomputedNullValuePreservesUncomputedState() {
        Indicator ind = newIndicator("PRECOMPUTED", null, null, 3600);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(null, result.value());
        assertEquals(null, result.computedAt());
        assertTrue(result.cacheHit());
        assertEquals("precomputed", result.source());
        assertEquals(IndicatorValueResult.NOT_COMPUTED, result.status());
    }

    // ====================================================================
    // CACHED branch
    // ====================================================================

    @Test
    @DisplayName("CACHED + TTL 内: 返 lastValue, 不调 Python")
    void computesCachedWithinTtl() {
        Indicator ind = newIndicator("CACHED", new BigDecimal("70.1234"),
                LocalDateTime.now().minusSeconds(60), 3600);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));

        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(newComputation("JPA_AGGREGATE", CODE)));
        when(strategyRegistry.find(CODE)).thenReturn(Optional.of(mock(
                com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy.class)));
        IndicatorVersion evidence = new IndicatorVersion();
        evidence.setFactoryId(FACTORY_ID);
        evidence.setPeriodStart(START);
        evidence.setPeriodEnd(END);
        evidence.setComputedAt(ind.getLastComputedAt());
        evidence.setValue(ind.getLastValue());
        evidence.setComputeSource("REAL_BUSINESS:" + CODE);
        when(indicatorVersionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(IND_ID))
                .thenReturn(Optional.of(evidence));
        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(new BigDecimal("70.1234"), result.value());
        assertTrue(result.cacheHit());
        assertEquals("REAL_BUSINESS:" + CODE, result.source());
        verify(pythonSmartBIClient, never())
                .fetchIndicatorValue(anyString(), anyString(), any(), any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("CACHED + 过期: 调 Python + 更新 lastValue + 落 version")
    void computesCachedExpiredFetchesFreshFromPython() {
        Indicator ind = newIndicator("CACHED", new BigDecimal("50.0000"),
                LocalDateTime.now().minusHours(2), 3600);
        IndicatorComputation comp = newComputation("PYTHON_ENDPOINT",
                "/api/smartbi/indicator/restaurant-dish-margin");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(pythonSmartBIClient.fetchIndicatorValue(eq(FACTORY_ID),
                eq("/api/smartbi/indicator/restaurant-dish-margin"), eq(START), eq(END)))
                .thenReturn(new BigDecimal("75.5000"));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(new BigDecimal("75.5000"), result.value());
        assertFalse(result.cacheHit());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        assertEquals(IndicatorValueResult.AVAILABLE, result.status());
        assertNotNull(result.computedAt());

        ArgumentCaptor<Indicator> indCap = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorRepository).save(indCap.capture());
        assertEquals(new BigDecimal("75.5000"), indCap.getValue().getLastValue());
        assertNotNull(indCap.getValue().getLastComputedAt());

        ArgumentCaptor<IndicatorVersion> verCap = ArgumentCaptor.forClass(IndicatorVersion.class);
        verify(indicatorVersionRepository).save(verCap.capture());
        IndicatorVersion saved = verCap.getValue();
        assertEquals(IND_ID, saved.getIndicatorId());
        assertEquals(FACTORY_ID, saved.getFactoryId());
        assertEquals(START, saved.getPeriodStart());
        assertEquals(END, saved.getPeriodEnd());
        assertEquals(new BigDecimal("75.5000"), saved.getValue());
    }

    @Test
    @DisplayName("CACHED + 首次 (lastComputedAt=null): 视为 cache miss, 调 Python")
    void computesCachedFirstCallFetchesFromPython() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        IndicatorComputation comp = newComputation("PYTHON_ENDPOINT",
                "/api/smartbi/indicator/restaurant-dish-margin");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(pythonSmartBIClient.fetchIndicatorValue(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("80.0000"));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertFalse(result.cacheHit());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        assertEquals(IndicatorValueResult.AVAILABLE, result.status());
        assertNotNull(result.computedAt());
        verify(pythonSmartBIClient, times(1))
                .fetchIndicatorValue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CACHED + 真实零: ZERO 仍是 AVAILABLE, 保留来源和计算时间")
    void computesCachedRealZeroAsAvailableValue() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        IndicatorComputation comp = newComputation("PYTHON_ENDPOINT", "/api/smartbi/indicator/count");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(pythonSmartBIClient.fetchIndicatorValue(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(BigDecimal.ZERO, result.value());
        assertEquals(IndicatorValueResult.AVAILABLE, result.status());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        assertNotNull(result.computedAt());
        verify(indicatorRepository).save(any(Indicator.class));
        verify(indicatorVersionRepository).save(any(IndicatorVersion.class));
    }

    // ====================================================================
    // REALTIME branch
    // ====================================================================

    @Test
    @DisplayName("REALTIME: 每次调 Python + 落 version, 不更新 lastValue")
    void computesRealtimeAlwaysCallsPython() {
        Indicator ind = newIndicator("REALTIME", new BigDecimal("60.0000"),
                LocalDateTime.now().minusSeconds(10), 3600);
        IndicatorComputation comp = newComputation("PYTHON_ENDPOINT",
                "/api/smartbi/indicator/restaurant-dish-margin");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(pythonSmartBIClient.fetchIndicatorValue(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("85.5000"));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(new BigDecimal("85.5000"), result.value());
        assertFalse(result.cacheHit());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        assertEquals(IndicatorValueResult.AVAILABLE, result.status());
        assertNotNull(result.computedAt());
        verify(pythonSmartBIClient, times(1))
                .fetchIndicatorValue(any(), any(), any(), any());
        verify(indicatorVersionRepository).save(any());
        // Realtime 不更新 lastValue
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("JPA_AGGREGATE 无样本: 保持 null / NO_DATA, 不落假快照")
    void computesJpaAggregateNoDataWithoutPersisting() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        IndicatorComputation comp = newComputation("JPA_AGGREGATE", null);
        com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy strategy =
                org.mockito.Mockito.mock(com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy.class);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(strategyRegistry.find(CODE)).thenReturn(Optional.of(strategy));
        when(strategy.compute(FACTORY_ID, START, END)).thenReturn(null);

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(null, result.value());
        assertEquals(null, result.computedAt());
        assertEquals("REAL_BUSINESS:" + CODE, result.source());
        assertEquals(IndicatorValueResult.NO_DATA, result.status());
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    // ====================================================================
    // Error branches
    // ====================================================================

    @Test
    @DisplayName("未知 strategy: 抛 BusinessException")
    void unknownStrategyThrowsBusinessException() {
        Indicator ind = newIndicator("UNKNOWN_STRATEGY", BigDecimal.ZERO, null, 3600);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.computeForCode(CODE, FACTORY_ID, START, END));
        assertTrue(ex.getMessage().contains("Unknown computeStrategy"));
    }

    @Test
    @DisplayName("指标不存在: 抛 BusinessException")
    void notFoundIndicatorThrowsBusinessException() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.computeForCode(CODE, FACTORY_ID, START, END));
        assertTrue(ex.getMessage().contains("Indicator not found"));
    }

    @Test
    @DisplayName("computation 不是 PYTHON_ENDPOINT: 标记 FAILED, 不伪装成业务 ZERO")
    void cachedWithUnknownComputationReturnsFailure() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        IndicatorComputation stub = newComputation("JPA_QUERY",
                "SELECT 0 -- TODO: Phase 2B");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(stub));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(null, result.value());
        assertEquals("UNKNOWN:" + CODE, result.source());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        assertEquals(null, result.computedAt());
        verify(pythonSmartBIClient, never())
                .fetchIndicatorValue(anyString(), anyString(), any(), any());
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("CACHED + 没有 active computation: 标记 FAILED, 不伪装成业务 ZERO")
    void cachedNoComputationReturnsFailure() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(Collections.emptyList());

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(null, result.value());
        assertEquals("UNKNOWN:" + CODE, result.source());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        assertEquals(null, result.computedAt());
        verify(pythonSmartBIClient, never())
                .fetchIndicatorValue(anyString(), anyString(), any(), any());
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("PYTHON_ENDPOINT 异常: 标记 FAILED、无计算时间且不写回缓存")
    void cachedPythonFailureDoesNotBecomeZeroOrFresh() {
        Indicator ind = newIndicator("CACHED", null, null, 3600);
        IndicatorComputation comp = newComputation("PYTHON_ENDPOINT", "/api/smartbi/indicator/failing");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorComputationRepository.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(IND_ID))
                .thenReturn(List.of(comp));
        when(pythonSmartBIClient.fetchIndicatorValue(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("upstream unavailable"));

        IndicatorValueResult result = service.computeForCode(CODE, FACTORY_ID, START, END);

        assertEquals(null, result.value());
        assertEquals(null, result.computedAt());
        assertEquals("PYTHON_ENDPOINT:" + CODE, result.source());
        assertEquals(IndicatorValueResult.FAILED, result.status());
        verify(indicatorRepository, never()).save(any());
        verify(indicatorVersionRepository, never()).save(any());
    }
}
