package com.cretas.aims.ai.tool.impl.lineage;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.source.SourceIndividualQueryTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 批次溯源查询 Tool — Sprint 11 D4; 2026-09-15 加「按批次号」+「来自哪几头牛」。
 *
 * <p>AI 工厂 chat 入口: 老板问 "这批成品用了哪批原料" / "这批原料流向了哪些客户" /
 * "批次 SL-DEMO-BATCH-V1 来自哪几头牛" 时, LLM 路由到 lineage_query Tool。
 *
 * <h3>批次怎么定位</h3>
 * <ul>
 *   <li>{@code batch_number}（批号, 人在手机上念得出来的那个）优先: 按 {@code batch_type}
 *       到对应仓库精确匹配 (factoryId, batchNumber) 取主键。<b>查不到就说「没有批次 X」</b>,
 *       ⛔ 不模糊匹配、不拿批号当主键继续查 —— 后者会把「不存在」翻译成「没有溯源信息」。</li>
 *   <li>{@code batch_id}（主键）是老用法, 保留。</li>
 *   <li>{@code batch_type} 缺省 {@code PRODUCTION_BATCH} —— 手机上问「这批」十有八九指生产批次。</li>
 * </ul>
 *
 * <h3>图从哪来</h3>
 * 图由 {@link LineageGraphAssembler} 组装（它同时解析批次实体和源头个体）, 本工具只做
 * 「投影成 AI 能读的 map + 一段人话」。⛔ 不再自己读 closure 表 —— 那会是同一张图的第二份（形态 D）。
 * 直接边 (quantityUsed / unit / eventTime / meta) 仍从 {@code batch_lineage_edges} 取, assembler 不暴露它们。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Sprint 11 D4)
 */
@Slf4j
@Component
public class LineageQueryTool extends AbstractBusinessTool {

    @Autowired
    private LineageGraphAssembler lineageGraphAssembler;

    @Autowired
    private BatchLineageEdgeRepository edgeRepository;

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Autowired
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Autowired
    private SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    /** 允许的 batch_type 取值 (per BatchLineageEdge.sourceType 枚举). */
    private static final Set<String> ALLOWED_BATCH_TYPES = Set.of(
            "MATERIAL_BATCH", "PRODUCTION_BATCH", "FINISHED_BATCH", "SHIPMENT_RECORD");

    static final String DEFAULT_BATCH_TYPE = "PRODUCTION_BATCH";

    /** 允许的 direction 取值. */
    private static final Set<String> ALLOWED_DIRECTIONS = Set.of("ANCESTORS", "DESCENDANTS", "BOTH");

    /** maxDepth 上限. */
    private static final int MAX_DEPTH_CAP = 10;
    private static final int DEFAULT_MAX_DEPTH = 5;

    private static final Map<String, String> TYPE_LABELS = Map.of(
            "MATERIAL_BATCH", "原料批次",
            "PRODUCTION_BATCH", "生产批次",
            "FINISHED_BATCH", "成品批次",
            "SHIPMENT_RECORD", "发货记录");

    @Override
    public String getToolName() {
        return "lineage_query";
    }

    @Override
    public String getDescription() {
        return "查询批次溯源 — 直接给批次号 (batch_number, 如 SL-DEMO-BATCH-V1) 就行, 不用主键; "
                + "返回该批次的全部祖先 (用了哪些原料 / 来自哪几头牛, 含每头的耳标·品种·来源场·DNA 状态) "
                + "和全部后代 (流向哪些下游/客户). 适用: \"这批来自哪几头牛\" / "
                + "\"SL-DEMO-BATCH-V1 用了哪些原料\" / \"这批原料发给了谁\" / \"召回这批要查哪些客户\". "
                + "batch_type 缺省 PRODUCTION_BATCH, 支持 MATERIAL_BATCH / FINISHED_BATCH / SHIPMENT_RECORD.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 / 批号 (例: SL-DEMO-BATCH-V1, MB-20260915-001). 与 batch_id 二选一, 优先用这个");
        properties.put("batch_number", batchNumber);

        Map<String, Object> batchType = new HashMap<>();
        batchType.put("type", "string");
        batchType.put("description", "批次类型: PRODUCTION_BATCH (生产批次, 默认) / MATERIAL_BATCH (原料批次) / "
                + "FINISHED_BATCH (成品批次) / SHIPMENT_RECORD (发货记录)");
        batchType.put("enum", List.copyOf(ALLOWED_BATCH_TYPES));
        batchType.put("default", DEFAULT_BATCH_TYPE);
        properties.put("batch_type", batchType);

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次主键 — UUID 字符串 (FinishedBatch/MaterialBatch) 或数字字符串 (ProductionBatch). "
                + "只在已经拿到主键时用; 用户说的批号请放 batch_number");
        properties.put("batch_id", batchId);

        Map<String, Object> direction = new HashMap<>();
        direction.put("type", "string");
        direction.put("description", "查询方向: ANCESTORS (仅祖先/上游) / DESCENDANTS (仅后代/下游) / BOTH (默认)");
        direction.put("enum", List.copyOf(ALLOWED_DIRECTIONS));
        direction.put("default", "BOTH");
        properties.put("direction", direction);

        Map<String, Object> maxDepth = new HashMap<>();
        maxDepth.put("type", "integer");
        maxDepth.put("description", "最大跳数 (1-10, 默认 5)");
        maxDepth.put("default", DEFAULT_MAX_DEPTH);
        maxDepth.put("minimum", 1);
        maxDepth.put("maximum", MAX_DEPTH_CAP);
        properties.put("max_depth", maxDepth);

        schema.put("properties", properties);
        // batch_number / batch_id 二选一 —— 单个必填表达不了, 由 validateRequiredParams 判
        schema.put("required", List.of());
        return schema;
    }

    /** 没有单个必填项; 「batch_number / batch_id 至少一个」见 {@link #validateRequiredParams}。 */
    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    /**
     * batch_number 与 batch_id 一个都没给 ⇒ 报 batch_number 缺失, 走基类标准的 NEED_MORE_INFO 出口
     * （根级 needMoreInfo=true, 手机端会当成追问而不是失败）。
     */
    @Override
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        if (hasBatchReference(params)) {
            return new ArrayList<>();
        }
        return List.of("batch_number");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "batch_type" -> "请问要查哪种批次? 原料批次 (MATERIAL_BATCH) / 生产批次 (PRODUCTION_BATCH) / "
                    + "成品批次 (FINISHED_BATCH) / 发货记录 (SHIPMENT_RECORD)?";
            case "batch_number", "batch_id" -> "请问是哪一批? 说批次号就行 (例: SL-DEMO-BATCH-V1)";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        if (!hasBatchReference(params)) {
            // doExecute 被直接调用时的自保（正常入口在 validateRequiredParams 就拦下了）
            return buildSimpleResult(getParameterQuestion("batch_number"),
                    Map.of("status", "NEED_MORE_INFO"));
        }
        String batchType = upperOrDefault(getString(params, "batch_type"), DEFAULT_BATCH_TYPE);
        String batchId = blankToNull(getString(params, "batch_id"));
        String batchNumber = blankToNull(getString(params, "batch_number"));
        String direction = upperOrDefault(getString(params, "direction"), "BOTH");
        Integer maxDepth = getInteger(params, "max_depth", DEFAULT_MAX_DEPTH);

        if (!ALLOWED_BATCH_TYPES.contains(batchType)) {
            return buildSimpleResult(
                    "batch_type 不合法 (允许: " + ALLOWED_BATCH_TYPES + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "batch_type", "value", batchType));
        }
        if (!ALLOWED_DIRECTIONS.contains(direction)) {
            return buildSimpleResult(
                    "direction 不合法 (允许: " + ALLOWED_DIRECTIONS + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "direction", "value", direction));
        }
        int depthCap = Math.min(Math.max(1, maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth), MAX_DEPTH_CAP);

        // 0) 批次号 → 主键。查不到就到此为止, ⛔ 不拿批号当主键往下查。
        if (batchNumber != null) {
            Optional<String> resolved = resolveBatchId(factoryId, batchType, batchNumber);
            if (resolved.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", "BATCH_NOT_FOUND");
                data.put("batchType", batchType);
                data.put("batchNumber", batchNumber);
                return buildSimpleResult(
                        "没有批次 " + batchNumber + " (" + typeLabel(batchType) + "). "
                                + "请核对批次号; 如果是原料批次 / 成品批次, 请说明类型.",
                        data);
            }
            batchId = resolved.get();
        }

        log.info("lineage_query factoryId={} type={} id={} number={} dir={} depth={}",
                factoryId, batchType, batchId, batchNumber, direction, depthCap);

        // 1) 图: 由 assembler 组装 (含批次实体解析 + 源头个体)
        boolean includeDescendants = !"ANCESTORS".equals(direction);
        TraceabilityDTO.LineageSection section = lineageGraphAssembler.assemble(
                factoryId, batchType, batchId, depthCap, includeDescendants, false);
        if (section == null) {
            throw new IllegalStateException("血缘引擎返回 null, 无法组装溯源图");
        }

        List<Map<String, Object>> ancestors = new ArrayList<>();
        List<Map<String, Object>> descendants = new ArrayList<>();
        if (!"DESCENDANTS".equals(direction)) {
            flatten(section.getAncestors(), ancestors);
        }
        if (includeDescendants) {
            flatten(section.getDescendants(), descendants);
        }

        // 2) Direct edges with metadata (quantityUsed / unit / eventTime / meta)
        List<Map<String, Object>> edges = new ArrayList<>();
        if (!"DESCENDANTS".equals(direction)) {
            for (BatchLineageEdge e :
                    edgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, batchId, batchType)) {
                edges.add(serializeEdge(e, "UPSTREAM"));
            }
        }
        if (!"ANCESTORS".equals(direction)) {
            for (BatchLineageEdge e :
                    edgeRepository.findByFactoryIdAndSourceIdAndSourceType(factoryId, batchId, batchType)) {
                edges.add(serializeEdge(e, "DOWNSTREAM"));
            }
        }

        // 3) Build result —— 既有字段名一个不动, 新字段只加不改
        int individualCount = section.getSourceIndividualCount() == null ? 0 : section.getSourceIndividualCount();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchType", batchType);
        data.put("batchId", batchId);
        if (batchNumber != null) {
            data.put("batchNumber", batchNumber);
        }
        data.put("direction", direction);
        data.put("maxDepth", depthCap);
        data.put("ancestors", ancestors);
        data.put("descendants", descendants);
        data.put("edges", edges);
        data.put("ancestorCount", ancestors.size());
        data.put("descendantCount", descendants.size());
        data.put("directEdgeCount", edges.size());
        data.put("sourceIndividualCount", individualCount);

        if (ancestors.isEmpty() && descendants.isEmpty() && edges.isEmpty()) {
            data.put("status", "NO_LINEAGE_FOUND");
            String label = batchNumber != null ? batchNumber : batchType + ":" + batchId;
            return buildSimpleResult(
                    "未找到批次 " + label + " 的溯源信息. "
                            + "可能原因: 还未参与生产 / 还未投料 / lineage 未配置.",
                    data);
        }

        String summary = buildSummary(batchType, batchId, batchNumber, ancestors,
                individualCount, descendants.size(), edges.size(), direction);
        data.put("summary", summary);
        return buildSimpleResult(summary, data);
    }

    // ──────────────────────────────────────────────────────────────────
    // 批次号 → 主键
    // ──────────────────────────────────────────────────────────────────

    /** 按类型到对应仓库精确匹配 (factoryId, batchNumber)。⛔ 不做 LIKE。 */
    private Optional<String> resolveBatchId(String factoryId, String batchType, String batchNumber) {
        return switch (batchType) {
            case "PRODUCTION_BATCH" -> productionBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .map(pb -> String.valueOf(pb.getId()));
            case "MATERIAL_BATCH" -> materialBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .map(mb -> mb.getId());
            case "FINISHED_BATCH" -> finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .map(fg -> fg.getId());
            case "SHIPMENT_RECORD" -> salesDeliveryRecordRepository
                    .findByFactoryIdAndDeliveryNumber(factoryId, batchNumber)
                    .map(dr -> dr.getId());
            default -> Optional.empty();
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // 投影
    // ──────────────────────────────────────────────────────────────────

    /** 按跳数分组的节点 → 扁平 map 列表。nodeType / nodeId / depth 三个老字段不动, 其余追加。 */
    private static void flatten(List<TraceabilityDTO.LineageDepthGroup> groups, List<Map<String, Object>> into) {
        if (groups == null) return;
        for (TraceabilityDTO.LineageDepthGroup g : groups) {
            if (g == null || g.getNodes() == null) continue;
            for (TraceabilityDTO.LineageNode n : g.getNodes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeType", n.getNodeType());
                m.put("nodeId", n.getNodeId());
                m.put("depth", g.getDepth());
                m.put("batchNumber", n.getBatchNumber());
                m.put("name", n.getName());
                m.put("resolved", n.getResolved());
                if (n.getSupplierName() != null) m.put("supplierName", n.getSupplierName());
                if (n.getQuantity() != null) m.put("quantity", n.getQuantity());
                if (n.getUnit() != null) m.put("unit", n.getUnit());
                if (n.getEventTime() != null) m.put("eventTime", n.getEventTime());
                if (n.getSourceIndividual() != null) m.put("sourceIndividual", serializeIndividual(n.getSourceIndividual()));
                into.add(m);
            }
        }
    }

    private static Map<String, Object> serializeIndividual(TraceabilityDTO.SourceIndividualBrief si) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", si.getId());
        m.put("individualCode", si.getIndividualCode());
        m.put("tagNumber", si.getTagNumber());
        m.put("species", si.getSpecies());
        m.put("breed", si.getBreed());
        m.put("originFarm", si.getOriginFarm());
        m.put("dnaVerificationStatus", si.getDnaVerificationStatus());
        return m;
    }

    private Map<String, Object> serializeEdge(BatchLineageEdge e, String relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relation", relation);
        m.put("edgeType", e.getEdgeType());
        m.put("sourceType", e.getSourceType());
        m.put("sourceId", e.getSourceId());
        m.put("targetType", e.getTargetType());
        m.put("targetId", e.getTargetId());
        m.put("quantityUsed", e.getQuantityUsed());
        m.put("unit", e.getUnit());
        m.put("eventTime", e.getEventTime());
        m.put("meta", e.getMeta());
        return m;
    }

    // ──────────────────────────────────────────────────────────────────
    // 人话摘要
    // ──────────────────────────────────────────────────────────────────

    /**
     * 「生产批次 SL-DEMO-BATCH-V1 — 来自 6 头牛: 耳标 … · 西门塔尔杂交 · 山东胜龙自有育肥场 · DNA 已比中; …
     *  上游 8 节点, 下游 2 节点, 5 条直接关联边」
     *
     * <p>个体按档案 id 去重 —— 同一头牛分成两个原料批次时不许被念成两头。
     * ⛔ 摘要里不出现基因库 ID / DNA 样本号（节点上本来就没有这两个字段）。
     */
    private static String buildSummary(String batchType, String batchId, String batchNumber,
                                       List<Map<String, Object>> ancestors, int individualCount,
                                       int descendantCount, int edgeCount, String direction) {
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel(batchType)).append(" ").append(batchNumber != null ? batchNumber : batchId).append(" — ");

        if (!"DESCENDANTS".equals(direction)) {
            List<String> lines = describeIndividuals(ancestors);
            if (individualCount > 0) {
                sb.append("来自 ").append(individualCount).append(" 头牛: ").append(String.join("; ", lines)).append(". ");
            } else if (!ancestors.isEmpty()) {
                sb.append("上游原料批次没登记来自哪头牛. ");
            }
        }

        boolean wroteCount = false;
        if (!"DESCENDANTS".equals(direction) && !ancestors.isEmpty()) {
            sb.append("上游 ").append(ancestors.size()).append(" 节点");
            wroteCount = true;
        }
        if (!"ANCESTORS".equals(direction) && descendantCount > 0) {
            if (wroteCount) sb.append(", ");
            sb.append("下游 ").append(descendantCount).append(" 节点");
            wroteCount = true;
        }
        if (wroteCount) sb.append(", ");
        sb.append(edgeCount).append(" 条直接关联边");
        return sb.toString();
    }

    /** 每头一句「耳标 X · 品种 · 来源场 · DNA 状态」, 按个体 id 去重, 保持首次出现顺序。 */
    @SuppressWarnings("unchecked")
    private static List<String> describeIndividuals(List<Map<String, Object>> ancestors) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : ancestors) {
            Object raw = node.get("sourceIndividual");
            if (!(raw instanceof Map)) continue;
            Map<String, Object> si = (Map<String, Object>) raw;
            String id = String.valueOf(si.get("id"));
            if (byId.containsKey(id)) continue;
            List<String> parts = new ArrayList<>();
            Object tag = si.get("tagNumber");
            parts.add(tag != null ? "耳标 " + tag : "档案 " + si.get("individualCode"));
            if (si.get("breed") != null) parts.add(String.valueOf(si.get("breed")));
            if (si.get("originFarm") != null) parts.add(String.valueOf(si.get("originFarm")));
            parts.add(SourceIndividualQueryTool.dnaLabel((String) si.get("dnaVerificationStatus")));
            byId.put(id, String.join(" · ", parts));
        }
        return new ArrayList<>(byId.values());
    }

    private static String typeLabel(String batchType) {
        return TYPE_LABELS.getOrDefault(batchType, batchType);
    }

    // ──────────────────────────────────────────────────────────────────
    // 小工具
    // ──────────────────────────────────────────────────────────────────

    private static boolean hasBatchReference(Map<String, Object> params) {
        return params != null && (isPresent(params.get("batch_number")) || isPresent(params.get("batch_id")));
    }

    private static boolean isPresent(Object v) {
        return v != null && !v.toString().trim().isEmpty();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String upperOrDefault(String s, String dflt) {
        String u = upper(s);
        return u == null || u.isEmpty() ? dflt : u;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
