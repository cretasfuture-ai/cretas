package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mapping and gap-only rules for the default purposes a blueprint apply writes. The real
 * {@link WarehouseResolver} runs over in-memory repositories, so "already routed" means exactly
 * what the business paths would resolve.
 */
class BlueprintWarehouseDefaultsContractTest {

    private static final String F = "F-BP";

    private final List<FactoryWarehouse> warehouses = new ArrayList<>();
    private final List<FactoryWarehouseDefault> rows = new ArrayList<>();
    private BlueprintWarehouseDefaultsWriter writer;

    @BeforeEach void setup() {
        FactoryWarehouseRepository warehouseRepository = mock(FactoryWarehouseRepository.class);
        when(warehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(any(), any())).thenAnswer(call ->
                warehouses.stream().filter(w -> w.getFactoryId().equals(call.getArgument(0))
                        && w.getCode().equals(call.getArgument(1)) && w.getDeletedAt() == null).findFirst());
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(any(), any())).thenAnswer(call ->
                warehouses.stream().filter(w -> w.getId().equals(call.getArgument(0))
                        && w.getFactoryId().equals(call.getArgument(1)) && w.getDeletedAt() == null).findFirst());
        FactoryWarehouseDefaultRepository defaultRepository = mock(FactoryWarehouseDefaultRepository.class);
        when(defaultRepository.findByFactoryIdAndPurposeAndDeletedAtIsNull(any(), any())).thenAnswer(call ->
                rows.stream().filter(r -> r.getFactoryId().equals(call.getArgument(0))
                        && r.getPurpose() == call.getArgument(1) && r.getDeletedAt() == null).findFirst());
        when(defaultRepository.saveAndFlush(any())).thenAnswer(call -> {
            FactoryWarehouseDefault row = call.getArgument(0);
            row.setId(UUID.randomUUID().toString());
            rows.add(row);
            return row;
        });
        writer = new BlueprintWarehouseDefaultsWriter(warehouseRepository, defaultRepository,
                new WarehouseResolver(warehouseRepository, defaultRepository));
    }

    @Test void onlyPurposesWithABasisAreMappedAndLogisticsAndRdAreLeftAlone() {
        assertThat(BlueprintWarehouseDefaultsWriter.PURPOSE_TYPES).containsExactly(
                entry(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT, WarehouseType.RAW),
                entry(WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT, WarehouseType.RAW),
                entry(WarehouseDefaultPurpose.WORKSHOP_DEFAULT, WarehouseType.WORKSHOP),
                entry(WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT, WarehouseType.FINISHED));
        assertThat(BlueprintWarehouseDefaultsWriter.PURPOSE_TYPES)
                .doesNotContainKeys(WarehouseDefaultPurpose.LOGISTICS_DEFAULT, WarehouseDefaultPurpose.RD_DEFAULT);
    }

    @Test void blueprintWarehousesGetEveryMappedRouteAndReapplyWritesNothing() {
        warehouse("WH-RAW", WarehouseType.RAW, true);
        warehouse("WH-WKS2", WarehouseType.WORKSHOP, true);
        warehouse("WH-WIP", WarehouseType.WIP, true);
        warehouse("WH-FG", WarehouseType.FINISHED, true);
        List<String> codes = List.of("WH-RAW", "WH-WKS2", "WH-WIP", "WH-FG");

        ApplyAIInitResult first = new ApplyAIInitResult();
        writer.write(F, codes, first);
        assertThat(first.getWrittenWarehouseDefaults()).containsExactly(
                "PURCHASE_INBOUND_DEFAULT→WH-RAW", "PRODUCTION_RAW_DEFAULT→WH-RAW",
                "WORKSHOP_DEFAULT→WH-WKS2", "SALES_OUTBOUND_DEFAULT→WH-FG");
        assertThat(first.getSkippedWarehouseDefaults()).isEmpty();

        ApplyAIInitResult again = new ApplyAIInitResult();
        writer.write(F, codes, again);
        assertThat(again.getWrittenWarehouseDefaults()).isEmpty();
        assertThat(again.getSkippedWarehouseDefaults()).hasSize(4).allMatch(s -> s.endsWith("(已有配置)"));
        assertThat(rows).hasSize(4);
    }

    @Test void wipIsNotTreatedAsTheProductionWarehouse() {
        warehouse("WH-RAW", WarehouseType.RAW, true);
        warehouse("WH-WIP", WarehouseType.WIP, true);
        warehouse("WH-FG", WarehouseType.FINISHED, true);

        ApplyAIInitResult result = new ApplyAIInitResult();
        writer.write(F, List.of("WH-RAW", "WH-WIP", "WH-FG"), result);

        assertThat(result.getWrittenWarehouseDefaults()).containsExactly(
                "PURCHASE_INBOUND_DEFAULT→WH-RAW", "PRODUCTION_RAW_DEFAULT→WH-RAW", "SALES_OUTBOUND_DEFAULT→WH-FG");
        assertThat(result.getSkippedWarehouseDefaults()).containsExactly("WORKSHOP_DEFAULT(蓝图没有启用的 WORKSHOP 仓)");
        assertThat(rows).noneMatch(r -> r.getPurpose() == WarehouseDefaultPurpose.WORKSHOP_DEFAULT);
    }

    @Test void savedDefaultIsNeverOverwrittenEvenWhenItsWarehouseWasDeleted() {
        FactoryWarehouse gone = warehouse("WH-OLD", WarehouseType.FINISHED, true);
        gone.setDeletedAt(LocalDateTime.now());
        FactoryWarehouseDefault saved = new FactoryWarehouseDefault();
        saved.setId("ADMIN");
        saved.setFactoryId(F);
        saved.setPurpose(WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT);
        saved.setWarehouseId(gone.getId());
        rows.add(saved);
        warehouse("WH-FG", WarehouseType.FINISHED, true);

        ApplyAIInitResult result = new ApplyAIInitResult();
        writer.write(F, List.of("WH-FG"), result);

        assertThat(result.getSkippedWarehouseDefaults()).contains("SALES_OUTBOUND_DEFAULT(已有配置)");
        assertThat(rows).filteredOn(r -> r.getPurpose() == WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT)
                .singleElement().satisfies(r -> assertThat(r.getWarehouseId()).isEqualTo(gone.getId()));
    }

    @Test void aFactoryThatAlreadyRoutesThroughItsSystemWarehouseKeepsThatRoute() {
        warehouse("WH-LOG", WarehouseType.LOGISTICS, true);
        warehouse("WH-RAW", WarehouseType.RAW, true);

        ApplyAIInitResult result = new ApplyAIInitResult();
        writer.write(F, List.of("WH-RAW"), result);

        assertThat(result.getWrittenWarehouseDefaults()).isEmpty();
        assertThat(result.getSkippedWarehouseDefaults()).contains(
                "PURCHASE_INBOUND_DEFAULT(已可用: WH-LOG)", "PRODUCTION_RAW_DEFAULT(已可用: WH-LOG)",
                "SALES_OUTBOUND_DEFAULT(已可用: WH-LOG)");
        assertThat(rows).isEmpty();
    }

    @Test void twoWarehousesOfTheSameTypeAreNotPickedForTheUser() {
        warehouse("WH-RAW-A", WarehouseType.RAW, true);
        warehouse("WH-RAW-B", WarehouseType.RAW, true);

        ApplyAIInitResult result = new ApplyAIInitResult();
        writer.write(F, List.of("WH-RAW-A", "WH-RAW-B"), result);

        assertThat(result.getSkippedWarehouseDefaults()).contains(
                "PURCHASE_INBOUND_DEFAULT(蓝图有 2 个 RAW 仓, 不替用户挑)",
                "PRODUCTION_RAW_DEFAULT(蓝图有 2 个 RAW 仓, 不替用户挑)");
        assertThat(rows).isEmpty();
    }

    @Test void onlyActiveWarehousesTheBlueprintNamedAreCandidates() {
        warehouse("WH-RAW", WarehouseType.RAW, false);
        warehouse("WH-FG-UNNAMED", WarehouseType.FINISHED, true);

        ApplyAIInitResult result = new ApplyAIInitResult();
        writer.write(F, List.of("WH-RAW"), result);

        assertThat(result.getWrittenWarehouseDefaults()).isEmpty();
        assertThat(result.getSkippedWarehouseDefaults()).contains(
                "PURCHASE_INBOUND_DEFAULT(蓝图没有启用的 RAW 仓)", "SALES_OUTBOUND_DEFAULT(蓝图没有启用的 FINISHED 仓)");
        assertThat(rows).isEmpty();
    }

    private FactoryWarehouse warehouse(String code, WarehouseType type, boolean active) {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("ID-" + code);
        warehouse.setFactoryId(F);
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setType(type);
        warehouse.setIsActive(active);
        warehouses.add(warehouse);
        return warehouse;
    }
}
