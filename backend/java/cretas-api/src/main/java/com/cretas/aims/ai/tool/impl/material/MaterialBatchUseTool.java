package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.WritePreviewSupport;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    /**
     * 「批次号 → 真实主键」的解析用。⛔ 不设 required=false: 缺了它整条批次号回退静默失效,
     * 一线说批次号永远「找不到」(2026-10-06 prod 事故原形), 宁可启动时就炸。
     * 只读 {@code findByFactoryIdAndBatchNumber} (工厂隔离)。
     */
    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    /** 只说了原料名时, 追问里最多列几个候选批次。 */
    static final int MAX_CANDIDATES = 5;
    /** 候选搜索一次最多取多少条再在内存里按「可用 + 有剩余」过滤。 */
    private static final int CANDIDATE_SEARCH_SIZE = 50;

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

        // batchId: 批次号（必需）。一线只认得批次号 (如 MT-20260829-9687), 认不得内部主键;
        // 工具内部「先按主键、再按批次号」解析 (见 resolveBatch)。
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "原材料批次号，例如 MT-20260829-9687。用户只说了原料名称、没说批次号时，"
                + "原样填用户说的原料名称，不要编造批次号");
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

        // ⛔ 不再给大模型一个 reason 槽: 写路径从不落库 reason (见 doPreview 注释), 留着它只会让模型
        // 自己编一句「生产使用」填进去 (2026-10-06 prod 日志: 抽参抽出 [batchId, reason, quantity])。

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
        String batchRef = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");

        // 2. 参数验证
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("消耗数量必须大于0");
        }

        // 2.5 批次解析: 用户给的是批次号 (或老调用方给的主键), 写路径必须拿真实主键。
        //     解析不到 = 与预览同一句人话, 不把「按主键查不到」的内部措辞漏给一线。
        ResolvedBatch resolved = resolveBatch(factoryId, batchRef);
        if (resolved == null) {
            throw new ResourceNotFoundException(BATCH_NOT_FOUND_MESSAGE);
        }
        String batchId = resolved.id();

        log.info("📦 执行原料消耗 - 批次: {} (用户给的: {}), 数量: {}, 生产计划: {}",
                batchId, batchRef, quantity, productionPlanId);

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

        // 先按主键、再按批次号 (写路径同一个 resolveBatch)。别厂的批次与「不存在」同一个说法, 不泄露存在性。
        ResolvedBatch resolved = resolveBatch(factoryId, batchId);
        if (resolved == null) {
            // 既不是主键也不是批次号 ⇒ 多半是只说了原料名: 列出本工厂该原料名下能领的批次让用户选
            Map<String, Object> askOrBlock = askWhichBatch(factoryId, batchId);
            if (askOrBlock != null) {
                return askOrBlock;
            }
            return WritePreviewSupport.blocked("BATCH_NOT_FOUND", BATCH_NOT_FOUND_MESSAGE, null);
        }
        MaterialBatchDTO batch = resolved.dto();

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

    // ==================== 批次解析 (预览 / 执行共用) ====================

    static final String BATCH_NOT_FOUND_MESSAGE = "找不到这个原料批次，或它不属于本工厂。请核对批次号后再试。";

    /** 解析结果: {@code id} 一定是真实主键 (写路径用它), {@code dto} 是已通过本工厂校验的批次。 */
    private record ResolvedBatch(String id, MaterialBatchDTO dto) {
    }

    /**
     * 把用户给的「批次」解析成真实批次。<b>预览与执行必须共用这一个方法</b>, 否则预览能找到、执行找不到 (或反过来)。
     *
     * <ol>
     *   <li>先按主键查 (老行为, 老调用方 / 前端按钮传的是主键), 且必须属于本工厂;</li>
     *   <li>查不到再按 factoryId + 批次号查 (一线只认得批次号, 如 MT-20260829-9687), 同样必须属于本工厂;</li>
     *   <li>都不命中返回 null —— 调用方据此决定是「找不到」还是「只说了原料名, 问他要哪个批次」。</li>
     * </ol>
     * 只读。⛔ 不在这里抛异常: 别厂的批次与不存在同一个出口, 不泄露存在性。
     */
    private ResolvedBatch resolveBatch(String factoryId, String ref) {
        if (!WritePreviewSupport.notBlank(ref)) {
            return null;
        }
        String key = ref.trim();
        MaterialBatchDTO byId = readOwnBatch(factoryId, key);
        if (byId != null) {
            return new ResolvedBatch(key, byId);
        }
        Optional<MaterialBatch> byNumber = materialBatchRepository.findByFactoryIdAndBatchNumber(factoryId, key);
        if (byNumber.isPresent() && factoryId.equals(byNumber.get().getFactoryId())
                && WritePreviewSupport.notBlank(byNumber.get().getId())) {
            String realId = byNumber.get().getId();
            MaterialBatchDTO dto = readOwnBatch(factoryId, realId);
            if (dto != null) {
                return new ResolvedBatch(realId, dto);
            }
        }
        return null;
    }

    /** 按主键读批次, 且必须属于本工厂 (service 抛 404 / 403 都当没有; 工具这一层再认一次 factoryId, 双保险)。 */
    private MaterialBatchDTO readOwnBatch(String factoryId, String id) {
        MaterialBatchDTO dto;
        try {
            dto = materialBatchService.getMaterialBatchById(factoryId, id);
        } catch (BusinessException notVisible) {   // 含 ResourceNotFoundException (不存在) 与 403 (别厂)
            return null;
        }
        return dto != null && factoryId.equals(dto.getFactoryId()) ? dto : null;
    }

    /**
     * 用户没给批次号 / 主键 (多半只说了原料名): 在<b>本工厂</b>按名字搜, 把有剩余的可用批次列给他选。
     * 只读; 读法与 App 里批次列表同一个 service 入口 ({@code getMaterialBatchList}, 沿用它的数据范围),
     * 不新加绕过范围的查询。
     *
     * @return 追问 (有候选) / 拦下 (搜到了但没有能领的) / null (连名字都搜不到 ⇒ 调用方回「找不到」)
     */
    private Map<String, Object> askWhichBatch(String factoryId, String ref) {
        String name = ref.trim();
        PageRequest search = PageRequest.of(1, CANDIDATE_SEARCH_SIZE);
        search.setKeyword(name);
        PageResponse<MaterialBatchDTO> page = materialBatchService.getMaterialBatchList(factoryId, search);
        List<MaterialBatchDTO> found = page == null || page.getContent() == null ? List.of() : page.getContent();
        if (found.isEmpty()) {
            return null;
        }
        List<MaterialBatchDTO> usable = new ArrayList<>();
        for (MaterialBatchDTO b : found) {
            if (b != null
                    && MaterialBatchStatus.AVAILABLE.equals(b.getStatus())
                    && b.getRemainingQuantity() != null
                    && b.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0
                    && factoryId.equals(b.getFactoryId())
                    && WritePreviewSupport.notBlank(b.getBatchNumber())) {
                usable.add(b);
            }
        }
        String shownName = name.length() > 20 ? name.substring(0, 20) + "…" : name;
        if (usable.isEmpty()) {
            return WritePreviewSupport.blocked("NO_AVAILABLE_BATCH", "「" + shownName
                    + "」名下现在没有能领用的批次（都已用完或不可用）。请核对原料名称，或直接说批次号。", null);
        }
        List<MaterialBatchDTO> shown = usable.size() > MAX_CANDIDATES ? usable.subList(0, MAX_CANDIDATES) : usable;
        List<String> labels = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (MaterialBatchDTO b : shown) {
            String unit = WritePreviewSupport.notBlank(b.getQuantityUnit()) ? b.getQuantityUnit() : b.getUnit();
            String remaining = WritePreviewSupport.quantityText(b.getRemainingQuantity(), unit);
            labels.add(b.getBatchNumber() + "（"
                    + (WritePreviewSupport.notBlank(b.getMaterialName()) ? b.getMaterialName() + "，" : "")
                    + "剩 " + remaining + "）");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("批次号", b.getBatchNumber());
            c.put("原料", b.getMaterialName());
            c.put("剩余数量", remaining);
            candidates.add(c);
        }
        String question = "要领用哪个批次？「" + shownName + "」现在可用的批次有：" + String.join("、", labels)
                + (usable.size() > MAX_CANDIDATES ? "（只列出前 " + MAX_CANDIDATES + " 个）" : "")
                + "。请直接说批次号。";
        return new BatchChoiceQuestion(question, candidates);
    }

    /**
     * 预览里「要用户再补一句」的出口 —— 根级 {@code success=false, status=NEED_MORE_INFO}, 与基类缺参出口同形状
     * ({@code ToolDispatchService.blockedPreviewResponse} 据此回 NEED_MORE_INFO、不铸确认 token)。
     * 专用 Map 子类: 只有本工具自己构造得出来, {@link #buildSuccessResult} 才会改写它。
     */
    private static final class BatchChoiceQuestion extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        final String question;
        final transient List<Map<String, Object>> candidates;

        BatchChoiceQuestion(String question, List<Map<String, Object>> candidates) {
            this.question = question;
            this.candidates = candidates;
            put("status", "NEED_MORE_INFO");
        }
    }

    @Override
    protected String buildSuccessResult(Object data) {
        if (data instanceof BatchChoiceQuestion q) {
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("status", "NEED_MORE_INFO");
                result.put("needMoreInfo", true);
                result.put("missingParameters", List.of("batchId"));
                result.put("clarificationQuestions", List.of(q.question));
                result.put("message", q.question);
                result.put("error", q.question);
                result.put("candidates", q.candidates);
                return objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                log.error("❌ 构建批次追问结果失败", e);
                return buildErrorResult("响应处理失败");
            }
        }
        return super.buildSuccessResult(data);
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
