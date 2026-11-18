package com.cretas.aims.controller.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.inventory.SalesService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SalesFinishedGoodsWarehouseNameContractTest {

    private FinishedGoodsBatch batch(String id, String warehouseId) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setWarehouseId(warehouseId);
        b.setProducedQuantity(BigDecimal.TEN);
        return b;
    }

    @Test
    void listFillsWarehouseNameAndLeavesUnknownNull() {
        SalesService salesService = mock(SalesService.class);
        SalesController c = new SalesController(salesService, null, null, null, null, null, null, null,
                null, null, null, null);
        FactoryWarehouseRepository repo = mock(FactoryWarehouseRepository.class);
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId("W1");
        w.setName("成品冷库");
        when(repo.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1")).thenReturn(List.of(w));
        ReflectionTestUtils.setField(c, "factoryWarehouseRepository", repo);
        when(salesService.getFinishedGoodsBatches("F1", 1, 20)).thenReturn(
                PageResponse.of(List.of(batch("a", "W1"), batch("b", "GONE")), 1, 20, 2L));

        List<FinishedGoodsBatch> out = c.listFinishedGoods("F1", 1, 20).getData().getContent();

        assertThat(out.get(0).getWarehouseName()).isEqualTo("成品冷库");
        assertThat(out.get(1).getWarehouseName()).isNull();
    }
}
