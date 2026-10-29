package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
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
 * 线边仓算; 成品 / 研发 / 质检 / 退货 / 报废仓即使被配成默认仓也不算; 停用的仓不算。
 */
class ProductionPickingWarehousesContractTest {

    private final FactoryWarehouseRepository warehouses = mock(FactoryWarehouseRepository.class);
    private final FactoryWarehouseDefaultRepository defaults = mock(FactoryWarehouseDefaultRepository.class);
    private final WarehouseResolver resolver = new WarehouseResolver(warehouses, defaults);

    private final Map<String, FactoryWarehouse> byId = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        for (FactoryWarehouse w : List.of(
                wh("fg", "WH-FG", WarehouseType.FINISHED), wh("line", "WH-LINE", WarehouseType.LINESIDE),
                wh("log", "WH-LOG", WarehouseType.LOGISTICS), wh("qc", "WH-QC", WarehouseType.QC),
                wh("raw", "WH-RAW", WarehouseType.RAW), wh("rd", "WH-RD", WarehouseType.RD),
                wh("ret", "WH-RET", WarehouseType.RETURNS), wh("scr", "WH-SCR", WarehouseType.SCRAP),
                wh("wks", "WH-WKS", WarehouseType.WORKSHOP))) {
            byId.put(w.getId(), w);
        }
        when(defaults.findByFactoryIdAndPurposeAndDeletedAtIsNull(eq("F1"), any())).thenReturn(Optional.empty());
        when(warehouses.findByFactoryIdAndCodeAndDeletedAtIsNull(eq("F1"), anyString()))
                .thenAnswer(i -> byId.values().stream().filter(w -> w.getCode().equals(i.getArgument(1))).findFirst());
        when(warehouses.findByIdAndFactoryIdAndDeletedAtIsNull(anyString(), eq("F1")))
                .thenAnswer(i -> Optional.ofNullable(byId.get((String) i.getArgument(0))));
        when(warehouses.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1"))
                .thenAnswer(i -> List.copyOf(byId.values()));
    }

    @Test
    void rawLogisticsLinesideAndWorkshopCountTheRestDoNot() {
        List<String> ids = resolver.productionPickingWarehouseIds("F1");

        assertThat(ids.get(0)).isEqualTo("wks");                       // 车间余料先用
        assertThat(ids).containsExactlyInAnyOrder("wks", "log", "raw", "line");
    }

    @ParameterizedTest
    @EnumSource(value = WarehouseType.class, names = {"QC", "RETURNS", "SCRAP", "FINISHED", "RD"})
    void aConfiguredDefaultOfANeverPickableTypeIsStillExcluded(WarehouseType type) {
        String id = byId.values().stream().filter(w -> w.getType() == type).findFirst().orElseThrow().getId();
        configure(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT, id);   // 比如把采购入库默认仓配成待检仓

        assertThat(resolver.productionPickingWarehouseIds("F1")).doesNotContain(id).contains("raw");
    }

    @Test
    void aConfiguredPurchaseInboundWarehouseOfAPickableKindIsIncluded() {
        byId.put("other", wh("other", "WH-OTHER", WarehouseType.OTHER));   // 本身不在可领料类型里
        configure(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT, "other");

        assertThat(resolver.productionPickingWarehouseIds("F1")).contains("other");
    }

    @Test
    void anInactiveWarehouseIsSkippedEvenWhenItIsADefault() {
        byId.get("raw").setIsActive(false);
        configure(WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT, "raw");

        assertThat(resolver.productionPickingWarehouseIds("F1")).doesNotContain("raw").contains("log");
    }

    private void configure(WarehouseDefaultPurpose purpose, String warehouseId) {
        FactoryWarehouseDefault d = new FactoryWarehouseDefault();
        d.setWarehouseId(warehouseId);
        when(defaults.findByFactoryIdAndPurposeAndDeletedAtIsNull("F1", purpose)).thenReturn(Optional.of(d));
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
