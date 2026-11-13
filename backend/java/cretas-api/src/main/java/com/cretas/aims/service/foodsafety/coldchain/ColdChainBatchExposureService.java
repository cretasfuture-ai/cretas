package com.cretas.aims.service.foodsafety.coldchain;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.foodsafety.ColdChainEquipmentLocation;
import com.cretas.aims.entity.foodsafety.ColdChainExcursionHold;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.foodsafety.ColdChainBatchStayQueries;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentLocationRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.foodsafety.ColdChainExcursionHoldRepository;
import com.cretas.aims.repository.foodsafety.ColdChainTempReadingRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Excursion;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Exposure;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.LocationSpan;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Reading;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Segment;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Segments;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Stay;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainExposureCalculator.Verdict;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 温度关联批次 (食品 DAG 缺口 15; Steve 2026-11-03: 先做软件侧)。
 *
 * <ul>
 *   <li>设备 ↔ 库位: {@link #assignLocation} 关掉当前时段、开新时段, 不改写历史;</li>
 *   <li>受影响批次: {@link #affectedBatches} = 告警所在的超温段 × 设备当时所在的库 × 批次在库时段;</li>
 *   <li>转质检: {@link #holdBatches} 沿用缺口 11 的待检状态 —— 原料 / 在制 {@code INSPECTING},
 *       成品 {@code PENDING_INSPECTION}; 本服务只留一条 {@link ColdChainExcursionHold}。</li>
 * </ul>
 *
 * <p>批次在库时段的取数 (见 {@link #materialStay} / {@link #finishedGoodsStay}):
 * 入库 = 批次登记时刻 (与入库/生产日期同一天才算确定); 出库 = 实物还有 ⇒ 仍在库, 出完了 ⇒ 最后一条出库流水,
 * 并以批次最后修改时刻为上界。拿不到的标「在库时间不确定」, 不猜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColdChainBatchExposureService {

    public static final String CODE_NO_EXCURSION = "COLD_CHAIN_EXCURSION_NOT_IN_READINGS";
    public static final String CODE_INVALID = "COLD_CHAIN_LOCATION_INVALID";
    public static final String CODE_NOT_AFFECTED = "COLD_CHAIN_HOLD_NOT_AFFECTED";

    /** 读数取告警前后多长 (超温段向两边扩展的范围)。 */
    static final Duration READING_LOOKBACK = Duration.ofHours(48);
    static final Duration READING_LOOKAHEAD = Duration.ofHours(48);
    /** 最后一条出库流水与批次最后修改相差在这之内 ⇒ 出完的就是那条流水。 */
    static final Duration SAME_UPDATE_TOLERANCE = Duration.ofMinutes(1);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ColdChainEquipmentRepository equipmentRepo;
    private final ColdChainEquipmentLocationRepository locationRepo;
    private final ColdChainAlertRepository alertRepo;
    private final ColdChainTempReadingRepository readingRepo;
    private final ColdChainExcursionHoldRepository holdRepo;
    private final ColdChainBatchStayQueries stayRepo;
    private final FactoryWarehouseRepository warehouseRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final FinishedGoodsBatchRepository finishedGoodsRepo;
    private final RawMaterialTypeRepository rawMaterialTypeRepo;
    private final ProductTypeRepository productTypeRepo;

    // ------------------------------------------------------------------ 设备 ↔ 库位

    public record EquipmentView(Long id, String equipmentCode, String equipmentName, String type,
                                BigDecimal targetTempMin, BigDecimal targetTempMax, boolean active,
                                String currentWarehouseId, String currentWarehouseName,
                                LocalDateTime currentSince) {}

    public record LocationView(Long id, Long equipmentId, String warehouseId, String warehouseName,
                               LocalDateTime effectiveFrom, LocalDateTime effectiveTo, String remark) {}

    public record AssignCommand(String warehouseId, LocalDateTime effectiveFrom, String remark) {}

    @Transactional(readOnly = true)
    public List<EquipmentView> listEquipment(String factoryId) {
        Map<String, String> whNames = warehouseNames(factoryId);
        Map<Long, ColdChainEquipmentLocation> open = new HashMap<>();
        for (ColdChainEquipmentLocation l : locationRepo.findByFactoryIdOrderByEquipmentIdAscEffectiveFromAsc(factoryId)) {
            if (l.getEffectiveTo() == null) open.put(l.getEquipmentId(), l);
        }
        List<EquipmentView> out = new ArrayList<>();
        for (ColdChainEquipment e : equipmentRepo.findByFactoryIdOrderByEquipmentCodeAsc(factoryId)) {
            ColdChainEquipmentLocation cur = open.get(e.getId());
            out.add(new EquipmentView(e.getId(), e.getEquipmentCode(), e.getEquipmentName(), e.getType(),
                    e.getTargetTempMin(), e.getTargetTempMax(), e.isActive(),
                    cur == null ? null : cur.getWarehouseId(),
                    cur == null ? null : whNames.get(cur.getWarehouseId()),
                    cur == null ? null : cur.getEffectiveFrom()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LocationView> listLocations(String factoryId, Long equipmentId) {
        requireEquipment(factoryId, equipmentId);
        Map<String, String> whNames = warehouseNames(factoryId);
        return locationRepo.findByFactoryIdAndEquipmentIdOrderByEffectiveFromAsc(factoryId, equipmentId).stream()
                .map(l -> toView(l, whNames)).toList();
    }

    /**
     * 设备从 {@code effectiveFrom} 起放在 {@code warehouseId}。当前时段在这一刻关掉, 不改写任何已关的时段;
     * 生效时刻不能早于已登记的最后一段 (那等于改写历史), 也不能是将来。
     */
    @Transactional
    public LocationView assignLocation(String factoryId, Long equipmentId, AssignCommand cmd, Long userId) {
        if (cmd == null || cmd.warehouseId() == null || cmd.warehouseId().isBlank()) {
            throw invalid("请选择设备所在的仓库");
        }
        ColdChainEquipment eq = equipmentRepo.findByIdAndFactoryIdForUpdate(equipmentId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "冷链设备不存在").withCode(CODE_INVALID));
        FactoryWarehouse wh = warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(cmd.warehouseId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "仓库不存在或不属于本厂").withCode(CODE_INVALID));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = cmd.effectiveFrom() != null ? cmd.effectiveFrom() : now;
        if (from.isAfter(now.plusMinutes(1))) {
            throw invalid("生效时间不能是将来: 设备真的放进去之后再登记");
        }
        List<ColdChainEquipmentLocation> spans =
                locationRepo.findByFactoryIdAndEquipmentIdOrderByEffectiveFromAsc(factoryId, equipmentId);
        ColdChainEquipmentLocation open = null;
        for (ColdChainEquipmentLocation s : spans) {
            if (s.getEffectiveTo() == null) {
                open = s;
            } else if (from.isBefore(s.getEffectiveTo())) {
                throw invalid("生效时间 " + TS.format(from) + " 早于这台设备已登记的库位记录 (到 "
                        + TS.format(s.getEffectiveTo()) + ")，已有的历史不能改写");
            }
        }
        if (open != null) {
            if (!from.isAfter(open.getEffectiveFrom())) {
                throw invalid("生效时间必须晚于当前库位的开始时间 " + TS.format(open.getEffectiveFrom()));
            }
            if (open.getWarehouseId().equals(wh.getId())) {
                throw invalid("这台设备现在就在「" + wh.getName() + "」，不用重复登记");
            }
            open.setEffectiveTo(from);
            locationRepo.saveAndFlush(open);
        }
        ColdChainEquipmentLocation created = locationRepo.save(ColdChainEquipmentLocation.builder()
                .factoryId(factoryId)
                .equipmentId(eq.getId())
                .warehouseId(wh.getId())
                .effectiveFrom(from)
                .remark(cmd.remark() == null || cmd.remark().isBlank() ? null : cmd.remark().trim())
                .createdBy(userId)
                .build());
        log.info("[冷链库位] factory={} equipment={} -> warehouse={} from={}", factoryId, eq.getId(), wh.getId(), from);
        return toView(created, Map.of(wh.getId(), wh.getName()));
    }

    // ------------------------------------------------------------------ 告警 → 受影响批次

    public record AlertView(Long id, Long equipmentId, String equipmentCode, String equipmentName,
                            LocalDateTime alertTime, String severity, BigDecimal minTemp, BigDecimal maxTemp,
                            Integer durationMinutes, String status, long heldBatchCount) {}

    public record SegmentView(String warehouseId, String warehouseName, LocalDateTime start, LocalDateTime end,
                              long minutes) {}

    public record GapView(LocalDateTime start, LocalDateTime end, long minutes) {}

    public record ExcursionView(LocalDateTime start, LocalDateTime end, long minutes, BigDecimal minTemp,
                                BigDecimal maxTemp, BigDecimal targetTempMin, BigDecimal targetTempMax,
                                int readingCount, boolean ongoing) {}

    public record AffectedBatch(
            String targetType,
            /** 给人看的形态: 原料 / 在制半成品 / 成品 */
            String formLabel,
            String targetId,
            String batchNumber,
            String itemName,
            String warehouseId,
            String warehouseName,
            BigDecimal quantityNow,
            String unit,
            String status,
            /** AFFECTED = 暴露时长确定; UNCERTAIN = 在库时间不确定 */
            String verdict,
            Long exposureMinutes,
            long maxExposureMinutes,
            LocalDateTime firstExposedAt,
            LocalDateTime lastExposedAt,
            String inStockFrom,
            String inStockUntil,
            String uncertainReason,
            boolean holdable,
            String holdBlockedReason,
            boolean alreadyHeld) {}

    public record AffectedReport(AlertView alert, ExcursionView excursion, List<SegmentView> segments,
                                 List<GapView> unlocatedPeriods, List<AffectedBatch> batches,
                                 int affectedCount, int uncertainCount, int notAffectedCount,
                                 String note) {}

    @Transactional(readOnly = true)
    public List<AlertView> listAlerts(String factoryId, int days) {
        LocalDateTime now = LocalDateTime.now();
        List<ColdChainAlert> alerts = alertRepo.findByFactoryIdAndAlertTimeBetweenOrderByAlertTimeDesc(
                factoryId, now.minusDays(Math.max(1, Math.min(days, 365))), now.plusDays(1));
        Map<Long, ColdChainEquipment> eqs = equipmentRepo.findByFactoryIdOrderByEquipmentCodeAsc(factoryId).stream()
                .collect(Collectors.toMap(ColdChainEquipment::getId, Function.identity()));
        return alerts.stream().map(a -> alertView(a, eqs.get(a.getEquipmentId()),
                holdRepo.findByFactoryIdAndAlertId(factoryId, a.getId()).size())).toList();
    }

    @Transactional(readOnly = true)
    public AffectedReport affectedBatches(String factoryId, Long alertId) {
        return compute(factoryId, alertId).report();
    }

    private record Computed(AffectedReport report, Map<String, AffectedBatch> byKey) {}

    private Computed compute(String factoryId, Long alertId) {
        ColdChainAlert alert = alertRepo.findByIdAndFactoryId(alertId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "冷链告警不存在").withCode(CODE_INVALID));
        ColdChainEquipment eq = equipmentRepo.findByIdAndFactoryId(alert.getEquipmentId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "告警对应的冷链设备不存在").withCode(CODE_INVALID));

        List<Reading> readings = readingRepo.findByEquipmentIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        eq.getId(), alert.getAlertTime().minus(READING_LOOKBACK), alert.getAlertTime().plus(READING_LOOKAHEAD))
                .stream().filter(r -> factoryId.equals(r.getFactoryId()))
                .map(r -> new Reading(r.getRecordedAt(), r.getTemperature())).toList();
        Excursion ex = ColdChainExposureCalculator.excursionAround(readings, eq.getTargetTempMin(),
                        eq.getTargetTempMax(), alert.getAlertTime())
                .orElseThrow(() -> new BusinessException(409,
                        "这条告警前后的温度读数里找不到超出目标区间 " + eq.getTargetTempMin() + "~" + eq.getTargetTempMax()
                                + " 的读数，算不出超温时段，也就列不出受影响批次")
                        .withCode(CODE_NO_EXCURSION)
                        .withHint("请检查这台设备的温度读数是否已上传，或确认告警是否为误报"));

        List<LocationSpan> spans = locationRepo.findByFactoryIdAndEquipmentIdOrderByEffectiveFromAsc(factoryId, eq.getId())
                .stream().map(l -> new LocationSpan(l.getWarehouseId(), l.getEffectiveFrom(), l.getEffectiveTo())).toList();
        Segments segs = ColdChainExposureCalculator.segments(ex.start(), ex.end(), spans);
        Map<String, String> whNames = warehouseNames(factoryId);
        Map<String, List<Segment>> segsByWh = segs.covered().stream()
                .collect(Collectors.groupingBy(Segment::warehouseId));

        Set<String> heldKeys = holdRepo.findByFactoryIdAndAlertId(factoryId, alertId).stream()
                .map(h -> key(h.getTargetType(), h.getTargetId())).collect(Collectors.toSet());

        List<AffectedBatch> rows = new ArrayList<>();
        int notAffected = 0;
        if (!segsByWh.isEmpty()) {
            Set<String> whIds = segsByWh.keySet();
            List<MaterialBatch> mbs = stayRepo.findMaterialBatchCandidates(factoryId, whIds, ex.start(), ex.end());
            Map<String, LocalDateTime> mbLastOut = materialLastOut(factoryId, mbs);
            Map<String, String> rawNames = rawNames(mbs);
            Map<String, String> productNames = productNames(factoryId,
                    mbs.stream().map(MaterialBatch::getProductTypeId).filter(Objects::nonNull).collect(Collectors.toSet()));
            for (MaterialBatch m : mbs) {
                Exposure e = ColdChainExposureCalculator.expose(materialStay(m, mbLastOut.get(m.getId())),
                        segsByWh.getOrDefault(m.getWarehouseId(), List.of()));
                if (e.verdict() == Verdict.NOT_AFFECTED) {
                    notAffected++;
                    continue;
                }
                boolean wip = QcInspectionGuard.WIP_SOURCE_DOC_TYPE.equals(m.getSourceDocType());
                String type = wip ? ColdChainExcursionHold.TargetType.WIP_MATERIAL_BATCH
                        : ColdChainExcursionHold.TargetType.RAW_MATERIAL_BATCH;
                String name = wip ? productNames.getOrDefault(m.getProductTypeId(), rawNames.get(m.getMaterialTypeId()))
                        : rawNames.get(m.getMaterialTypeId());
                Stay stay = materialStay(m, mbLastOut.get(m.getId()));
                String status = m.getStatus() == null ? null : m.getStatus().name();
                String blocked = m.getStatus() == MaterialBatchStatus.INSPECTING ? "已经在质检中"
                        : m.getStatus() != MaterialBatchStatus.AVAILABLE ? "当前状态「" + statusLabel(m.getStatus()) + "」不能转待检"
                        : m.getPhysicalQuantity().signum() <= 0 ? "已经全部出库，库里没有货可扣" : null;
                rows.add(row(type, wip ? "在制半成品" : "原料", m.getId(), m.getBatchNumber(), name,
                        m.getWarehouseId(), whNames, m.getPhysicalQuantity(), m.getQuantityUnit(), status, e, stay,
                        blocked, heldKeys.contains(key(type, m.getId()))));
            }
            List<FinishedGoodsBatch> fgs = stayRepo.findFinishedGoodsCandidates(factoryId, whIds, ex.start(), ex.end())
                    .stream().filter(f -> !FinishedGoodsBatch.Status.REVERSED.equals(f.getStatus())).toList();
            Map<String, LocalDateTime> fgLastOut = finishedGoodsLastOut(factoryId, fgs);
            Map<String, String> fgNames = productNames(factoryId,
                    fgs.stream().map(FinishedGoodsBatch::getProductTypeId).filter(Objects::nonNull).collect(Collectors.toSet()));
            for (FinishedGoodsBatch f : fgs) {
                Stay stay = finishedGoodsStay(f, fgLastOut.get(f.getId()));
                Exposure e = ColdChainExposureCalculator.expose(stay, segsByWh.getOrDefault(f.getWarehouseId(), List.of()));
                if (e.verdict() == Verdict.NOT_AFFECTED) {
                    notAffected++;
                    continue;
                }
                BigDecimal physical = fgPhysical(f);
                String blocked = FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(f.getStatus()) ? "已经在待检"
                        : !FinishedGoodsBatch.Status.AVAILABLE.equals(f.getStatus()) ? "当前状态「" + f.getStatus() + "」不能转待检"
                        : physical.signum() <= 0 ? "已经全部出库，库里没有货可扣" : null;
                String type = ColdChainExcursionHold.TargetType.FINISHED_GOODS;
                rows.add(row(type, "成品", f.getId(), f.getBatchNumber(),
                        f.getProductName() != null ? f.getProductName() : fgNames.get(f.getProductTypeId()),
                        f.getWarehouseId(), whNames, physical, f.getUnit(), f.getStatus(), e, stay, blocked,
                        heldKeys.contains(key(type, f.getId()))));
            }
        }
        rows.sort(Comparator.comparing((AffectedBatch b) -> "UNCERTAIN".equals(b.verdict()))
                .thenComparing(AffectedBatch::maxExposureMinutes, Comparator.reverseOrder()));

        int affected = (int) rows.stream().filter(r -> "AFFECTED".equals(r.verdict())).count();
        int uncertain = rows.size() - affected;
        String note = segs.covered().isEmpty()
                ? "超温期间这台设备没有登记所在仓库，列不出受影响批次：请先在「设备库位」里登记它当时在哪个库"
                : !segs.uncovered().isEmpty()
                ? "超温期间有一段时间这台设备没有登记所在仓库，那段时间的批次没有算进来"
                : null;
        AffectedReport report = new AffectedReport(
                alertView(alert, eq, heldKeys.size()),
                new ExcursionView(ex.start(), ex.end(), ex.minutes(), ex.minTemp(), ex.maxTemp(),
                        eq.getTargetTempMin(), eq.getTargetTempMax(), ex.readingCount(), ex.ongoing()),
                segs.covered().stream().map(s -> new SegmentView(s.warehouseId(), whNames.get(s.warehouseId()),
                        s.start(), s.end(), Duration.between(s.start(), s.end()).toMinutes())).toList(),
                segs.uncovered().stream().map(g -> new GapView(g.start(), g.end(),
                        Duration.between(g.start(), g.end()).toMinutes())).toList(),
                rows, affected, uncertain, notAffected, note);
        Map<String, AffectedBatch> byKey = new HashMap<>();
        rows.forEach(r -> byKey.put(key(r.targetType(), r.targetId()), r));
        return new Computed(report, byKey);
    }

    // ------------------------------------------------------------------ 转质检

    public record HoldTarget(String targetType, String targetId) {}

    public record HoldCommand(List<HoldTarget> targets) {}

    public record HoldOutcome(String targetType, String targetId, String batchNumber, String outcome, String message) {}

    /**
     * 把选中的受影响批次转成待检 (沿用缺口 11 的状态)。只接受本告警算出来受影响 / 时间不确定的批次;
     * 已在待检的不重复转, 状态不对的逐个说明原因, 不整单失败。
     */
    @Transactional
    public List<HoldOutcome> holdBatches(String factoryId, Long alertId, HoldCommand cmd, Long userId, String userName) {
        if (cmd == null || cmd.targets() == null || cmd.targets().isEmpty()) {
            throw invalid("请至少选一个批次");
        }
        if (userId == null) {
            throw new BusinessException(401, "缺少操作人").withCode(CODE_INVALID);
        }
        Map<String, AffectedBatch> affected = compute(factoryId, alertId).byKey();
        Set<String> heldAlready = holdRepo.findByFactoryIdAndAlertId(factoryId, alertId).stream()
                .map(h -> key(h.getTargetType(), h.getTargetId())).collect(Collectors.toCollection(HashSet::new));
        List<HoldOutcome> out = new ArrayList<>();
        for (HoldTarget t : cmd.targets()) {
            AffectedBatch row = affected.get(key(t.targetType(), t.targetId()));
            if (row == null) {
                throw new BusinessException(409, "批次 " + t.targetId() + " 不在这条告警的受影响批次里，不能从这里转质检")
                        .withCode(CODE_NOT_AFFECTED)
                        .withHint("请刷新受影响批次表后重新勾选；其他批次请到「质检记录」处理");
            }
            if (heldAlready.contains(key(t.targetType(), t.targetId()))) {
                out.add(new HoldOutcome(t.targetType(), t.targetId(), row.batchNumber(), "ALREADY_HELD", "这条告警已经把它转过质检"));
                continue;
            }
            out.add(holdOne(factoryId, alertId, row, userId, userName));
            heldAlready.add(key(t.targetType(), t.targetId()));
        }
        return out;
    }

    private HoldOutcome holdOne(String factoryId, Long alertId, AffectedBatch row, Long userId, String userName) {
        String previous;
        String held;
        switch (row.targetType()) {
            case ColdChainExcursionHold.TargetType.RAW_MATERIAL_BATCH,
                 ColdChainExcursionHold.TargetType.WIP_MATERIAL_BATCH -> {
                MaterialBatch m = materialBatchRepo.findByIdAndFactoryIdForUpdate(row.targetId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "批次不存在").withCode(CODE_INVALID));
                if (m.getStatus() == MaterialBatchStatus.INSPECTING) {
                    return skipped(row, "ALREADY_PENDING", "已经在质检中");
                }
                if (m.getStatus() != MaterialBatchStatus.AVAILABLE || m.getPhysicalQuantity().signum() <= 0) {
                    return skipped(row, "SKIPPED", m.getPhysicalQuantity().signum() <= 0 ? "已经全部出库，库里没有货可扣"
                            : "当前状态「" + statusLabel(m.getStatus()) + "」不能转待检");
                }
                previous = m.getStatus().name();
                com.cretas.aims.service.quality.release.MaterialBatchHoldService.hold(m);   // 记下扣留前状态 (#1590 遗留 M5)
                materialBatchRepo.save(m);
                held = MaterialBatchStatus.INSPECTING.name();
            }
            case ColdChainExcursionHold.TargetType.FINISHED_GOODS -> {
                FinishedGoodsBatch f = finishedGoodsRepo.findByIdAndFactoryIdForUpdate(row.targetId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "成品批次不存在").withCode(CODE_INVALID));
                if (FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(f.getStatus())) {
                    return skipped(row, "ALREADY_PENDING", "已经在待检");
                }
                if (!FinishedGoodsBatch.Status.AVAILABLE.equals(f.getStatus()) || fgPhysical(f).signum() <= 0) {
                    return skipped(row, "SKIPPED", fgPhysical(f).signum() <= 0 ? "已经全部出库，库里没有货可扣"
                            : "当前状态「" + f.getStatus() + "」不能转待检");
                }
                previous = f.getStatus();
                f.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
                finishedGoodsRepo.save(f);
                held = FinishedGoodsBatch.Status.PENDING_INSPECTION;
            }
            default -> throw invalid("不支持的批次类型: " + row.targetType());
        }
        holdRepo.save(ColdChainExcursionHold.builder()
                .factoryId(factoryId)
                .alertId(alertId)
                .targetType(row.targetType())
                .targetId(row.targetId())
                .batchNumber(row.batchNumber())
                .warehouseId(row.warehouseId())
                .exposureMinutes(row.exposureMinutes() == null ? null : row.exposureMinutes().intValue())
                .previousStatus(previous)
                .heldStatus(held)
                .heldBy(userId)
                .heldByName(userName)
                .build());
        log.info("[冷链转质检] factory={} alert={} {}:{} {}->{} by={}", factoryId, alertId,
                row.targetType(), row.targetId(), previous, held, userId);
        String where = ColdChainExcursionHold.TargetType.RAW_MATERIAL_BATCH.equals(row.targetType())
                ? "已转为质检中，请到「质检记录」检验后处置" : "已转为待检，请到「质检放行」放行或判不合格";
        return new HoldOutcome(row.targetType(), row.targetId(), row.batchNumber(), "HELD", where);
    }

    private static HoldOutcome skipped(AffectedBatch row, String outcome, String msg) {
        return new HoldOutcome(row.targetType(), row.targetId(), row.batchNumber(), outcome, msg);
    }

    // ------------------------------------------------------------------ 在库时段

    /** 原料 / 在制批次的在库时段。{@code lastOut} = 已知出库流水 (领用 / 调拨发出 / last_used_at) 的最后时刻。 */
    static Stay materialStay(MaterialBatch m, LocalDateTime lastOut) {
        return stay(m.getCreatedAt(), m.getReceiptDate(), "入库日期",
                m.getPhysicalQuantity().signum() > 0, latest(lastOut, m.getLastUsedAt()), m.getUpdatedAt());
    }

    /** 成品批次的在库时段。{@code lastOut} = 发货明细 / 数量调整流水的最后时刻。 */
    static Stay finishedGoodsStay(FinishedGoodsBatch f, LocalDateTime lastOut) {
        return stay(f.getCreatedAt(), f.getProductionDate(), "生产日期",
                fgPhysical(f).signum() > 0, lastOut, f.getUpdatedAt());
    }

    static Stay stay(LocalDateTime registeredAt, LocalDate businessDate, String dateLabel,
                     boolean stillHasStock, LocalDateTime lastOutEvent, LocalDateTime lastModifiedAt) {
        List<String> reasons = new ArrayList<>();
        LocalDateTime inE = registeredAt;
        LocalDateTime inL = registeredAt;
        if (businessDate != null && !businessDate.equals(registeredAt.toLocalDate())) {
            LocalDateTime dayStart = businessDate.atStartOfDay();
            LocalDateTime dayEnd = businessDate.atTime(LocalTime.MAX);
            inE = dayStart.isBefore(registeredAt) ? dayStart : registeredAt;
            inL = dayEnd.isAfter(registeredAt) ? dayEnd : registeredAt;
            reasons.add(dateLabel + " " + businessDate + " 与系统登记时刻 " + TS.format(registeredAt)
                    + " 不在同一天，具体入库时刻未知");
        }
        if (stillHasStock) {
            return new Stay(inE, inL, null, null, reasons.isEmpty() ? null : String.join("；", reasons));
        }
        LocalDateTime upper = latest(lastModifiedAt, lastOutEvent);
        LocalDateTime outE;
        LocalDateTime outL;
        if (lastOutEvent == null) {
            outE = inL;
            outL = upper;
            reasons.add("没有找到出库流水，只知道最晚 " + TS.format(upper) + " 前已出完");
        } else if (Duration.between(lastOutEvent, upper).compareTo(SAME_UPDATE_TOLERANCE) <= 0) {
            outE = lastOutEvent;
            outL = lastOutEvent;
        } else {
            outE = lastOutEvent;
            outL = upper;
            reasons.add("最后一条出库流水在 " + TS.format(lastOutEvent) + "，批次在 " + TS.format(upper)
                    + " 又被改过，出完的确切时刻在两者之间");
        }
        if (outE.isBefore(inE)) outE = inE;
        return new Stay(inE, inL, outE, outL, reasons.isEmpty() ? null : String.join("；", reasons));
    }

    private Map<String, LocalDateTime> materialLastOut(String factoryId, List<MaterialBatch> mbs) {
        Map<String, LocalDateTime> out = new HashMap<>();
        if (mbs.isEmpty()) return out;
        Set<String> ids = mbs.stream().map(MaterialBatch::getId).collect(Collectors.toSet());
        merge(out, stayRepo.lastConsumptionAt(factoryId, ids));
        merge(out, stayRepo.lastTransferDispatchAt(factoryId, ids));
        return out;
    }

    private Map<String, LocalDateTime> finishedGoodsLastOut(String factoryId, List<FinishedGoodsBatch> fgs) {
        Map<String, LocalDateTime> out = new HashMap<>();
        if (fgs.isEmpty()) return out;
        Set<String> ids = fgs.stream().map(FinishedGoodsBatch::getId).collect(Collectors.toSet());
        merge(out, stayRepo.lastDeliveryAt(ids));
        merge(out, stayRepo.lastFinishedGoodsAdjustmentAt(factoryId, ids));
        return out;
    }

    private static void merge(Map<String, LocalDateTime> into, List<Object[]> rows) {
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            into.merge((String) r[0], (LocalDateTime) r[1], ColdChainBatchExposureService::latest);
        }
    }

    static BigDecimal fgPhysical(FinishedGoodsBatch f) {
        BigDecimal produced = f.getProducedQuantity() == null ? BigDecimal.ZERO : f.getProducedQuantity();
        BigDecimal shipped = f.getShippedQuantity() == null ? BigDecimal.ZERO : f.getShippedQuantity();
        return produced.subtract(shipped);
    }

    // ------------------------------------------------------------------ helpers

    private AffectedBatch row(String type, String formLabel, String id, String batchNumber, String name,
                              String warehouseId, Map<String, String> whNames, BigDecimal qty, String unit,
                              String status, Exposure e, Stay stay, String blocked, boolean alreadyHeld) {
        String from = stay.inEarliest().equals(stay.inLatest()) ? TS.format(stay.inEarliest())
                : TS.format(stay.inEarliest()) + " ~ " + TS.format(stay.inLatest());
        String until = stay.stillInStock() ? "仍在库"
                : stay.outEarliest().equals(stay.outLatest()) ? TS.format(stay.outEarliest())
                : TS.format(stay.outEarliest()) + " ~ " + TS.format(stay.outLatest());
        return new AffectedBatch(type, formLabel, id, batchNumber, name, warehouseId, whNames.get(warehouseId),
                qty, unit, status, e.verdict().name(), e.minutes(), e.maxMinutes(), e.firstExposedAt(),
                e.lastExposedAt(), from, until, e.verdict() == Verdict.UNCERTAIN ? e.reason() : null,
                blocked == null, blocked, alreadyHeld);
    }

    private AlertView alertView(ColdChainAlert a, ColdChainEquipment eq, long held) {
        return new AlertView(a.getId(), a.getEquipmentId(), eq == null ? null : eq.getEquipmentCode(),
                eq == null ? null : eq.getEquipmentName(), a.getAlertTime(), a.getSeverity(), a.getMinTemp(),
                a.getMaxTemp(), a.getDurationMinutes(), a.getStatus(), held);
    }

    private LocationView toView(ColdChainEquipmentLocation l, Map<String, String> whNames) {
        return new LocationView(l.getId(), l.getEquipmentId(), l.getWarehouseId(), whNames.get(l.getWarehouseId()),
                l.getEffectiveFrom(), l.getEffectiveTo(), l.getRemark());
    }

    private void requireEquipment(String factoryId, Long equipmentId) {
        equipmentRepo.findByIdAndFactoryId(equipmentId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "冷链设备不存在").withCode(CODE_INVALID));
    }

    private Map<String, String> warehouseNames(String factoryId) {
        return warehouseRepo.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId).stream()
                .collect(Collectors.toMap(FactoryWarehouse::getId, FactoryWarehouse::getName, (a, b) -> a, HashMap::new));
    }

    private Map<String, String> rawNames(List<MaterialBatch> mbs) {
        Set<String> ids = mbs.stream().map(MaterialBatch::getMaterialTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return new HashMap<>();   // 不用 Map.of(): 批次的类型 id 可能为 null, get(null) 会抛
        return rawMaterialTypeRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(RawMaterialType::getId, RawMaterialType::getName, (a, b) -> a, HashMap::new));
    }

    private Map<String, String> productNames(String factoryId, Collection<String> ids) {
        if (ids.isEmpty()) return new HashMap<>();   // 不用 Map.of(): 批次的类型 id 可能为 null, get(null) 会抛
        return productTypeRepo.findByFactoryIdAndIdIn(factoryId, ids).stream()
                .collect(Collectors.toMap(ProductType::getId, ProductType::getName, (a, b) -> a, HashMap::new));
    }

    private static String statusLabel(MaterialBatchStatus s) {
        return s == null ? "未知" : s.getDisplayName();
    }

    private static String key(String type, String id) {
        return type + ":" + id;
    }

    static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static BusinessException invalid(String msg) {
        return new BusinessException(400, msg).withCode(CODE_INVALID);
    }
}
