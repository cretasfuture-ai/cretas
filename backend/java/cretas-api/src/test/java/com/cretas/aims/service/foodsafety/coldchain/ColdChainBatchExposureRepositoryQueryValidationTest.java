package com.cretas.aims.service.foodsafety.coldchain;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.foodsafety.ColdChainTempReading;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.foodsafety.ColdChainBatchStayRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentLocationRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.foodsafety.ColdChainExcursionHoldRepository;
import com.cretas.aims.repository.foodsafety.ColdChainTempReadingRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AffectedBatch;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AffectedReport;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AssignCommand;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.HoldCommand;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.HoldOutcome;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.HoldTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 食品 DAG 缺口 15「温度关联批次」—— 清单场景, 在真实 Hibernate + H2 上从告警一路算到受影响批次再转质检。
 *
 * <p>冷库 (0~8℃) 10-20 02:00–05:00 超温 (12℃), 告警 02:15:
 * <ul>
 *   <li>A: 前一天入库, 01:00 领用完 → 不受影响</li>
 *   <li>B: 前一天入库, 一直在库 → 暴露 180 分钟</li>
 *   <li>C: 04:30 入库 → 30 分钟</li>
 *   <li>D: 06:00 入库 (超温之后) → 不受影响</li>
 *   <li>U: 前一天入库, 已出完但找不到出库流水, 最后修改 06:00 → 在库时间不确定, 最多 180 分钟</li>
 *   <li>E: 在另一个库 → 不受影响 (阴性对照: 不是全厂批次都列)</li>
 *   <li>G: 前一天入库, 02:30 领用完 (按领用流水定出库时刻) → 30 分钟</li>
 *   <li>成品 F: 03:00 入库 → 120 分钟</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("温度关联批次: 超温 02:00–05:00 → 受影响批次 / 暴露时长 / 转质检")
class ColdChainBatchExposureRepositoryQueryValidationTest {

    private static final String F = "F-CCX";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final long USER = 9L;

    @Autowired private ColdChainEquipmentRepository equipmentRepo;
    @Autowired private ColdChainEquipmentLocationRepository locationRepo;
    @Autowired private ColdChainAlertRepository alertRepo;
    @Autowired private ColdChainTempReadingRepository readingRepo;
    @Autowired private ColdChainExcursionHoldRepository holdRepo;
    @Autowired private ColdChainBatchStayRepository stayRepo;
    @Autowired private FactoryWarehouseRepository warehouseRepo;
    @Autowired private MaterialBatchRepository materialBatchRepo;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsRepo;
    @Autowired private RawMaterialTypeRepository rawMaterialTypeRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestEntityManager em;

    private ColdChainBatchExposureService service;
    private Long equipmentId;
    private Long alertId;
    private String cold;
    private String other;

    private static LocalDateTime at(int dayOffset, int h, int m) {
        return DAY.plusDays(dayOffset).atTime(h, m);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        service = new ColdChainBatchExposureService(equipmentRepo, locationRepo, alertRepo, readingRepo, holdRepo,
                stayRepo, warehouseRepo, materialBatchRepo, finishedGoodsRepo, rawMaterialTypeRepo, productTypeRepo);
        cold = warehouse("WH-COLD", "冷库");
        other = warehouse("WH-DRY", "常温库");

        ColdChainEquipment eq = equipmentRepo.saveAndFlush(ColdChainEquipment.builder()
                .factoryId(F).equipmentCode("COLD-01").equipmentName("1号冷库").type("COLD_STORAGE")
                .targetTempMin(new BigDecimal("0")).targetTempMax(new BigDecimal("8")).build());
        equipmentId = eq.getId();
        // 读数: 00:00–07:00 每 5 分钟; 02:00–05:00 (含两端) 12℃, 其余 4℃
        for (LocalDateTime t = at(0, 0, 0); !t.isAfter(at(0, 7, 0)); t = t.plusMinutes(5)) {
            boolean hot = !t.isBefore(at(0, 2, 0)) && !t.isAfter(at(0, 5, 0));
            readingRepo.save(ColdChainTempReading.builder().factoryId(F).equipmentId(equipmentId)
                    .recordedAt(t).temperature(new BigDecimal(hot ? "12.0" : "4.0")).build());
        }
        alertId = alertRepo.saveAndFlush(ColdChainAlert.builder().factoryId(F).equipmentId(equipmentId)
                .alertTime(at(0, 2, 15)).severity("WARNING").minTemp(new BigDecimal("12"))
                .maxTemp(new BigDecimal("12")).durationMinutes(15).status("OPEN").build()).getId();

        material("A", cold, at(-1, 10, 0), "10", "10", MaterialBatchStatus.USED_UP, at(0, 1, 0));
        consumption("A", at(0, 1, 0));
        material("B", cold, at(-1, 10, 0), "10", "0", MaterialBatchStatus.AVAILABLE, at(-1, 10, 0));
        material("C", cold, at(0, 4, 30), "10", "0", MaterialBatchStatus.AVAILABLE, at(0, 4, 30));
        material("D", cold, at(0, 6, 0), "10", "0", MaterialBatchStatus.AVAILABLE, at(0, 6, 0));
        material("U", cold, at(-1, 10, 0), "10", "10", MaterialBatchStatus.USED_UP, at(0, 6, 0));
        material("G", cold, at(-1, 10, 0), "10", "10", MaterialBatchStatus.USED_UP, at(0, 2, 30));
        consumption("G", at(0, 2, 30));
        material("E", other, at(-1, 10, 0), "10", "0", MaterialBatchStatus.AVAILABLE, at(-1, 10, 0));
        finished("FG-F", cold, at(0, 3, 0));
        em.clear();
    }

    private void placeEquipment(String warehouseId, LocalDateTime from) {
        service.assignLocation(F, equipmentId, new AssignCommand(warehouseId, from, null), USER);
    }

    private Map<String, AffectedBatch> byNumber(AffectedReport r) {
        return r.batches().stream().collect(Collectors.toMap(AffectedBatch::batchNumber, Function.identity()));
    }

    @Test
    @DisplayName("清单场景: B 180 分钟, C 30 分钟, A / D / 别的库 E 不在表里, 成品 F 120 分钟, G 按领用流水 30 分钟, U 在库时间不确定")
    void listedScenario() {
        placeEquipment(cold, at(-10, 0, 0));

        AffectedReport r = service.affectedBatches(F, alertId);
        System.out.println("CCX_SCENARIO excursion=" + r.excursion() + " rows=" + r.batches().size()
                + " notAffected=" + r.notAffectedCount());

        assertThat(r.excursion().start()).isEqualTo(at(0, 2, 0));
        assertThat(r.excursion().end()).isEqualTo(at(0, 5, 0));
        assertThat(r.excursion().minutes()).isEqualTo(180);

        Map<String, AffectedBatch> rows = byNumber(r);
        assertThat(rows).containsOnlyKeys("BN-B", "BN-C", "BN-G", "BN-U", "BN-FG-F");
        assertThat(rows.get("BN-B").verdict()).isEqualTo("AFFECTED");
        assertThat(rows.get("BN-B").exposureMinutes()).isEqualTo(180L);
        assertThat(rows.get("BN-C").exposureMinutes()).isEqualTo(30L);
        assertThat(rows.get("BN-G").verdict()).isEqualTo("AFFECTED");
        assertThat(rows.get("BN-G").exposureMinutes()).isEqualTo(30L);
        assertThat(rows.get("BN-G").inStockUntil()).isEqualTo("2026-10-20 02:30");
        assertThat(rows.get("BN-FG-F").exposureMinutes()).isEqualTo(120L);
        assertThat(rows.get("BN-FG-F").targetType()).isEqualTo("FINISHED_GOODS");
        assertThat(rows.get("BN-B").targetType()).isEqualTo("RAW_MATERIAL_BATCH");

        AffectedBatch u = rows.get("BN-U");
        assertThat(u.verdict()).isEqualTo("UNCERTAIN");
        assertThat(u.exposureMinutes()).isNull();
        assertThat(u.maxExposureMinutes()).isEqualTo(180L);
        assertThat(u.uncertainReason()).contains("没有找到出库流水");
        // A 超温前已出完、D 登记在超温之后、E 在别的库: 候选查询阶段就排除, 不进表
        assertThat(r.notAffectedCount()).isZero();
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("设备 03:30 从冷库搬到常温库: 冷库的 B 只暴露 90 分钟, 常温库的 E 暴露后 90 分钟; 历史时段不被改写")
    void equipmentMovedMidExcursion() {
        placeEquipment(cold, at(-10, 0, 0));
        placeEquipment(other, at(0, 3, 30));

        AffectedReport r = service.affectedBatches(F, alertId);
        Map<String, AffectedBatch> rows = byNumber(r);
        assertThat(rows.get("BN-B").exposureMinutes()).isEqualTo(90L);
        assertThat(rows.get("BN-E").exposureMinutes()).isEqualTo(90L);
        assertThat(rows).doesNotContainKey("BN-C");   // C 04:30 进冷库, 那时设备已经不在冷库
        assertThat(r.segments()).hasSize(2);

        // 再往回登记一段 (03:00) 等于改写历史 → 拒绝
        assertThatThrownBy(() -> placeEquipment(cold, at(0, 3, 0)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("历史不能改写");
    }

    @Test
    @DisplayName("设备没登记库位: 不报「没有受影响批次」, 而是明说列不出")
    void noLocationIsNotZeroAffected() {
        AffectedReport r = service.affectedBatches(F, alertId);
        assertThat(r.batches()).isEmpty();
        assertThat(r.unlocatedPeriods()).hasSize(1);
        assertThat(r.note()).contains("没有登记所在仓库");
    }

    @Test
    @DisplayName("转质检: 原料 → 质检中, 成品 → 待检 (沿用缺口 11); 不受影响的批次被拒; 重复转幂等")
    void holdUsesExistingPendingStates() {
        placeEquipment(cold, at(-10, 0, 0));
        String bId = id("B");
        String fgId = finishedGoodsRepo.findAll().stream().filter(f -> "BN-FG-F".equals(f.getBatchNumber()))
                .findFirst().orElseThrow().getId();

        List<HoldOutcome> out = service.holdBatches(F, alertId, new HoldCommand(List.of(
                new HoldTarget("RAW_MATERIAL_BATCH", bId), new HoldTarget("FINISHED_GOODS", fgId))), USER, "qa");
        assertThat(out).extracting(HoldOutcome::outcome).containsExactly("HELD", "HELD");
        em.flush();
        em.clear();
        assertThat(materialBatchRepo.findById(bId).orElseThrow().getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        assertThat(finishedGoodsRepo.findById(fgId).orElseThrow().getStatus())
                .isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        assertThat(holdRepo.findByFactoryIdAndAlertId(F, alertId)).hasSize(2)
                .allSatisfy(h -> assertThat(h.getPreviousStatus()).isEqualTo("AVAILABLE"));
        assertThat(service.affectedBatches(F, alertId).batches().stream()
                .filter(AffectedBatch::alreadyHeld).count()).isEqualTo(2);

        // 再点一次: 不重复转
        assertThat(service.holdBatches(F, alertId, new HoldCommand(List.of(new HoldTarget("RAW_MATERIAL_BATCH", bId))),
                USER, "qa")).extracting(HoldOutcome::outcome).containsExactly("ALREADY_HELD");

        // 阴性对照: D 不受这条告警影响, 不能从这里转
        assertThatThrownBy(() -> service.holdBatches(F, alertId,
                new HoldCommand(List.of(new HoldTarget("RAW_MATERIAL_BATCH", id("D")))), USER, "qa"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不在这条告警的受影响批次里");
        em.clear();
        assertThat(materialBatchRepo.findById(id("D")).orElseThrow().getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    // ------------------------------------------------------------------ seed helpers

    private String warehouse(String code, String name) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setFactoryId(F);
        w.setCode(code);
        w.setName(name);
        w.setType(FactoryWarehouse.WarehouseType.RAW);
        return warehouseRepo.saveAndFlush(w).getId();
    }

    private String id(String key) {
        return "CCX-" + key;
    }

    private void material(String key, String warehouseId, LocalDateTime createdAt, String receipt, String used,
                          MaterialBatchStatus status, LocalDateTime updatedAt) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id(key));
        b.setFactoryId(F);
        b.setBatchNumber("BN-" + key);
        b.setWarehouseId(warehouseId);
        b.setReceiptDate(createdAt.toLocalDate());
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(new BigDecimal(used));
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setStatus(status);
        b.setCreatedBy(USER);
        materialBatchRepo.saveAndFlush(b);
        // custom_fields 置空: hypersistence 的 Map jsonb 列在 H2 上读不回 "{}" (仪器限制, 与被守行为无关)
        jdbc.update("UPDATE material_batches SET created_at = ?, updated_at = ?, custom_fields = NULL WHERE id = ?",
                createdAt, updatedAt, b.getId());
    }

    private void consumption(String key, LocalDateTime at) {
        jdbc.update("INSERT INTO material_consumptions (factory_id, batch_id, quantity, unit_price, total_cost, "
                        + "consumption_time, recorded_by, created_at, updated_at) VALUES (?, ?, 10, 1, 10, ?, ?, ?, ?)",
                F, id(key), at, USER, at, at);
    }

    private void finished(String key, String warehouseId, LocalDateTime createdAt) {
        FinishedGoodsBatch f = new FinishedGoodsBatch();
        f.setFactoryId(F);
        f.setBatchNumber("BN-" + key);
        f.setProductTypeId("PT-CCX");
        f.setProducedQuantity(new BigDecimal("40"));
        f.setUnit("kg");
        f.setWarehouseId(warehouseId);
        f.setProductionDate(createdAt.toLocalDate());
        String fid = finishedGoodsRepo.saveAndFlush(f).getId();
        jdbc.update("UPDATE finished_goods_batches SET created_at = ?, updated_at = ? WHERE id = ?",
                createdAt, createdAt, fid);
    }
}
