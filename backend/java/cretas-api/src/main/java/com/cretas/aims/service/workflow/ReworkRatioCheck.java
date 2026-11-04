package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.ReworkRatioDeviation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.gate.GateDiagnosis;
import com.cretas.aims.service.gate.GateId;
import com.cretas.aims.service.gate.GateRoutes;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺口 03「回料比例上限」(Steve 2026-11-03 裁定): 按产品设上限, 不设就不拦;
 * 超限必须填原因才能正式提交, 填了放行并留痕 (做法同 {@link BlendRatioCheck})。
 *
 * <p>占比 = 本行回料投入 (手工 + 自动, 只认服务端 is_rework=true 的 SFI) / 主料投入 × 100;
 * 主料 = 行投入量 − 回料, 与 {@code ReworkPolicyAutoDeduction} 同分母。
 * 过期 / 客户不符沿用效期闸与 IntermediateOwnershipGuard, 本类不碰。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReworkRatioCheck {

    public static final String ERROR_CODE = "REWORK_RATIO_REASON_REQUIRED";
    public static final String INVALID_REASON_CODE = "REWORK_RATIO_REASON_INVALID";
    public static final String REASON_OTHER = "其他";
    public static final List<String> STANDARD_REASONS =
            List.of("库存回料需尽快消化", "按主管口头调整", "主料不足", "品质允许", REASON_OTHER);
    public static final String STATUS_WITHIN = "WITHIN";
    public static final String STATUS_OVER_WITH_REASON = "OVER_WITH_REASON";
    private static final int NOTE_MAX = 200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SemiFinishedInventoryRepository sfiRepository;
    private final ProductTypeRepository productTypeRepository;

    /** 写回 {@code req.reworkRatio}; 超限没原因抛 409。不适用 (没设上限 / 没回料 / 投入量未知) 清成 null。 */
    public void enforce(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (req == null) return;
        ReworkRatioDeviation submitted = req.getReworkRatio();
        req.setReworkRatio(null); // 客户端传的快照不作数
        if (req.getUpstreamSources() == null || req.getInputQuantity() == null || req.getProductTypeId() == null) {
            return;
        }
        BigDecimal limit = productTypeRepository.findByIdAndFactoryId(req.getProductTypeId(), factoryId)
                .map(ProductType::getMaxReworkRatioPercent).orElse(null);
        if (limit == null || limit.signum() <= 0) {
            return; // 没设上限 ⇒ 不拦
        }
        BigDecimal rework = BigDecimal.ZERO;
        for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
            if (ref == null || !ref.isSemiFinished() || ref.getSourceBatchNumber() == null
                    || ref.getSourceBatchNumber().isBlank() || ref.getFeedQuantityKg() == null
                    || ref.getFeedQuantityKg().signum() <= 0) {
                continue;
            }
            SemiFinishedInventory src = sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                    factoryId, ref.getSourceBatchNumber().trim()).orElse(null);
            if (src != null && Boolean.TRUE.equals(src.getIsRework())) {
                rework = rework.add(ref.getFeedQuantityKg());
            }
        }
        req.setReworkRatio(judge(factoryId, planId, limit, rework, req.getInputQuantity(), submitted));
    }

    /**
     * App 报工 (YieldReportServiceImpl) 的同一道闸: 领用的源半成品是回料 (服务端 is_rework=true) 时,
     * 回料占比 = 回料量 / (本道投入 − 回料量); 超过本道产品上限必须带原因, 快照并入
     * {@code report.customFields.reworkRatio}。没设上限 / 不是回料 / 投入量未知 ⇒ 不拦、不写快照。
     *
     * @param totalInput 本道投入合计 (含回料, 与 web 工序表的 inputQuantity 同口径)
     */
    public void enforceYieldReport(String factoryId, WorkProcessTask task, SemiFinishedInventory sourceWip,
                                   BigDecimal reworkQuantity, BigDecimal totalInput,
                                   ReworkRatioDeviation submitted, ProductionReport report) {
        if (task == null || report == null || sourceWip == null || reworkQuantity == null
                || reworkQuantity.signum() <= 0 || totalInput == null || task.getProductTypeId() == null
                || !Boolean.TRUE.equals(sourceWip.getIsRework())) {
            return;
        }
        BigDecimal limit = productTypeRepository.findByIdAndFactoryId(task.getProductTypeId(), factoryId)
                .map(ProductType::getMaxReworkRatioPercent).orElse(null);
        if (limit == null || limit.signum() <= 0) {
            return; // 没设上限 ⇒ 不拦
        }
        ReworkRatioDeviation snap = judge(factoryId, String.valueOf(task.getId()), limit, reworkQuantity,
                totalInput, submitted);
        if (snap == null) {
            return;
        }
        Map<String, Object> fields = report.getCustomFields() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(report.getCustomFields());
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("status", snap.getStatus());
        audit.put("limitPercent", snap.getLimitPercent());
        audit.put("actualPercent", snap.getActualPercent());
        audit.put("reworkKg", snap.getReworkKg());
        audit.put("mainKg", snap.getMainKg());
        audit.put("reason", snap.getReason());
        audit.put("note", snap.getNote());
        fields.put("reworkRatio", audit);
        report.setCustomFields(fields);
    }

    /** 算占比并裁决; 回料量 ≤ 0 返回 null (不适用)。超限没原因 / 原因不合法 / 选「其他」没说明 → 抛错。 */
    private ReworkRatioDeviation judge(String factoryId, String contextId, BigDecimal limit, BigDecimal rework,
                                       BigDecimal totalInput, ReworkRatioDeviation submitted) {
        if (rework.signum() <= 0) {
            return null;
        }
        BigDecimal main = totalInput.subtract(rework);
        BigDecimal actual = main.signum() <= 0
                ? HUNDRED // 全是回料: 按 100% 判, ⛔ 不除以 0
                : rework.multiply(HUNDRED).divide(main, 4, RoundingMode.HALF_UP);
        ReworkRatioDeviation snap = ReworkRatioDeviation.builder()
                .limitPercent(limit).actualPercent(actual.setScale(2, RoundingMode.HALF_UP))
                .reworkKg(rework).mainKg(main.signum() <= 0 ? BigDecimal.ZERO : main)
                .status(STATUS_WITHIN).build();
        if (actual.compareTo(limit) <= 0) {
            return snap;
        }
        String text = "回料占比 " + plain(snap.getActualPercent()) + "%，超过该产品上限 " + plain(limit) + "%";
        String reason = submitted == null || submitted.getReason() == null ? "" : submitted.getReason().trim();
        String note = submitted == null || submitted.getNote() == null ? "" : submitted.getNote().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(409, text + "，必须填写超限原因")
                    .withCode(ERROR_CODE)
                    .withHint("请核对回料投入量；确实要这样投的，选择超限原因（选「其他」需写明）后再正式报工")
                    .withHintTarget("回料超限原因")
                    .withSeverity("BLOCKING")
                    .withDiagnosis(GateDiagnosis.of(GateId.REWORK_RATIO, ERROR_CODE, "WORKFLOW_STEP", text).resolve("核对回料投入量; 确按此投的, 选择超限原因 (选「其他」需写明) 后再正式报工", null).build());
        }
        if (!STANDARD_REASONS.contains(reason)) {
            throw new BusinessException(400, "回料超限原因「" + reason + "」不在可选范围内")
                    .withCode(INVALID_REASON_CODE)
                    .withHint("请从下拉中选择：" + String.join(" / ", STANDARD_REASONS))
                    .withHintTarget("回料超限原因")
                    .withSeverity("BLOCKING");
        }
        if (REASON_OTHER.equals(reason) && note.isEmpty()) {
            throw new BusinessException(409, text + "，原因选了「其他」，请写明具体原因")
                    .withCode(ERROR_CODE)
                    .withHint("在说明框里写明为什么回料占比超过上限")
                    .withHintTarget("回料超限原因")
                    .withSeverity("BLOCKING")
                    .withDiagnosis(GateDiagnosis.of(GateId.REWORK_RATIO, ERROR_CODE, "WORKFLOW_STEP", text).resolve("核对回料投入量; 确按此投的, 选择超限原因 (选「其他」需写明) 后再正式报工", null).build());
        }
        snap.setStatus(STATUS_OVER_WITH_REASON);
        snap.setReason(reason);
        snap.setNote(note.isEmpty() ? null : note.substring(0, Math.min(note.length(), NOTE_MAX)));
        log.info("[rework-ratio] over limit with reason: factory={} ctx={} actual={}% limit={}% reason={}",
                factoryId, contextId, snap.getActualPercent(), limit, reason);
        return snap;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
