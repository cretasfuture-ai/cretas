package com.cretas.aims.ai.catalog;

import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSalesRankingAggregationContractTest {
    @Test
    void productAmountRanksAcrossBucketsWhileIncompatibleQuantitiesRemainSeparate() {
        var repo = mock(SalesOrderItemRepository.class);
        var rows = List.of(row("B", "kg", "5", "50"), row("A", "CASE", "2", "30"), row("A", "kg", "3", "30"));
        when(repo.rankSalesByProduct(anyString(), any(), any(), any(), anyBoolean(), anyList()))
                .thenReturn(rows);
        var service = new ProductSalesRankingService(repo, mock(DataScopeResolver.class));
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "platform_admin"))) {
            var result = service.rank("F006", LocalDate.now(), LocalDate.now());
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getProductTypeId()).isEqualTo("A");
            assertThat(result.get(0).getAmount()).isEqualByComparingTo("60");
            assertThat(result.get(0).getQuantity()).isNull();
            assertThat(result.get(0).getUnit()).isNull();
            assertThat(result.get(0).getQuantitiesByUnit()).containsEntry("CASE", new BigDecimal("2")).containsEntry("kg", new BigDecimal("3"));
            assertThat(result.get(1).getProductTypeId()).isEqualTo("B");
        }
    }
    private SalesOrderItemRepository.SalesRankingRow row(String product, String unit, String quantity, String amount) {
        var row = mock(SalesOrderItemRepository.SalesRankingRow.class);
        when(row.getProductTypeId()).thenReturn(product); when(row.getProductName()).thenReturn(product);
        when(row.getUnit()).thenReturn(unit); when(row.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(row.getAmount()).thenReturn(new BigDecimal(amount)); return row;
    }
}
