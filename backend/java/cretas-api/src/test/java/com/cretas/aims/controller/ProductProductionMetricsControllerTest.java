package com.cretas.aims.controller;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.indicator.strategy.impl.FactoryActualProductionMetrics;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProductionMetricsControllerTest {
    @Test void operationalCostPermissionIsDelegatedForBothMaskStates() {
        var metrics = mock(FactoryActualProductionMetrics.class);
        var mask = mock(PriceMaskResolver.class);
        var controller = new ProductProductionMetricsController(metrics, mask);
        LocalDate day = LocalDate.of(2026, 10, 16);
        for (boolean masked : new boolean[]{true, false}) {
            when(mask.shouldMaskOperationalCost("test-header")).thenReturn(masked);
            controller.byProduct("F-TEST", day, day, "test-header");
            verify(metrics).byProduct("F-TEST", day, day, masked);
        }
        verify(mask, never()).shouldMaskPrice(anyString());
    }
    @Test void invalidOrExcessiveDateWindowsAreRejectedBeforeReading() {
        var metrics = mock(FactoryActualProductionMetrics.class);
        var mask = mock(PriceMaskResolver.class);
        var controller = new ProductProductionMetricsController(metrics, mask);
        LocalDate day = LocalDate.of(2026, 10, 16);
        assertThatThrownBy(() -> controller.byProduct("F-TEST", day, day.minusDays(1), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("90天");
        assertThatThrownBy(() -> controller.byProduct("F-TEST", day, day.plusDays(90), null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(metrics, mask);
    }
}
