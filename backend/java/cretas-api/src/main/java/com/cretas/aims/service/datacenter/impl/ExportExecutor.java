package com.cretas.aims.service.datacenter.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeDerived;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Export 真实执行器. ExportServiceImpl 委托此类做 entity query + SpEL filter + EasyExcel 写.
 *
 * <p>Design: 通过 {@code EntityManager.createQuery("SELECT e FROM " + targetEntity + " e
 * WHERE e.factoryId = :fid")} 反射式查询. 然后用 Jackson 把 entity 转 Map, 应用 SpEL filter,
 * 最后通过 EasyExcel WriteSheet.head(List&lt;List&lt;String&gt;&gt;) + doWrite(List&lt;List&lt;Object&gt;&gt;)
 * 写文件 (复用 utils/ExcelUtil 模式).
 *
 * <p>Sprint 4 Chat K C-EXPORT-CENTER-1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportExecutor {

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper;
    private final ProcessSecretPayloadScrubber processSecretScrubber;
    private final ProcessSecretCostMask processSecretCostMask;

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    /** Result wrapper. {@code bytes} populated when {@code targetFile} null; otherwise written to file. */
    public static class Result {
        public final int rowCount;
        public final long fileSizeBytes;
        public final byte[] bytes;

        public Result(int rowCount, long fileSizeBytes, byte[] bytes) {
            this.rowCount = rowCount;
            this.fileSizeBytes = fileSizeBytes;
            this.bytes = bytes;
        }
    }

    /**
     * 同步执行. 若 targetFile 为 null, 数据写入内存返 bytes; 否则写文件返空 bytes.
     *
     * @param rule          导出规则
     * @param runtimeParams SpEL filter 变量
     * @param targetFile    文件输出目标 (null = 写内存)
     */
    public Result run(ExportRule rule, Map<String, Object> runtimeParams, File targetFile)
            throws Exception {
        List<Map<String, Object>> rows;
        // 规则可以导出任意实体 (含批次成本、配方用量): 和 JSON 出口走同一个工艺保密裁剪,
        // 读取记录在本次查询的作用域里收集
        try (ProcessSecretReadTracker.Scope ignored = ProcessSecretReadTracker.open()) {
            rows = queryAndFilter(rule, runtimeParams);
        }
        List<Map<String, Object>> columns = rule.getColumns();
        List<List<String>> head = buildHead(columns);
        List<List<Object>> data = buildData(rows, columns);

        WriteCellStyle headStyle = new WriteCellStyle();
        WriteCellStyle bodyStyle = new WriteCellStyle();
        HorizontalCellStyleStrategy style = new HorizontalCellStyleStrategy(headStyle, bodyStyle);

        if (targetFile != null) {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建导出目录: " + parent.getAbsolutePath());
            }
            EasyExcel.write(targetFile)
                    .head(head)
                    .registerWriteHandler(style)
                    .sheet(safeSheet(rule.getRuleName()))
                    .doWrite(data);
            return new Result(rows.size(), targetFile.length(), null);
        } else {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                EasyExcel.write((OutputStream) baos)
                        .head(head)
                        .registerWriteHandler(style)
                        .sheet(safeSheet(rule.getRuleName()))
                        .doWrite(data);
                byte[] bytes = baos.toByteArray();
                return new Result(rows.size(), bytes.length, bytes);
            }
        }
    }

    /**
     * 工艺保密: 受保护产品的配比、单位用量、标准出成率, 以及成本合计 (Steve 2026-10-28 选 A) 对无授权账号
     * 置为「已保密」; 平台 / 他厂 / 识别不出调用者 (定时导出) 连数量也不给。规则和审计与 HTTP 响应相同。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> maskProcessSecret(ExportRule rule, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return rows;
        ArrayNode tree = objectMapper.valueToTree(rows);
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        String resourceRef = "EXPORT rule " + rule.getId();
        // 整表导出就是跨批汇总 (Steve 2026-10-26 第三问: 单批可看、汇总不给)
        boolean changed = processSecretScrubber.scrub(tree, actor, rule.getFactoryId(),
                ProcessSecretReadTracker.reads(), resourceRef, true);
        // 调用者看不到的保密产品 (只在有行需要时才查, 免得给与产品无关的导出写审计; 独立审查 L-a)
        String factoryId = rule.getFactoryId();
        Set<String>[] hiddenProtected = new Set[1];
        java.util.function.Supplier<Set<String>> hidden = () -> {
            if (hiddenProtected[0] == null) {
                hiddenProtected[0] = processSecretCostMask.hiddenProtected(factoryId, resourceRef);
            }
            return hiddenProtected[0];
        };
        Map<String, Set<String>> productCache = new HashMap<>();
        boolean anyMasked = changed;
        List<ObjectNode> out = new ArrayList<>(rows.size());
        for (JsonNode node : tree) {
            ObjectNode row = (ObjectNode) node;
            JsonNode marker = row.remove(ProcessSecretPayloadScrubber.MARKER);
            if (marker != null) {
                for (JsonNode hiddenField : marker.path("hiddenFields")) {
                    row.put(hiddenField.asText(), ProcessSecretCostMask.LABEL);
                }
            }
            List<String> costFields = new ArrayList<>();
            List<String> unitCostFields = new ArrayList<>();
            row.fields().forEachRemaining(e -> {
                if (!e.getValue().isNumber()) return;
                if (ProcessSecretPayloadScrubber.isCostTotalField(e.getKey())) costFields.add(e.getKey());
                else if (UNIT_COST_FIELDS.contains(e.getKey())) unitCostFields.add(e.getKey());
            });
            if (!costFields.isEmpty() || !unitCostFields.isEmpty()) {
                RowCost decision = rowCostHidden(row, factoryId, hidden, productCache);
                List<String> toMask = new ArrayList<>();
                if (decision != RowCost.VISIBLE) toMask.addAll(costFields);
                // 单价只在认定属于看不到的保密产品时才遮; 认不出归属的行不遮单价 (采购价不是秘密)
                if (decision == RowCost.LINKED_HIDDEN) toMask.addAll(unitCostFields);
                toMask.forEach(name -> row.put(name, ProcessSecretCostMask.LABEL));
                anyMasked |= !toMask.isEmpty();
            }
            out.add(row);
        }
        if (!anyMasked) return rows;
        List<Map<String, Object>> masked = new ArrayList<>(out.size());
        for (ObjectNode row : out) masked.add(objectMapper.convertValue(row, Map.class));
        return masked;
    }

    /** 单价 / 单位成本类字段: 半成品、成品、跨计划领用的单价就是滚进配方金额的单位成本。 */
    static final Set<String> UNIT_COST_FIELDS = Set.of("unitPrice", "unitCost");

    /**
     * 这一行的成本要不要遮:
     * <ul>
     *   <li>行上的产品, 或它的计划 / 生产批次 / 物料批次牵涉的产品里, 有调用者看不到的保密产品
     *       (受限计划的副产、中间品, 跨计划领用的半成品; 独立审查 N-M1 / N-M2);</li>
     *   <li>行上什么归属都没有 (半成品流水、返工记录、工序配方), 而本厂有调用者看不到的保密产品:
     *       认不出它属于谁, 宁可多遮 (M3)。</li>
     * </ul>
     */
    enum RowCost { VISIBLE, LINKED_HIDDEN, UNATTRIBUTED_HIDDEN }

    RowCost rowCostHidden(ObjectNode row, String factoryId, java.util.function.Supplier<Set<String>> hidden,
                          Map<String, Set<String>> productCache) {
        Set<String> linked = new LinkedHashSet<>();
        String productTypeId = row.path("productTypeId").asText(null);
        if (productTypeId != null && !productTypeId.isBlank()) linked.add(productTypeId);
        String planId = row.path("productionPlanId").asText(null);
        Long productionBatchId = row.path("productionBatchId").canConvertToLong()
                ? row.path("productionBatchId").asLong() : null;
        boolean hasLink = !linked.isEmpty() || (planId != null && !planId.isBlank()) || productionBatchId != null;
        if ((planId != null && !planId.isBlank()) || productionBatchId != null) {
            linked.addAll(processSecretCostMask.productsOf(factoryId, planId, productionBatchId, productCache));
        }
        if (row.has("sourceDocType")) {
            // 物料批次本身: 生产批次产出的半成品批次按来源计划认, 采购批次与产品无关 (采购价照给)
            hasLink = true;
            linked.addAll(processSecretCostMask.materialBatchProducts(factoryId, productTypeId,
                    row.path("sourceDocType").asText(null), row.path("sourceDocId").asText(null), productCache));
        }
        String materialBatchId = row.path("batchId").isTextual() ? row.path("batchId").asText() : null;
        if (materialBatchId != null) {
            hasLink = true;
            linked.addAll(processSecretCostMask.materialBatchProducts(factoryId, materialBatchId, productCache));
        }
        if (!linked.isEmpty()) {
            return ProcessSecretCostMask.isHiddenAmong(linked, hidden.get()) ? RowCost.LINKED_HIDDEN : RowCost.VISIBLE;
        }
        if (hasLink) return RowCost.VISIBLE;
        return hidden.get().isEmpty() ? RowCost.VISIBLE : RowCost.UNATTRIBUTED_HIDDEN;
    }

    /** 仅做 entity query + SpEL filter, 返 Map list. 单独抽出便于 unit test. */
    List<Map<String, Object>> queryAndFilter(ExportRule rule, Map<String, Object> runtimeParams) {
        String targetEntity = rule.getTargetEntity();
        if (targetEntity == null || targetEntity.isEmpty()) {
            throw new IllegalArgumentException("rule.targetEntity 必填");
        }
        String jpql = "SELECT e FROM " + targetEntity + " e WHERE e.factoryId = :fid";
        List<?> entities;
        try {
            entities = em.createQuery(jpql)
                    .setParameter("fid", rule.getFactoryId())
                    .getResultList();
        } catch (Exception ex) {
            throw new IllegalStateException("查询 entity 失败 (检查 targetEntity 是否含 factoryId 字段): "
                    + ex.getMessage(), ex);
        }

        List<Map<String, Object>> result = new ArrayList<>(entities.size());
        Expression filter = null;
        if (rule.getFilterExpression() != null && !rule.getFilterExpression().isBlank()) {
            try {
                filter = SPEL.parseExpression(rule.getFilterExpression());
            } catch (Exception ex) {
                log.warn("[ExportExecutor] SpEL filter parse fail: {} — 忽略 filter", ex.getMessage());
            }
        }

        List<Map<String, Object>> converted = new ArrayList<>(entities.size());
        for (Object e : entities) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = objectMapper.convertValue(e, Map.class);
            // 消耗记录里按配方自动带出的行 (调料 / 包材 / BOM 自动扣料): 与 JSON 出口同口径标 automatic
            if (row.get("sourceType") instanceof String sourceType
                    && ProcessSecretRecipeDerived.isAutomaticConsumption(sourceType)) {
                row.put("automatic", true);
            }
            converted.add(row);
        }
        // 先遮后筛: 规则的筛选条件只能看到遮过的值, 不能拿它对隐藏的成本二分试探
        for (Map<String, Object> row : maskProcessSecret(rule, converted)) {
            if (filter != null && !evalFilter(filter, row, runtimeParams)) continue;
            result.add(row);
        }
        return result;
    }

    /**
     * SECURITY (2026-05-22 Sprint 10 P0 #5 hotfix per BI audit):
     * <p>Previously used {@link org.springframework.expression.spel.support.StandardEvaluationContext}
     * which exposes full Java reflection (T(Runtime).getRuntime().exec() RCE etc.) to ANY
     * admin who can set ExportRule.filterExpression. Replaced with
     * {@link SimpleEvaluationContext} (read-only data binding) — restricts SpEL to property
     * reads on the supplied row/params, blocks T(...), constructors, static methods, and
     * arbitrary bean references.
     * <p>Pattern mirrors {@link com.cretas.aims.engine.SpelConditionEvaluator} Round 5 SEC-2 fix.
     * <p>Other unsafe SpEL sites flagged by audit (StateMachineServiceImpl, MetricFormulaServiceImpl,
     * LoggableAspect) tracked as Sprint 11 follow-up — each has distinct attacker-surface
     * analysis and is out of this hotfix scope.
     */
    static boolean evalFilter(Expression expr, Map<String, Object> row, Map<String, Object> runtimeParams) {
        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            ctx.setVariable("row", row);
            if (runtimeParams != null) {
                runtimeParams.forEach(ctx::setVariable);
            }
            Object v = expr.getValue(ctx);
            return v instanceof Boolean && (Boolean) v;
        } catch (Exception ex) {
            log.debug("[ExportExecutor] SpEL eval failed for row: {}", ex.getMessage());
            return false;
        }
    }

    static List<List<String>> buildHead(List<Map<String, Object>> columns) {
        List<List<String>> head = new ArrayList<>(columns.size());
        for (Map<String, Object> col : columns) {
            Object header = col.getOrDefault("header", col.get("field"));
            List<String> h = new ArrayList<>(1);
            h.add(header == null ? "" : String.valueOf(header));
            head.add(h);
        }
        return head;
    }

    static List<List<Object>> buildData(List<Map<String, Object>> rows, List<Map<String, Object>> columns) {
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<Object> r = new ArrayList<>(columns.size());
            for (Map<String, Object> col : columns) {
                Object field = col.get("field");
                if (field == null) {
                    r.add(null);
                } else {
                    r.add(row.get(String.valueOf(field)));
                }
            }
            data.add(r);
        }
        return data;
    }

    /** Excel sheet name 不允许特殊字符 且 ≤31 chars. */
    static String safeSheet(String name) {
        if (name == null || name.isEmpty()) return "Sheet1";
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
