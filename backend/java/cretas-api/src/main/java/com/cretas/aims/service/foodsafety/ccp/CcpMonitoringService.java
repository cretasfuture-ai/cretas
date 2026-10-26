package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.quality.release.QcDeviationHold;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 缺口 13 CCP 监控 (owner 2026-10-25): 工序报工时录入关键控制点实测值。
 *
 * <ol>
 *   <li>CCP 挂在工序上 ({@link HaccpCheckpoint#getWorkProcessId()}); 该工序报工时,
 *       挂着的每一个启用 CCP 都<b>必须</b>录实测值 (少录一项整笔拒绝, 防呆 Rule 1)。</li>
 *   <li>实测值超出 [下限, 上限] ⇒ 自动生成偏离 ({@code isDeviation=true}), 立刻把该批次/计划已有的产出
 *       扣成待检 ({@link QcDeviationHold}), 之后入账的产出由 {@code QcInspectionPolicy} 扣;
 *       质检放行在偏离处理之前一律拒绝 ({@link CcpOpenDeviationLookup} 作为 QcReleaseBlocker)。</li>
 *   <li>偏离「处理」= 填纠偏措施 + 处理人/时间; 处理完才能放行。</li>
 *   <li>按批次导出全部 CCP 记录 (批次号 或 同一生产计划)。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcpMonitoringService {

    public static final String CODE_INVALID = "CCP_RECORD_INVALID";
    public static final String CODE_MISSING = "CCP_READING_MISSING";
    public static final String CODE_NOT_DEVIATION = "CCP_NOT_OPEN_DEVIATION";

    public static final String SOURCE_APP = "APP_STEP_REPORT";
    public static final String SOURCE_WEB = "WEB_PROCESS_SHEET";

    /** 同人同批同点同值在这个窗口内再提交一次 = 重试, 不重复记 (防呆 Rule 4)。 */
    private static final long DUPLICATE_WINDOW_SECONDS = 120;

    private final HaccpCheckpointRepository checkpointRepo;
    private final HaccpMonitoringRecordRepository recordRepo;
    private final ProductionBatchRepository productionBatchRepo;
    private final FinishedGoodsBatchRepository finishedGoodsRepo;
    private final QcDeviationHold deviationHold;

    /** 报工屏要显示的 CCP: 名称 + 限值 + 单位 (录入前先看到边界)。 */
    public record StepCheckpoint(Long checkpointId, String checkpointCode, String name, String hazardType,
                                 BigDecimal criticalLimitMin, BigDecimal criticalLimitMax, String unit,
                                 String monitoringProcedure, String correctiveAction) {
    }

    public record Reading(Long checkpointId, BigDecimal measuredValue, String notes) {
    }

    /**
     * 一次录入。
     *
     * @param batchNumber       报工批号 (App: 生产批号; web: 本行批号/锚号)。productionBatchId 给了可省
     * @param productionBatchId 报工所在生产批次 (可空)
     * @param planId            生产计划 (可空; 有 productionBatchId 时从批次补)
     * @param workProcessId     工序 (必填)
     * @param processOrder      工序序 (可空)
     * @param source            APP_STEP_REPORT / WEB_PROCESS_SHEET
     */
    public record RecordCommand(String batchNumber, Long productionBatchId, String planId, String workProcessId,
                                Integer processOrder, String source, List<Reading> readings) {
    }

    public record RecordResult(List<RecordView> records, int deviationCount, int heldOutputs,
                               String message) {
    }

    /** 给界面的一条 CCP 记录 (带 CCP 名称; 偏离状态拆成明确的三态, 不让前端猜布尔字段名)。 */
    public record RecordView(Long id, Long checkpointId, String checkpointCode, String checkpointName,
                             String batchNumber, Integer processOrder, String workProcessId,
                             LocalDateTime monitoringTime, BigDecimal measuredValue,
                             BigDecimal criticalLimitMin, BigDecimal criticalLimitMax, String unit,
                             /** OK / DEVIATION_OPEN / DEVIATION_HANDLED */
                             String result,
                             String operatorName, Long operatorUserId, String source, String notes,
                             String deviationAction, LocalDateTime deviationHandledAt,
                             String deviationHandledByName, String correctiveActionGuide) {
    }

    /** 记录 → 界面视图 (一次批量取 CCP 定义)。 */
    @Transactional(readOnly = true)
    public List<RecordView> toViews(List<HaccpMonitoringRecord> rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.getCheckpointId()));
        Map<Long, HaccpCheckpoint> cps = ids.isEmpty() ? Map.of() : checkpointRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(HaccpCheckpoint::getId, Function.identity(), (a, b) -> a));
        List<RecordView> out = new ArrayList<>(rows.size());
        for (HaccpMonitoringRecord r : rows) {
            HaccpCheckpoint cp = cps.get(r.getCheckpointId());
            out.add(new RecordView(r.getId(), r.getCheckpointId(),
                    cp == null ? null : cp.getCheckpointCode(), cp == null ? null : cp.getName(),
                    r.getBatchNumber(), r.getProcessOrder(), r.getWorkProcessId(), r.getMonitoringTime(),
                    r.getMeasuredValue(),
                    r.getCriticalLimitMinSnapshot() != null ? r.getCriticalLimitMinSnapshot()
                            : cp == null ? null : cp.getCriticalLimitMin(),
                    r.getCriticalLimitMaxSnapshot() != null ? r.getCriticalLimitMaxSnapshot()
                            : cp == null ? null : cp.getCriticalLimitMax(),
                    r.getUnitSnapshot() != null ? r.getUnitSnapshot() : cp == null ? null : cp.getUnit(),
                    resultOf(r), r.getOperatorName(), r.getOperatorUserId(), r.getSource(), r.getNotes(),
                    r.getDeviationAction(), r.getDeviationHandledAt(), r.getDeviationHandledByName(),
                    cp == null ? null : cp.getCorrectiveAction()));
        }
        return out;
    }

    static String resultOf(HaccpMonitoringRecord r) {
        if (!r.isDeviation()) {
            return "OK";
        }
        return r.getDeviationHandledAt() == null ? "DEVIATION_OPEN" : "DEVIATION_HANDLED";
    }

    @Transactional(readOnly = true)
    public List<StepCheckpoint> checkpointsForStep(String factoryId, String workProcessId) {
        if (workProcessId == null || workProcessId.isBlank()) {
            return List.of();
        }
        return checkpointRepo.findByFactoryIdAndWorkProcessIdAndActiveTrueOrderByCheckpointCodeAsc(
                        factoryId, workProcessId).stream()
                .map(c -> new StepCheckpoint(c.getId(), c.getCheckpointCode(), c.getName(), c.getHazardType(),
                        c.getCriticalLimitMin(), c.getCriticalLimitMax(), c.getUnit(),
                        c.getMonitoringProcedure(), c.getCorrectiveAction()))
                .toList();
    }

    /** 实测值是否超出临界限值 (闭区间 [min, max] 内为合格)。 */
    public static boolean isOutOfLimit(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            return false;
        }
        return (min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0);
    }

    @Transactional
    public RecordResult record(String factoryId, RecordCommand cmd, Long userId, String userName) {
        return record(factoryId, cmd, userId, userName, null);
    }

    /**
     * 校验一次报工带的 CCP 实测值是否齐全 (审查中 7: 报工落库<b>之前</b>调用, 少录一项整笔拒绝, 一行都不写)。
     *
     * @return 本工序启用的 CCP (按 id 有序); 空 = 本工序没挂 CCP, 调用方不必录
     */
    @Transactional(readOnly = true)
    public Map<Long, HaccpCheckpoint> validateReadings(String factoryId, String workProcessId, List<Reading> readings) {
        Map<Long, HaccpCheckpoint> required = workProcessId == null || workProcessId.isBlank()
                ? new java.util.LinkedHashMap<>()
                : checkpointRepo.findByFactoryIdAndWorkProcessIdAndActiveTrueOrderByCheckpointCodeAsc(
                        factoryId, workProcessId).stream()
                .collect(Collectors.toMap(HaccpCheckpoint::getId, Function.identity(), (a, b) -> a,
                        java.util.LinkedHashMap::new));
        if (required.isEmpty()) {
            return required;
        }
        Map<Long, Reading> byId = indexReadings(required, readings);
        List<String> missing = new ArrayList<>();
        for (HaccpCheckpoint c : required.values()) {
            Reading r = byId.get(c.getId());
            if (r == null || r.measuredValue() == null) {
                missing.add(c.getName() + "（" + c.getCriticalLimitMin().stripTrailingZeros().toPlainString()
                        + "~" + c.getCriticalLimitMax().stripTrailingZeros().toPlainString() + c.getUnit() + "）");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "本工序是关键控制点，还有实测值没录：" + String.join("、", missing))
                    .withCode(CODE_MISSING)
                    .withHint("请按工序规定测量后逐项填写，全部填完再提交；若界面上没有录入栏，请刷新页面或更新 App")
                    .withSeverity("BLOCKING")
                    .withHintTarget("ccpReadings");
        }
        return required;
    }

    private Map<Long, Reading> indexReadings(Map<Long, HaccpCheckpoint> required, List<Reading> readings) {
        Map<Long, Reading> byId = new HashMap<>();
        for (Reading r : readings == null ? List.<Reading>of() : readings) {
            if (r == null || r.checkpointId() == null) {
                continue;
            }
            if (!required.containsKey(r.checkpointId())) {
                throw invalid("CCP " + r.checkpointId() + " 不属于本工序或已停用");
            }
            byId.put(r.checkpointId(), r);
        }
        return byId;
    }

    /**
     * @param reportRef 报工凭据 (YR:报工id / PS:计划:行); 非空时与报工同一事务落库, 重放按它去重
     */
    @Transactional
    public RecordResult record(String factoryId, RecordCommand cmd, Long userId, String userName, String reportRef) {
        if (userId == null) {
            throw invalid("未登录，无法录入 CCP");
        }
        if (cmd == null || cmd.workProcessId() == null || cmd.workProcessId().isBlank()) {
            throw invalid("缺少工序 (workProcessId)");
        }
        // 1. 批次身份: 生产批次优先, 补出批号与计划
        String batchNumber = trimToNull(cmd.batchNumber());
        Long pbId = cmd.productionBatchId();
        String planId = trimToNull(cmd.planId());
        ProductionBatch pb = null;
        if (pbId != null) {
            final Long requestedPbId = pbId;
            pb = productionBatchRepo.findByIdAndFactoryId(requestedPbId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "生产批次不存在或不属于本工厂: " + requestedPbId)
                            .withCode(CODE_INVALID).withHint("请刷新报工页面后重试"));
        } else if (batchNumber != null) {
            pb = productionBatchRepo.findByFactoryIdAndBatchNumber(factoryId, batchNumber).orElse(null);
        }
        if (pb != null) {
            pbId = pb.getId();
            if (batchNumber == null) {
                batchNumber = pb.getBatchNumber();
            }
            if (planId == null) {
                planId = pb.getProductionPlanId();
            }
        }
        if (batchNumber == null) {
            throw invalid("缺少批次号: 请先保存报工行拿到批次号, 再提交 CCP");
        }

        // 2. 本工序必录的 CCP 全部要有值; 不许录不属于本工序的 CCP
        Map<Long, HaccpCheckpoint> required = validateReadings(factoryId, cmd.workProcessId(), cmd.readings());
        if (required.isEmpty()) {
            throw new BusinessException(409, "该工序没有挂关键控制点，无需录入 CCP")
                    .withCode(CODE_INVALID)
                    .withHint("如需监控，请先在「食品安全 → HACCP 关键控制点」给 CCP 选择所属工序");
        }
        Map<Long, Reading> byId = indexReadings(required, cmd.readings());

        // 3. 落记录 (快照限值), 判偏离
        LocalDateTime now = LocalDateTime.now();
        String source = cmd.source() == null || cmd.source().isBlank() ? SOURCE_APP : cmd.source().trim();
        List<HaccpMonitoringRecord> saved = new ArrayList<>();
        int deviations = 0;
        for (HaccpCheckpoint c : required.values()) {
            Reading r = byId.get(c.getId());
            // 去重: 带报工凭据的按「同一次报工 + 同一控制点」(报工幂等重放); 不带的沿用双击窗口
            List<HaccpMonitoringRecord> dup = reportRef != null
                    ? recordRepo.findByFactoryIdAndReportRefAndCheckpointId(factoryId, reportRef, c.getId())
                    : recordRepo.findRecentDuplicates(factoryId, batchNumber, c.getId(),
                            userId, r.measuredValue(), now.minusSeconds(DUPLICATE_WINDOW_SECONDS));
            if (!dup.isEmpty()) {
                saved.add(dup.get(0));
                if (dup.get(0).isOpenDeviation()) {
                    deviations++;
                }
                continue;
            }
            boolean deviation = isOutOfLimit(r.measuredValue(), c.getCriticalLimitMin(), c.getCriticalLimitMax());
            HaccpMonitoringRecord rec = HaccpMonitoringRecord.builder()
                    .factoryId(factoryId)
                    .checkpointId(c.getId())
                    .batchNumber(batchNumber)
                    .monitoringTime(now)
                    .measuredValue(r.measuredValue())
                    .operatorUserId(userId)
                    .operatorName(userName)
                    .isDeviation(deviation)
                    .notes(trimToNull(r.notes()))
                    .productionBatchId(pbId)
                    .productionPlanId(planId)
                    .workProcessId(cmd.workProcessId())
                    .processOrder(cmd.processOrder())
                    .source(source)
                    .reportRef(reportRef)
                    .criticalLimitMinSnapshot(c.getCriticalLimitMin())
                    .criticalLimitMaxSnapshot(c.getCriticalLimitMax())
                    .unitSnapshot(c.getUnit())
                    .build();
            saved.add(recordRepo.save(rec));
            if (deviation) {
                deviations++;
            }
        }

        // 4. 有偏离: 已存在的产出立刻扣成待检
        int held = 0;
        if (deviations > 0) {
            held = deviationHold.holdForDeviation(factoryId, batchNumber, pbId, planId).total();
        }
        String message = deviations == 0
                ? "CCP 已记录，全部在限值内"
                : "有 " + deviations + " 项 CCP 超出限值，已记录偏离；批次 " + batchNumber
                        + " 的产出已转为待检，请通知质检员处理偏离后再放行";
        log.info("[CCP] factory={} batch={} wp={} readings={} deviations={} held={}",
                factoryId, batchNumber, cmd.workProcessId(), saved.size(), deviations, held);
        return new RecordResult(toViews(saved), deviations, held, message);
    }

    /** 处理偏离: 填纠偏措施 (必填) + 处理人/时间。处理完, 这条不再挡放行和出货。 */
    @Transactional
    public HaccpMonitoringRecord handleDeviation(String factoryId, Long recordId, String action,
                                                 Long userId, String userName) {
        if (action == null || action.isBlank()) {
            throw new BusinessException(400, "请填写纠偏措施")
                    .withCode(CODE_INVALID)
                    .withHint("例如：继续加热 5 分钟后复测中心温度 78℃，合格")
                    .withSeverity("BLOCKING")
                    .withHintTarget("deviationAction");
        }
        HaccpMonitoringRecord rec = recordRepo.findByIdAndFactoryIdForUpdate(recordId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "CCP 记录不存在或不属于本工厂: " + recordId)
                        .withCode(CODE_NOT_DEVIATION).withHint("请刷新 CCP 记录列表"));
        if (!rec.isOpenDeviation()) {
            throw new BusinessException(409, rec.isDeviation() ? "这条偏离已经处理过了" : "这条记录在限值内，不是偏离")
                    .withCode(CODE_NOT_DEVIATION)
                    .withHint("请刷新 CCP 记录列表");
        }
        rec.setDeviationAction(action.trim());
        rec.setDeviationHandledAt(LocalDateTime.now());
        rec.setDeviationHandledBy(userId);
        rec.setDeviationHandledByName(userName);
        return recordRepo.save(rec);
    }

    /** 一个批次相关的全部 CCP 记录: 批号命中 或 同一生产计划 (成品批号 → 它的计划 → 整条链)。 */
    @Transactional(readOnly = true)
    public List<HaccpMonitoringRecord> listForBatch(String factoryId, String batchNumber) {
        if (batchNumber == null || batchNumber.isBlank()) {
            throw invalid("请输入批次号");
        }
        String no = batchNumber.trim();
        Set<String> plans = new LinkedHashSet<>();
        productionBatchRepo.findByFactoryIdAndBatchNumber(factoryId, no)
                .map(ProductionBatch::getProductionPlanId).ifPresent(plans::add);
        finishedGoodsRepo.findByFactoryIdAndBatchNumber(factoryId, no)
                .map(FinishedGoodsBatch::getProductionPlanId).ifPresent(plans::add);
        plans.remove(null);
        return recordRepo.findForBatchView(factoryId, Set.of(no), plans.isEmpty() ? Set.of("#NO-PLAN#") : plans);
    }

    /** 按批次导出 CSV (UTF-8 BOM, Excel 直接打开不乱码)。 */
    @Transactional(readOnly = true)
    public byte[] exportBatchCsv(String factoryId, String batchNumber) {
        List<HaccpMonitoringRecord> rows = listForBatch(factoryId, batchNumber);
        Set<Long> cpIds = new HashSet<>();
        rows.forEach(r -> cpIds.add(r.getCheckpointId()));
        Map<Long, HaccpCheckpoint> cps = checkpointRepo.findAllById(cpIds).stream()
                .collect(Collectors.toMap(HaccpCheckpoint::getId, Function.identity(), (a, b) -> a));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("监控时间,批次号,工序序,CCP编码,CCP名称,实测值,下限,上限,单位,结果,录入人,来源,纠偏措施,处理人,处理时间,备注\n");
        for (HaccpMonitoringRecord r : rows) {
            HaccpCheckpoint cp = cps.get(r.getCheckpointId());
            BigDecimal min = r.getCriticalLimitMinSnapshot() != null ? r.getCriticalLimitMinSnapshot()
                    : cp != null ? cp.getCriticalLimitMin() : null;
            BigDecimal max = r.getCriticalLimitMaxSnapshot() != null ? r.getCriticalLimitMaxSnapshot()
                    : cp != null ? cp.getCriticalLimitMax() : null;
            String unit = r.getUnitSnapshot() != null ? r.getUnitSnapshot() : cp != null ? cp.getUnit() : "";
            String result = !r.isDeviation() ? "合格" : r.getDeviationHandledAt() == null ? "偏离-未处理" : "偏离-已处理";
            sb.append(csv(r.getMonitoringTime() == null ? "" : r.getMonitoringTime().format(fmt))).append(',')
                    .append(csv(r.getBatchNumber())).append(',')
                    .append(csv(r.getProcessOrder() == null ? "" : String.valueOf(r.getProcessOrder()))).append(',')
                    .append(csv(cp == null ? String.valueOf(r.getCheckpointId()) : cp.getCheckpointCode())).append(',')
                    .append(csv(cp == null ? "" : cp.getName())).append(',')
                    .append(csv(plain(r.getMeasuredValue()))).append(',')
                    .append(csv(plain(min))).append(',')
                    .append(csv(plain(max))).append(',')
                    .append(csv(unit)).append(',')
                    .append(csv(result)).append(',')
                    .append(csv(r.getOperatorName() != null ? r.getOperatorName() : String.valueOf(r.getOperatorUserId())))
                    .append(',')
                    .append(csv(sourceLabel(r.getSource()))).append(',')
                    .append(csv(r.getDeviationAction())).append(',')
                    .append(csv(r.getDeviationHandledByName() != null ? r.getDeviationHandledByName()
                            : r.getDeviationHandledBy() == null ? "" : String.valueOf(r.getDeviationHandledBy())))
                    .append(',')
                    .append(csv(r.getDeviationHandledAt() == null ? "" : r.getDeviationHandledAt().format(fmt)))
                    .append(',')
                    .append(csv(r.getNotes())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 内部 ====================

    private static String sourceLabel(String source) {
        if (SOURCE_APP.equals(source)) {
            return "App 报工";
        }
        if (SOURCE_WEB.equals(source)) {
            return "逐道录入";
        }
        return source == null ? "手工" : source;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** CSV 单元格转义; 以 = + - @ 开头的加单引号, 防 Excel 公式注入。 */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message).withCode(CODE_INVALID).withSeverity("BLOCKING");
    }
}
