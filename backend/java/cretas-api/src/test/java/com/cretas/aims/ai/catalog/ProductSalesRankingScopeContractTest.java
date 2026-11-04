package com.cretas.aims.ai.catalog;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSalesRankingScopeContractTest {
    @Test
    void unknownOrForeignIdentityNeverQueriesRanking() {
        var repo = mock(SalesOrderItemRepository.class);
        var service = new ProductSalesRankingService(repo, mock(DataScopeResolver.class));
        assertThatThrownBy(() -> service.rank("A", LocalDate.now(), LocalDate.now())).hasMessageContaining("身份");
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 1L, "B", "sales"))) {
            assertThatThrownBy(() -> service.rank("A", LocalDate.now(), LocalDate.now())).hasMessageContaining("不一致");
        }
        verifyNoInteractions(repo);
    }

    @Test
    void selfScopeIsResolvedForAsyncActorAndCustomNeverBecomesAll() {
        var repo = mock(SalesOrderItemRepository.class);
        var resolver = mock(DataScopeResolver.class);
        var service = new ProductSalesRankingService(repo, resolver);
        var day = LocalDate.of(2026, 11, 4);
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 7L, "A", "sales"))) {
            when(resolver.resolve(7L)).thenReturn(DataScopeContext.builder().userId(7L).factoryId("A").scope(DataScope.SELF).build());
            service.rank("A", day, day);
            verify(repo).rankSalesByProduct(eq("A"), eq(day), eq(day), any(), eq(true), eq(List.of(7L)));
            clearInvocations(repo);
            when(resolver.resolve(7L)).thenReturn(DataScopeContext.builder().userId(7L).factoryId("A").scope(DataScope.CUSTOM).build());
            assertThat(service.rank("A", day, day)).isEmpty();
            verifyNoInteractions(repo);
        }
    }
}
