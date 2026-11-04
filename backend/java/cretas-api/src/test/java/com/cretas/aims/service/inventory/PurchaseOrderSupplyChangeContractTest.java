package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.event.PurchaseOrderSupplyChangedEvent;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 采购单进出「在途」口径时必须发事件, 否则缺料实例的在途一直是旧数 (D-S411-01: 手动核对才 40 -> 168)。
 */
class PurchaseOrderSupplyChangeContractTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java");

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository items = mock(PurchaseOrderItemRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orders, items, null, null, null, null, null, null, publisher, null);
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void order(PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("PO-1");
        order.setFactoryId("F1");
        order.setStatus(status);
        when(orders.findById("PO-1")).thenReturn(Optional.of(order));
    }

    private static PurchaseOrderItem item(String materialTypeId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterialTypeId(materialTypeId);
        return item;
    }

    @Test
    void cancellingAnOpenOrderAnnouncesEachDistinctMaterialOnce() {
        order(PurchaseOrderStatus.SUBMITTED);
        when(items.findByPurchaseOrderId("PO-1")).thenReturn(Arrays.asList(item("M1"), item("M1"), item("M2"), item(null)));

        service.cancelOrder("F1", "PO-1");

        ArgumentCaptor<PurchaseOrderSupplyChangedEvent> event =
                ArgumentCaptor.forClass(PurchaseOrderSupplyChangedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().getFactoryId()).isEqualTo("F1");
        assertThat(event.getValue().getPurchaseOrderId()).isEqualTo("PO-1");
        assertThat(event.getValue().getMaterialTypeIds()).isEqualTo(Set.of("M1", "M2"));
    }

    @Test
    void anOrderWithNoMaterialAnnouncesNothing() {
        order(PurchaseOrderStatus.SUBMITTED);
        when(items.findByPurchaseOrderId("PO-1")).thenReturn(List.of(item(null)));

        service.cancelOrder("F1", "PO-1");

        verifyNoInteractions(publisher);
    }

    /**
     * 新增状态流转要在这里过一遍: 进出在途口径的走 saveAndAnnounceSupplyChange; 其余裸保存是草稿写入 / 改价 /
     * 口径内部流转 (转待财务复核) / 收完转完成, 收货引起的变化由 MaterialReceivedEvent 负责。
     */
    @Test
    void everyOrderSaveIsAccountedFor() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertThat(count(source, "saveAndAnnounceSupplyChange\\(order\\)")).isEqualTo(10);
        assertThat(count(source, "purchaseOrderRepository\\.save\\((order|newOrder)\\)")).isEqualTo(11);
    }

    private static long count(String source, String regex) {
        return Pattern.compile(regex).matcher(source).results().count();
    }
}
