package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4: 产能只认与订单同单位的产线配置 (不换算、不套默认), 设备只认显式配置; 三态 READY / MISSING / CONFLICT。
 */
class LineCapacityEquipmentCheckContractTest {

    private static final String F = "F-S4";
    private static final String PT = "PT-1";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 3, 8, 0);
    private static final LocalDateTime END = START.plusHours(2);

    private ProductionLineCapacityConfigRepository configs;
    private ProductEquipmentRequirementRepository requirements;
    private ProductionLineRepository lines;
    private EquipmentRepository equipment;
    private LineCapacityEquipmentCheck check;
    private final List<ProductionLine> lineRows = new ArrayList<>();
    private final List<ProductionLineCapacityConfig> configRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        configs = mock(ProductionLineCapacityConfigRepository.class);
        requirements = mock(ProductEquipmentRequirementRepository.class);
        lines = mock(ProductionLineRepository.class);
        equipment = mock(EquipmentRepository.class);
        when(lines.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(lineRows);
        when(configs.findEffectiveForProduct(eq(F), eq(PT), any())).thenReturn(configRows);
        check = new LineCapacityEquipmentCheck(configs, requirements, lines, equipment);
    }

    private void line(String id, String name, ProductionLine.LineStatus status) {
        ProductionLine l = new ProductionLine();
        l.setId(id);
        l.setFactoryId(F);
        l.setName(name);
        l.setStatus(status);
        lineRows.add(l);
    }

    private void config(String lineId, String productTypeId, String perHour, CapacityUnit unit) {
        ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
        c.setFactoryId(F);
        c.setProductionLineId(lineId);
        c.setProductTypeId(productTypeId);
        c.setCapacityPerHour(new BigDecimal(perHour));
        c.setCapacityUnit(unit);
        configRows.add(c);
    }

    private ResourceCheckResult capacity(String qty, String unit, String preferredLine) {
        ResourceCheckResult r = check.checkCapacity(F, PT, new BigDecimal(qty), unit, preferredLine, START, END);
        System.out.println("CAPACITY " + qty + " " + unit + " -> " + LineCapacityEquipmentCheck.status(r) + " | " + r.getMessage());
        return r;
    }

    // ---------------------------------------------------------------- capacity

    @Test
    void noUnitOnTheOrderIsMissingEvenWhenLinesAreConfigured() {
        line("L1", "卤制线", ProductionLine.LineStatus.active);
        config("L1", null, "100", CapacityUnit.KG);

        assertThat(LineCapacityEquipmentCheck.status(capacity("50", null, null))).isEqualTo("MISSING");
        assertThat(LineCapacityEquipmentCheck.status(capacity("50", "箱", null))).isEqualTo("MISSING");
    }

    @Test
    void noConfigAtAllIsMissingNotTheOld500KgDefault() {
        line("L1", "卤制线", ProductionLine.LineStatus.active);

        ResourceCheckResult r = capacity("50", "KG", null);

        assertThat(LineCapacityEquipmentCheck.status(r)).isEqualTo("MISSING");
        assertThat(r.getMessage()).startsWith("产能未验证").contains("kg");
    }

    @Test
    void aLineConfiguredInAnotherUnitIsMissingAndSaysWhichUnitItHas() {
        line("L1", "卤制线", ProductionLine.LineStatus.active);
        config("L1", null, "100", CapacityUnit.KG);

        ResourceCheckResult r = capacity("50", "PIECE", null);

        assertThat(LineCapacityEquipmentCheck.status(r)).isEqualTo("MISSING");
        assertThat(r.getMessage()).contains("卤制线 配的是 kg/小时");
    }

    @Test
    void sameUnitWithinTheSlotIsReadyAndBeyondItIsConflict() {
        line("L1", "包装线", ProductionLine.LineStatus.active);
        config("L1", null, "100", CapacityUnit.PIECE);   // 2 小时 = 200 件

        assertThat(LineCapacityEquipmentCheck.status(capacity("200", "piece", null))).isEqualTo("READY");
        ResourceCheckResult over = capacity("201", "PIECE", null);
        assertThat(LineCapacityEquipmentCheck.status(over)).isEqualTo("CONFLICT");
        assertThat(over.getMessage()).contains("需要 201 件").contains("最多 200 件").contains("包装线");
    }

    @Test
    void theOrderRunsOnOneLineSoTheBestSingleLineCountsNotTheSum() {
        line("L1", "一号线", ProductionLine.LineStatus.active);
        line("L2", "二号线", ProductionLine.LineStatus.active);
        config("L1", null, "60", CapacityUnit.KG);
        config("L2", null, "80", CapacityUnit.KG);   // 单线最多 160 kg, 两线相加 280 不算

        assertThat(LineCapacityEquipmentCheck.status(capacity("160", "KG", null))).isEqualTo("READY");
        assertThat(LineCapacityEquipmentCheck.status(capacity("200", "KG", null))).isEqualTo("CONFLICT");
    }

    @Test
    void aProductSpecificRowOverridesTheLinesGenericRow() {
        line("L1", "卤制线", ProductionLine.LineStatus.active);
        config("L1", null, "1000", CapacityUnit.BATCH);
        config("L1", PT, "1", CapacityUnit.BATCH);   // 这个产品每小时只能 1 批

        assertThat(LineCapacityEquipmentCheck.status(capacity("2", "BATCH", null))).isEqualTo("READY");
        assertThat(LineCapacityEquipmentCheck.status(capacity("3", "BATCH", null))).isEqualTo("CONFLICT");
    }

    @Test
    void aPreferredLineOnlyCountsThatLine() {
        line("L1", "一号线", ProductionLine.LineStatus.active);
        line("L2", "二号线", ProductionLine.LineStatus.active);
        config("L1", null, "10", CapacityUnit.KG);
        config("L2", null, "100", CapacityUnit.KG);

        assertThat(LineCapacityEquipmentCheck.status(capacity("100", "KG", "L1"))).isEqualTo("CONFLICT");
        assertThat(LineCapacityEquipmentCheck.status(capacity("100", "KG", "L2"))).isEqualTo("READY");
        assertThat(LineCapacityEquipmentCheck.status(capacity("100", "KG", "L9"))).isEqualTo("MISSING");
    }

    @Test
    void onlyLinesUnderMaintenanceIsConflictAndADeletedLineDoesNotCount() {
        line("L1", "一号线", ProductionLine.LineStatus.maintenance);
        config("L1", null, "100", CapacityUnit.KG);
        config("L-DELETED", null, "100", CapacityUnit.KG);   // 产线已删, 不在 lineRows

        ResourceCheckResult r = capacity("10", "KG", null);

        assertThat(LineCapacityEquipmentCheck.status(r)).isEqualTo("CONFLICT");
        assertThat(r.getMessage()).contains("一号线 (maintenance)");
    }

    // --------------------------------------------------------------- equipment

    private FactoryEquipment device(long id, String factoryId, String status) {
        FactoryEquipment e = new FactoryEquipment();
        e.setId(id);
        e.setFactoryId(factoryId);
        e.setEquipmentName("夹层锅" + id);
        e.setEquipmentCode("EQ-" + id);
        e.setStatus(status);
        return e;
    }

    private void requires(Long... ids) {
        List<ProductEquipmentRequirement> rows = new ArrayList<>();
        for (Long id : ids) {
            ProductEquipmentRequirement r = new ProductEquipmentRequirement();
            r.setFactoryId(F);
            r.setProductTypeId(PT);
            r.setEquipmentId(id);
            rows.add(r);
        }
        when(requirements.findByFactoryIdAndProductTypeId(F, PT)).thenReturn(rows);
    }

    private String equipmentStatus(FactoryEquipment... found) {
        when(equipment.findByIdIn(anyCollection())).thenReturn(List.of(found));
        ResourceCheckResult r = check.checkEquipment(F, PT);
        System.out.println("EQUIPMENT -> " + LineCapacityEquipmentCheck.status(r) + " | " + r.getMessage());
        return LineCapacityEquipmentCheck.status(r);
    }

    @Test
    void noRequirementConfiguredIsMissing() {
        requires();
        assertThat(equipmentStatus()).isEqualTo("MISSING");
    }

    @Test
    void configuredDevicesUpAreReadyAndOneUnderMaintenanceIsConflict() {
        requires(1L, 2L);
        assertThat(equipmentStatus(device(1, F, "idle"), device(2, F, "running"))).isEqualTo("READY");
        assertThat(equipmentStatus(device(1, F, "idle"), device(2, F, "maintenance"))).isEqualTo("CONFLICT");
        assertThat(equipmentStatus(device(1, F, "scrapped"), device(2, F, "running"))).isEqualTo("CONFLICT");
    }

    @Test
    void anotherFactorysDeviceOrAnUnknownStatusIsMissing() {
        requires(1L, 2L);
        assertThat(equipmentStatus(device(1, F, "idle"), device(2, "F-OTHER", "idle"))).isEqualTo("MISSING");
        assertThat(equipmentStatus(device(1, F, "idle"), device(2, F, null))).isEqualTo("MISSING");
    }
}
