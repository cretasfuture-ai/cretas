package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 仓库发货队列 (待确认 / 历史) 的服务层接线: 路由闸已对受限数据范围放行这两条 GET,
 * 真正的行过滤只在 {@code warehouseQueuePage} 里。三态:
 * 归属集合非空 → 只走按归属过滤的查询并传入该集合; 空 → 空页且不查库; null(全工厂) → 原工厂查询。
 */
class SalesServiceImplWarehouseQueueScopeContractTest {
    private static final String FACTORY = "F006";

    private final SalesDeliveryRecordRepository deliveries = mock(SalesDeliveryRecordRepository.class);
    private final BusinessRowScope rowScope = mock(BusinessRowScope.class);
    private SalesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(mock(SalesOrderRepository.class), mock(SalesOrderItemRepository.class),
                deliveries, mock(FinishedGoodsBatchRepository.class), mock(CustomerRepository.class),
                mock(ProductTypeRepository.class), mock(ArApService.class), mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "businessRowScope", rowScope);
    }

    private Supplier<PageResponse<SalesDeliveryRecord>> entry(String which) {
        return which.equals("pending")
                ? () -> service.getPendingWarehouseDeliveries(FACTORY, 1, 20)
                : () -> service.getWarehouseDeliveryHistory(FACTORY, 1, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "history"})
    void restrictedScopeQueriesOnlyByOwnersAndPassesThatList(String which) {
        List<Long> owners = List.of(1555L, 1560L);
        when(rowScope.recordCreatorIds(FACTORY)).thenReturn(owners);
        SalesDeliveryRecord mine = new SalesDeliveryRecord();
        mine.setId("D-MINE");
        when(deliveries.findByFactoryIdAndStatusInAndOwnerIn(eq(FACTORY), anyList(), eq(owners), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mine)));

        PageResponse<SalesDeliveryRecord> page = entry(which).get();

        assertThat(page.getContent()).extracting(SalesDeliveryRecord::getId).containsExactly("D-MINE");
        verify(deliveries).findByFactoryIdAndStatusInAndOwnerIn(eq(FACTORY), anyList(), eq(owners), any(Pageable.class));
        verify(deliveries, never()).findByFactoryIdAndStatusIn(anyString(), anyList(), any(Pageable.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "history"})
    void emptyOwnerSetReturnsEmptyPageWithoutQuerying(String which) {
        when(rowScope.recordCreatorIds(FACTORY)).thenReturn(List.of());

        PageResponse<SalesDeliveryRecord> page = entry(which).get();

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(deliveries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "history"})
    void wholeFactoryScopeKeepsTheFactoryQuery(String which) {
        when(rowScope.recordCreatorIds(FACTORY)).thenReturn(null);
        when(deliveries.findByFactoryIdAndStatusIn(eq(FACTORY), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        entry(which).get();

        verify(deliveries).findByFactoryIdAndStatusIn(eq(FACTORY), anyList(), any(Pageable.class));
        verify(deliveries, never()).findByFactoryIdAndStatusInAndOwnerIn(anyString(), anyList(), anyList(), any(Pageable.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "history"})
    void eachEntryKeepsItsOwnStatusSet(String which) {
        when(rowScope.recordCreatorIds(FACTORY)).thenReturn(List.of(1555L));
        when(deliveries.findByFactoryIdAndStatusInAndOwnerIn(anyString(), anyList(), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        entry(which).get();

        List<SalesDeliveryStatus> expected = which.equals("pending")
                ? List.of(SalesDeliveryStatus.DRAFT, SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM, SalesDeliveryStatus.PICKED)
                : List.of(SalesDeliveryStatus.PARTIALLY_SHIPPED, SalesDeliveryStatus.SHIPPED, SalesDeliveryStatus.DELIVERED,
                        SalesDeliveryStatus.CANCELLED, SalesDeliveryStatus.RETURNED);
        verify(deliveries).findByFactoryIdAndStatusInAndOwnerIn(eq(FACTORY), eq(expected), eq(List.of(1555L)), any(Pageable.class));
    }
}
