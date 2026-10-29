package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺料核对算「可用库存」的仓 = 生产能领到料的仓 (2026-10-29 验收 D-S3S4-01):
 * 采购默认收进原料仓 (WH-RAW) 时, 原料仓必须在范围里, 否则到货永远不抵缺口;
 * 成品仓 / 研发仓不是生产领料来源, 不算。
 */
class ProductionPickingWarehousesContractTest {

    private final FactoryWarehouseRepository warehouses = mock(FactoryWarehouseRepository.class);
    private final FactoryWarehouseDefaultRepository defaults = mock(FactoryWarehouseDefaultRepository.class);
    private final WarehouseResolver resolver = new WarehouseResolver(warehouses, defaults);

    private final Map<String, FactoryWarehouse> byId = Map.of(
            "wks", wh("wks", "WH-WKS", WarehouseType.WORKSHOP),
            "log", wh("log", "WH-LOG", WarehouseType.LOGISTICS),
            "raw", wh("raw", "WH-RAW", WarehouseType.RAW),
            "fg", wh("fg", "WH-FG", WarehouseType.FINISHED),
            "rd", wh("rd", "WH-RD", WarehouseType.RD));

    @BeforeEach
    void setUp() {
        when(defaults.findByFactoryIdAndPurposeAndDeletedAtIsNull(eq("F1"), any())).thenReturn(Optional.empty());
        when(warehouses.findByFactoryIdAndCodeAndDeletedAtIsNull(eq("F1"), anyString()))
                .thenAnswer(i -> byId.values().stream().filter(w -> w.getCode().equals(i.getArgument(1))).findFirst());
        when(warehouses.findByIdAndFactoryIdAndDeletedAtIsNull(anyString(), eq("F1")))
                .thenAnswer(i -> Optional.ofNullable(byId.get((String) i.getArgument(0))));
        when(warehouses.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1"))
                .thenReturn(List.of(byId.get("fg"), byId.get("log"), byId.get("raw"), byId.get("rd"), byId.get("wks")));
    }

    @Test
    void theRawWarehouseCountsAndFinishedGoodsAndRdDoNot() {
        List<String> ids = resolver.productionPickingWarehouseIds("F1");

        assertThat(ids.get(0)).isEqualTo("wks");                       // 车间余料先用
        assertThat(ids).contains("log", "raw").doesNotContain("fg", "rd").doesNotHaveDuplicates();
    }

    @Test
    void aConfiguredPurchaseInboundWarehouseIsIncludedEvenIfItsTypeIsNotARawType() {
        FactoryWarehouseDefault d = new FactoryWarehouseDefault();
        d.setWarehouseId("fg");   // 极端配置: 采购收进一个类型不是原料类的仓 —— 领料单来源仓就是它, 照样算
        when(defaults.findByFactoryIdAndPurposeAndDeletedAtIsNull("F1", WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT))
                .thenReturn(Optional.of(d));

        assertThat(resolver.productionPickingWarehouseIds("F1")).contains("fg").doesNotContain("rd");
    }

    private static FactoryWarehouse wh(String id, String code, WarehouseType type) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setFactoryId("F1");
        w.setCode(code);
        w.setName(code);
        w.setType(type);
        w.setIsActive(true);
        return w;
    }
}
