package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.WritePreviewSupport;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原材料批次使用/消耗工具
 *
 * 用于记录原材料出库消耗，支持关联生产计划。
 * 执行后会自动更新批次剩余数量，并记录使用历史。
 *
 * 业务规则：
 * 1. 批次必须存在且状态为可用
 * 2. 消耗数量不能超过剩余可用数量
 * 3. 消耗后剩余数量为0时，批次状态自动变为USED_UP
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchUseTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private MaterialBatchService materialBatchService;

    /** 只给预览用 (只读): 与写路径 validateProductionPlanRef 一样可选, 缺省时不校验计划。 */
    @Autowired(required = false)
    private ProductionPlanRepository productionPlanRepository;

    @Override
    public String getToolName() {
        return "material_batch_use";
    }

    @Override
    public String getDescription() {
        return "使用/消耗原材料，记录原料出库。需要指定批次和消耗数量。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "原材料批次ID，用于唯一标识要消耗的批次");
        properties.put("batchId", batchId);

        // quantity: 消耗数量（必需）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "消耗数量，必须大于0且不超过批次剩余可用数量");
        quantity.put("minimum", 0.001);
        properties.put("quantity", quantity);

        // productionPlanId: 生产计划ID（可选）
        Map<String, Object> productionPlanId = new HashMap<>();
        productionPlanId.put("type", "string");
        productionPlanId.put("description", "关联的生产计划ID，用于追溯原料使用去向");
        properties.put("productionPlanId", productionPlanId);

        // reason: 使用原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "使用/消耗原因说明，如：生产使用、样品制作、损耗等");
        reason.put("maxLength", 500);
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "quantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 解析参数
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");
        String reason = getString(params, "reason");

        // 2. 参数验证
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("消耗数量必须大于0");
        }

        log.info("📦 执行原料消耗 - 批次: {}, 数量: {}, 生产计划: {}, 原因: {}",
                batchId, quantity, productionPlanId, reason);

        // 3. 调用服务执行消耗 (operatorId 写入 MaterialConsumption.recordedBy)
        MaterialBatchDTO updatedBatch = materialBatchService.useBatchMaterial(
                factoryId,
                batchId,
                quantity,
                productionPlanId,
                getUserId(context)
        );

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", updatedBatch.getId());
        result.put("batchNumber", updatedBatch.getBatchNumber());
        result.put("materialTypeId", updatedBatch.getMaterialTypeId());
        result.put("consumedQuantity", quantity);
        result.put("remainingQuantity", updatedBatch.getRemainingQuantity());
        result.put("unit", updatedBatch.getUnit());
        result.put("status", updatedBatch.getStatus());
        result.put("productionPlanId", productionPlanId);
        result.put("message", String.format("成功消耗原料批次 %s，消耗数量 %s %s，剩余 %s %s",
                updatedBatch.getBatchNumber(),
                quantity,
                updatedBatch.getUnit(),
                updatedBatch.getRemainingQuantity(),
                updatedBatch.getUnit()));

        log.info("✅ 原料消耗完成 - 批次: {}, 剩余: {}",
                updatedBatch.getBatchNumber(), updatedBatch.getRemainingQuantity());

        return result;
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段。⛔ 只读批次 / 生产计划, 不调 {@code useBatchMaterial}。
     *
     * <p>镜像 {@code MaterialBatchServiceImpl#useBatchMaterial} 的拒绝条件, 同一个顺序:
     * 数量 &gt; 0 → 生产计划存在 → 批次属于本工厂 → 批次状态允许投产 ({@link MaterialBatchStatus#blocksProduction},
     * 与写路径同一份) → 剩余量够。{@code reason} 写路径不会落库, 所以确认卡里也不展示它。
     */
    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID",
                    "领用数量必须大于 0。请告诉我要领多少。", null);
        }

        String planText = null;
        if (productionPlanId != null && !productionPlanId.isBlank() && productionPlanRepository != null) {
            Optional<ProductionPlan> plan = productionPlanRepository.findById(productionPlanId);
            if (plan.isEmpty()) {
                return WritePreviewSupport.blocked("PLAN_NOT_FOUND",
                        "找不到你指定的生产计划，请核对后再试；不用关联生产计划的话不要填这一项。", null);
            }
            planText = factoryId.equals(plan.get().getFactoryId()) && plan.get().getPlanNumber() != null
                    ? plan.get().getPlanNumber() : "已指定的生产计划";
        }

        MaterialBatchDTO batch;
        try {
            batch = materialBatchService.getMaterialBatchById(factoryId, batchId);
        } catch (BusinessException notVisible) {   // 含 ResourceNotFoundException (不存在) 与 403 (别厂)
            // 别厂的批次与「不存在」同一个说法, 不泄露存在性
            return WritePreviewSupport.blocked("BATCH_NOT_FOUND",
                    "找不到这个原料批次，或它不属于本工厂。请核对批次号后再试。", null);
        }

        // DTO 的 unit 字段 mapper 并不填 (execute 返回里的 unit 因此是 null), 真正的单位在 quantityUnit
        String unit = WritePreviewSupport.notBlank(batch.getQuantityUnit()) ? batch.getQuantityUnit() : batch.getUnit();
        String who = batchTitle(batch);
        MaterialBatchStatus status = batch.getStatus();
        if (status != null && status.blocksProduction()) {
            return WritePreviewSupport.blocked("INVALID_STATUS",
                    who + status.productionBlockedReason() + "。" + status.productionBlockedHint() + "。",
                    null);
        }
        BigDecimal remaining = batch.getRemainingQuantity();
        if (remaining.compareTo(quantity) < 0) {
            return WritePreviewSupport.blocked("INSUFFICIENT_STOCK", String.format(
                    "%s只剩 %s，不够领用 %s。请核对数量，或换一个批次。",
                    who, WritePreviewSupport.quantityText(remaining, unit),
                    WritePreviewSupport.quantityText(quantity, unit)), null);
        }

        BigDecimal after = remaining.subtract(quantity);
        boolean usedUp = after.compareTo(BigDecimal.ZERO) == 0;
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("原料批次", who);
        current.put("剩余数量", WritePreviewSupport.quantityText(remaining, unit));
        current.put("批次状态", statusLabel(status));
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("原料批次", who);
        next.put("剩余数量", WritePreviewSupport.quantityText(after, unit));
        // 写路径: 剩余量降到 0 时批次状态变「已用完」
        next.put("批次状态", usedUp ? MaterialBatchStatus.USED_UP.getDisplayName() : statusLabel(status));
        next.put("本次领用", WritePreviewSupport.quantityText(quantity, unit));
        if (planText != null) {
            next.put("用于生产计划", planText);
        }
        String message = String.format("确认从 %s 领用 %s？剩余 %s → %s%s。",
                who, WritePreviewSupport.quantityText(quantity, unit),
                WritePreviewSupport.quantityText(remaining, unit),
                WritePreviewSupport.quantityText(after, unit),
                usedUp ? "，领完后这个批次会变成「已用完」" : "");
        return WritePreviewSupport.ready(message, current, next, null);
    }

    /** 「批次号（原料名）」—— 一线认得出的名字, 不回显内部 id。 */
    private static String batchTitle(MaterialBatchDTO batch) {
        String number = WritePreviewSupport.notBlank(batch.getBatchNumber()) ? batch.getBatchNumber() : "原料批次";
        return WritePreviewSupport.notBlank(batch.getMaterialName())
                ? number + "（" + batch.getMaterialName() + "）" : number;
    }

    private static String statusLabel(MaterialBatchStatus status) {
        return status == null ? WritePreviewSupport.EMPTY : status.getDisplayName();
    }

    /**
     * 覆盖参数问题生成，提供更精确的提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问您要消耗哪个批次的原料？说批次号就行";
            case "quantity":
                return "请问需要消耗多少数量？";
            case "productionPlanId":
                return "请问这次消耗是用于哪个生产计划？（可选）";
            case "reason":
                return "请说明消耗原因。（可选）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    /**
     * 覆盖参数显示名称
     */
    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            case "quantity":
                return "消耗数量";
            case "productionPlanId":
                return "生产计划ID";
            case "reason":
                return "使用原因";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /**
     * 此工具需要写操作权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 与 App 同口径: App 的 {@code POST /material-batches/{id}/use} 要 warehouse/inventory 读写,
     * 一线里只有仓库员 (warehouse_worker) 带这个权限 ⇒ AI 也只多给它 (2026-10-05 一线 AI 入口)。
     * 操作员 / 出成率录入员在 App 里领用不了, 这里也不给 (意图名单也已去掉它们, V20261101_56)。
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "factory_admin".equals(userRole) ||
                "warehouse_manager".equals(userRole) ||
                "warehouse_worker".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
