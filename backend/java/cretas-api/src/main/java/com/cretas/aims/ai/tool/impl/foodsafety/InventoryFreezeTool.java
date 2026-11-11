package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 冻结库存批次 Tool (WRITE + Preview, BLOCKING) — Sprint 8 P3 Phase B (食品召回闭环 step 5a).
 *
 * <p>把指定 batch 扣住, 阻止该批次进一步用于生产/出货. 召回 SOP 必须先冻结涉事批次以避免次生污染.
 *
 * <p>🔴 <b>冻结 = 改成「质检中」(INSPECTING), 不是 FROZEN</b> (交接单 2026-11-11 第二节第 2 条):
 * <ul>
 *   <li>{@link MaterialBatchStatus#FROZEN} 是「冻品」—— 鲜品转冻品 ({@code POST /material-batches/{id}/convert-to-frozen})
 *       之后的正常库存状态, 冻品要照常投产 (供应 / 统计 / 处置白名单都把它当库存)。原来写 FROZEN 根本挡不住投产,
 *       却提示「冻结后无法用于生产」, 与实际相反。</li>
 *   <li>INSPECTING 是现成的扣留状态: {@link MaterialBatchStatus#blocksProduction()} 拦投产, CCP 偏离扣留
 *       ({@code QcDeviationHold}) 也是把 AVAILABLE 改成 INSPECTING; 解除走「质检放行」(放行留记录), 或判不合格。</li>
 *   <li>已经不可投产的批次 (质检中 / 不良品 / 过期 / 报废) <b>不改状态</b>: 冻结不能把「不合格」覆盖成别的状态,
 *       否则冻结就成了洗白不合格的途径。已用完 / 已耗尽的批次没有库存可扣, 也不改。</li>
 * </ul>
 *
 * <p>防呆:
 * <ul>
 *   <li>R1: preview 显批次当前数量 + 状态, BLOCKING (modal 必须确认)</li>
 *   <li>R2: message 含 batchNumber + 当前数量 (上下文)</li>
 *   <li>R3: reason 必填非空 (validateRequiredParams 强制)</li>
 *   <li>R4: 已扣住 / 本来就不可投产 / 没库存 → 返 status=ALREADY_BLOCKED 不重写</li>
 *   <li>error sticky: BusinessException severity=BLOCKING</li>
 * </ul>
 *
 * <p>Intent Code: {@code INVENTORY_FREEZE}
 */
@Slf4j
@Component
public class InventoryFreezeTool extends AbstractBusinessTool {

    /** 冻结写入的状态: 质检中 (挡投产, 只能经质检放行解除)。 */
    public static final MaterialBatchStatus HOLD_STATUS = MaterialBatchStatus.INSPECTING;

    /** 可以被冻结的状态: 批次此刻能被领用 / 预留 / 投产。其余一律不改。 */
    static final Set<MaterialBatchStatus> FREEZABLE = EnumSet.of(
            MaterialBatchStatus.AVAILABLE,
            MaterialBatchStatus.IN_STOCK,
            MaterialBatchStatus.FRESH,
            MaterialBatchStatus.FROZEN,
            MaterialBatchStatus.RESERVED,
            MaterialBatchStatus.PRODUCING_RESERVED);

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    /** 冻结时写进 notes 的审计标记 (召回损失估算据此认出「被冻结工具扣住」的批次)。 */
    public static final String AUDIT_MARK = "[FREEZE by user=";

    /**
     * 这批是不是被召回冻结扣住的 (召回损失估算用)。
     *
     * @param listedInRecall 召回事件点名了这批: 质检中即算扣住; 没点名时 (兜底全厂扫) 要有本工具的审计标记,
     *                       免得把别的原因在质检中的批次算进召回损失。
     *                       老数据: 本次修复之前冻结写的是 FROZEN, 带审计标记的 FROZEN 仍算扣住; 不带标记的 FROZEN 是冻品。
     */
    public static boolean isHeldForRecall(MaterialBatch batch, boolean listedInRecall) {
        if (batch == null || batch.getStatus() == null) {
            return false;
        }
        boolean marked = batch.getNotes() != null && batch.getNotes().contains(AUDIT_MARK);
        if (batch.getStatus() == HOLD_STATUS) {
            return listedInRecall || marked;
        }
        return batch.getStatus() == MaterialBatchStatus.FROZEN && marked;
    }

    @Override
    public String getToolName() {
        return "inventory_freeze";
    }

    @Override
    public String getDescription() {
        return "冻结库存批次 — 把指定 batch 扣住 (改成「质检中」), 阻止用于生产/出货, 解除须经质检放行. "
                + "WRITE + Preview (BLOCKING). "
                + "LLM 触发: '冻结 B-X 库存' / '封存这批' / '召回先冻结库存' / "
                + "'这批不许再发货'. 召回 SOP 第一步.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填). e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "冻结原因 (必填). e.g. '客户投诉拉肚子, 启动召回'");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "reason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "reason");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.HIGH;
    }

    private static String label(MaterialBatchStatus st) {
        return st == null ? "未知" : st.getDisplayName();
    }

    /** 不改状态的原因; null = 可以冻结。 */
    static String notFreezableReason(MaterialBatch batch) {
        MaterialBatchStatus st = batch.getStatus();
        if (st != null && FREEZABLE.contains(st)) {
            return null;
        }
        if (st != null && st.blocksProduction()) {
            return String.format("批次 %s 当前是「%s」, 本来就不能用于生产/出货, 不改状态 (冻结不覆盖「%s」)",
                    batch.getBatchNumber(), label(st), label(st));
        }
        return String.format("批次 %s 当前是「%s」, 没有可冻结的库存, 不改状态", batch.getBatchNumber(), label(st));
    }

    /**
     * Preview: 查批次当前状态 + 数量, 返 BLOCKING 提示用户必须确认.
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String reason = getString(params, "reason");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("severity", "BLOCKING");
        result.put("batchNumber", batchNumber);
        result.put("reason", reason);

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ 批次 %s 不存在, 无法冻结", batchNumber));
            return result;
        }

        MaterialBatch batch = opt.get();
        result.put("materialTypeId", batch.getMaterialTypeId());
        result.put("currentStatus", batch.getStatus() != null ? batch.getStatus().name() : null);
        result.put("currentQuantity", batch.getReceiptQuantity());
        result.put("quantityUnit", batch.getQuantityUnit());

        String blocked = notFreezableReason(batch);
        if (blocked != null) {
            result.put("canDo", false);
            result.put("status", "ALREADY_BLOCKED");
            result.put("message", "ℹ️ " + blocked);
            return result;
        }

        result.put("canDo", true);
        result.put("targetStatus", HOLD_STATUS.name());
        result.put("message", String.format(
                "🚨 [BLOCKING] 确认冻结 → 批次 %s, %s %s, 原状态「%s」→「%s」. "
                        + "冻结后此批次不能领用投产/出货, 解除须在「质量管理 → 质检放行」放行. 冻结理由: %s",
                batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit(),
                label(batch.getStatus()), label(HOLD_STATUS), reason));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String reason = getString(params, "reason");
        Long userId = getUserId(context);

        log.info("inventory_freeze — factory={} batch={} reason={} userId={}",
                factoryId, batchNumber, reason, userId);

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("批次 %s 不存在, 无法冻结", batchNumber))
                    .withSeverity("BLOCKING");
        }

        MaterialBatch batch = opt.get();
        MaterialBatchStatus prevStatus = batch.getStatus();

        // R4 + 防洗白: 不可投产 / 没库存的批次一律不改状态 (绕过 preview 直接执行也一样)
        String blocked = notFreezableReason(batch);
        if (blocked != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ALREADY_BLOCKED");
            result.put("batchNumber", batchNumber);
            result.put("currentStatus", prevStatus != null ? prevStatus.name() : null);
            result.put("message", "ℹ️ " + blocked);
            return result;
        }

        // 状态更新 + audit notes (NOTE: 仅写 status + 在 notes 追加 audit 行)
        batch.setStatus(HOLD_STATUS);
        String existingNotes = batch.getNotes() != null ? batch.getNotes() : "";
        String auditLine = String.format("[%s] " + AUDIT_MARK + "%s] %s (prev=%s)",
                LocalDateTime.now(), userId, reason, prevStatus);
        batch.setNotes(existingNotes.isEmpty() ? auditLine : existingNotes + "\n" + auditLine);
        materialBatchRepository.save(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("previousStatus", prevStatus != null ? prevStatus.name() : null);
        data.put("currentStatus", HOLD_STATUS.name());
        data.put("quantity", batch.getReceiptQuantity());
        data.put("quantityUnit", batch.getQuantityUnit());
        data.put("frozenBy", userId);
        data.put("reason", reason);
        data.put("actionHint", "/inventory/material-batch?batchNumber=" + batchNumber);

        String message = String.format(
                "✅ 已冻结批次 %s (%s %s), 原状态「%s」→「%s」. 此批次不能领用投产/出货, "
                        + "解除须在「质量管理 → 质检放行」放行",
                batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit(),
                label(prevStatus), label(HOLD_STATUS));
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
