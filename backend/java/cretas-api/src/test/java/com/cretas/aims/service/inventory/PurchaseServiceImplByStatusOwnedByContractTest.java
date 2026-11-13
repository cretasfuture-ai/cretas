package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收货页按状态列采购单在受限数据范围下的服务层: 归属集合非空 → 只走按创建人过滤的查询并原样传入集合;
 * 空 → 空页且不查库; null 不允许 (全工厂走原 getPurchaseOrdersByStatus, 不能借这里变成不过滤)。
 */
class PurchaseServiceImplByStatusOwnedByContractTest {
    private static final String FACTORY = "F006";
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orders, mock(PurchaseOrderItemRepository.class),
                mock(PurchaseReceiveRecordRepository.class), mock(SupplierRepository.class),
                null, null, null, null, null, null);
    }

    @Test
    void ownersAreBoundIntoTheStatusQuery() {
        PurchaseOrder mine = new PurchaseOrder();
        mine.setId("PO-MINE");
        when(orders.findByFactoryIdAndStatusAndCreatedByInOrderByCreatedAtDesc(eq(FACTORY),
                eq(PurchaseOrderStatus.PARTIAL_RECEIVED), eq(List.of(1555L, 1560L)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mine)));

        PageResponse<PurchaseOrder> page = service.getPurchaseOrdersByStatusOwnedBy(
                FACTORY, PurchaseOrderStatus.PARTIAL_RECEIVED, List.of(1555L, 1560L), 1, 20);

        assertThat(page.getContent()).extracting(PurchaseOrder::getId).containsExactly("PO-MINE");
        assertThat(page.getTotalElements()).isEqualTo(1);
        verify(orders, never()).findByFactoryIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void emptyOwnerSetReturnsEmptyPageWithoutQuerying() {
        PageResponse<PurchaseOrder> page = service.getPurchaseOrdersByStatusOwnedBy(
                FACTORY, PurchaseOrderStatus.FINANCE_APPROVED, List.of(), 1, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(orders);
    }

    @Test
    void nullOwnersIsRejectedInsteadOfListingTheWholeFactory() {
        assertThatThrownBy(() -> service.getPurchaseOrdersByStatusOwnedBy(
                FACTORY, PurchaseOrderStatus.FINANCE_APPROVED, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders);
    }
}
