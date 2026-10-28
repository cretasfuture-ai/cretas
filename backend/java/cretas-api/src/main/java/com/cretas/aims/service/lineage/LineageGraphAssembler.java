package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把 {@code batch_lineage_closure} + {@code batch_lineage_edges} 组装成溯源页能直接渲染的
 * 「按跳数分组的来源链 / 影响面」。
 *
 * <h2>三条硬规矩</h2>
 *
 * <ol>
 *   <li><b>不 N+1</b>：节点名称按 nodeType 分桶后各来一次 {@code findAllById}。
 *       节点总数由 maxDepth 控住，一次组装最多 4 次批量查询 + 2 次关联查询。</li>
 *   <li><b>查不到就说查不到</b>：批次实体可能被软删（血缘边是 forensic 的，永不删），
 *       这时 {@code resolved=false} 且名称字段全 null。⛔ 不编「未知批次」——
 *       那会让「被删了」和「叫未知」不可区分。</li>
 *   <li><b>脱敏是参数不是分支</b>：公开接口与内部接口走同一段组装代码，只差
 *       {@code desensitize} / {@code includeDescendants} 两个开关；⛔ 不写两份组装逻辑（形态 D）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageGraphAssembler {

    /**
     * 血缘跳数上限 —— <b>全仓唯一来源</b>：召回影响面 / 完整溯源 / 公开溯源 / AI {@code lineage_query} /
     * 指标中心批次溯源都从这里取，⛔ 不要在别处再写一个数字。
     *
     * <p>为什么是 50 而不是原来的 10：每过一道工序链上多 <b>2 跳</b>
     * （生产批 → 在制库存批 → 下一道生产批）。prod 样例 LIUSHANMEN 生产批 10898（10 道工序）
     * 闭包补齐后首道原料在 depth 19；原来的默认 5 / 上限 10 只看得到最后 2~5 道工序 ——
     * 召回会漏掉更远的成品，溯源页看不到首道原料。50 跳 ≈ 24 道工序，留足余量。
     *
     * <p>成本：{@code findFullGraph} 走 {@code (factory_id, ancestor_id, ancestor_type)} /
     * {@code (factory_id, descendant_id, descendant_type)} 两个索引（V20260821_02），
     * depth 不在索引里、只是取回后的过滤条件 —— 上限只影响返回行数，不改变访问路径。
     */
    public static final int MAX_DEPTH_CAP = 50;

    /** S3-16 按锅收窄 (可选注入: Mockito @InjectMocks 构造的单测里为 null, 召回结果不变)。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pot.PotTraceService potTraceService;
    /**
     * 缺省跳数 = 上限。召回「漏一个成品」是食品安全事故，溯源「看不到首道原料」等于没溯源，
     * 所以没有「先给个浅的」这一档；调用方真想看浅的就显式传。
     */
    public static final int DEFAULT_MAX_DEPTH = MAX_DEPTH_CAP;

    /** 公开接口上耳标号保留的末位数。 */
    static final int TAG_SUFFIX_VISIBLE = 4;

    private final BatchLineageClosureRepository closureRepository;
    private final BatchLineageEdgeRepository edgeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final SupplierRepository supplierRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    private final CustomerRepository customerRepository;
    /**
     * 源头个体（这块肉来自哪头牛）。与其它节点解析同款：按 id 批量取，⛔ 不在循环里单条查。
     */
    private final SourceIndividualRepository sourceIndividualRepository;

    /**
     * 组装一个批次的血缘视图。
     *
     * @param includeDescendants 是否给下游（公开接口不给）
     * @param desensitize        公开视图开关（公开接口给 true）：抹掉供应商等内部信息，并且
     *                           上游<b>只给原料批、不给中间工序</b>（见 {@link #assemblePublic}）
     * @return 永远非 null；没有任何血缘记录时 {@code ancestors} 是空列表、{@code nodeCount} 为 0
     */
    // 🔴 自带只读事务: 结点解析会碰 MaterialBatch.materialType 这类懒加载代理。
    //    TraceabilityServiceImpl 的调用方本来就在事务里(这里只是加入), 但 AI 的
    //    lineage_query 走手机端的 SSE 流式路径时在 intent-sse 线程上直接调本方法,
    //    没有 OSIV 也没有事务 —— prod 实测 2026-09-15 22:03 手机问「SL-DEMO-BATCH-V1」
    //    LazyInitializationException: could not initialize proxy [RawMaterialType#…] - no Session,
    //    而同一个问题走非流式 /execute (请求线程, 有 OSIV) 答得出来。
    @Transactional(readOnly = true)
    public TraceabilityDTO.LineageSection assemble(String factoryId, String rootType, String rootId,
                                                   int maxDepth, boolean includeDescendants,
                                                   boolean desensitize) {
        return assembleView(factoryId, rootType, rootId, maxDepth, includeDescendants, desensitize).section();
    }

    /**
     * 公开溯源（消费者扫码）的血缘视图 —— <b>只有原料来源，没有中间工序</b>。
     *
     * <p>🔴 Steve 2026-10-25 裁定：公开溯源只显示原料来源，不显示中间工序。往上照样按上限查完整闭包
     * （首道原料在 10 道工序的链上是 depth 19），但对外只给链上的<b>原料批</b>
     * （判据见 {@link #isRawMaterialForPublic}）；中间生产批、在制库存批的名称 / 批号 / 数量 /
     * depth 一律不出。成品本身由公开响应的顶层字段（品名 / 批号）表达。
     *
     * @return 永远非 null。{@code rawMaterials} 是同一批原料的实体，只给服务层拼「原材料来源」卡用，
     *         ⛔ 不直接序列化（它带供应商 id 等内部字段）。
     */
    @Transactional(readOnly = true)
    public PublicLineage assemblePublic(String factoryId, String rootType, String rootId) {
        return assembleView(factoryId, rootType, rootId, MAX_DEPTH_CAP, false, true);
    }

    /** 公开视图 + 它背后的原料实体（同一次查询里得到的同一批，⛔ 不另查一份）。 */
    public record PublicLineage(TraceabilityDTO.LineageSection section, List<MaterialBatch> rawMaterials) {}

    /**
     * 公开侧算不算「原料批」—— 真正外购 / 客供进来的原材料。
     *
     * <p>⛔ 不是原料：{@code source_doc_type='PRODUCTION_BATCH'}（逐道录入时某道工序产出的在制库存批，
     * 也是副产批次走的那条路）、{@code byproduct_source_report_id} 非空（副产）。
     * 判据只看数据字段，⛔ 不靠批号前缀（WIP-）猜。链上另有「生产批 → 它」的边时，
     * {@link #assembleView} 还会按边再排除一次（老数据没填 source_doc_type 也挡得住）。
     */
    public static boolean isRawMaterialForPublic(MaterialBatch mb) {
        return mb != null
                && !LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(mb.getSourceDocType())
                && mb.getByproductSourceReportId() == null;
    }

    private PublicLineage assembleView(String factoryId, String rootType, String rootId,
                                       int maxDepth, boolean includeDescendants, boolean desensitize) {
        int depthCap = clampDepth(maxDepth);
        List<BatchLineageClosure> graph =
                closureRepository.findFullGraph(factoryId, rootId, rootType, depthCap);

        List<BatchLineageClosure> ancestorRows = new ArrayList<>();
        List<BatchLineageClosure> descendantRows = new ArrayList<>();
        for (BatchLineageClosure row : graph == null ? List.<BatchLineageClosure>of() : graph) {
            if (row.getDepth() == null || row.getDepth() <= 0) {
                continue;   // depth 0 是自引用，不是链上的一跳
            }
            if (isSameNode(row.getDescendantType(), row.getDescendantId(), rootType, rootId)) {
                ancestorRows.add(row);
            } else if (isSameNode(row.getAncestorType(), row.getAncestorId(), rootType, rootId)) {
                descendantRows.add(row);
            }
        }

        NodeResolver resolver = resolveNodes(factoryId, ancestorRows, descendantRows, desensitize);
        if (desensitize) {
            // 脱敏视图 = 公开视图: 只给原料, 不给中间工序 (Steve 2026-10-25)。
            return publicRawMaterialView(factoryId, rootType, ancestorRows, resolver);
        }
        EdgeIndex edges = loadEdges(factoryId, rootType, rootId, ancestorRows, descendantRows);

        List<TraceabilityDTO.LineageDepthGroup> ancestors = groupByDepth(
                ancestorRows, BatchLineageClosure::getAncestorType, BatchLineageClosure::getAncestorId,
                resolver, edges.bySource());
        List<TraceabilityDTO.LineageDepthGroup> descendants = includeDescendants
                ? groupByDepth(descendantRows,
                        BatchLineageClosure::getDescendantType, BatchLineageClosure::getDescendantId,
                        resolver, edges.byTarget())
                : null;

        int nodeCount = countNodes(ancestors) + countNodes(descendants);
        // ⚠️ 按**真实个体 id** 去重, 不是按节点上那个（公开侧已被遮过的）耳标号 ——
        //    末 4 位相同的两头牛会被数成一头, 而「来自几头牛」少报一头就是假话。
        int individualCount = countDistinctIndividuals(ancestorRows, resolver);
        return new PublicLineage(TraceabilityDTO.LineageSection.builder()
                .rootType(rootType)
                .rootId(rootId)
                .ancestors(ancestors)
                .descendants(descendants)
                .maxDepth(depthCap)
                .nodeCount(nodeCount)
                .sourceIndividualCount(individualCount)
                .build(), List.of());
    }

    /**
     * 公开视图：上游里只留原料批，压成<b>一组、不带 depth</b>。
     *
     * <p>⛔ 什么都不许让人反推出工序数或先后：
     * <ul>
     *   <li>不按 depth 分组（组数 / depth 值 = 工序数）、{@code maxDepth} 置 null；</li>
     *   <li>{@code nodeCount} 只数原料；</li>
     *   <li>节点上不贴边上的数量 / 时间（只有直接投进最后一道的原料才有那条边 ——
     *       「谁有数量」本身就泄漏了先后，数量也是配比）；</li>
     *   <li>按名称 + 批号排序，⛔ 不按链上的远近；</li>
     *   <li>{@code rootId}（生产批的自增主键）与 nodeId 同一条纪律，置 null。</li>
     * </ul>
     * 实体查不到的原料节点（软删）判不了是不是在制批 ⇒ 不给（公开侧宁缺）。
     */
    private PublicLineage publicRawMaterialView(String factoryId, String rootType,
                                                List<BatchLineageClosure> ancestorRows, NodeResolver resolver) {
        Set<String> materialIds = ancestorRows.stream()
                .filter(r -> LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(r.getAncestorType()))
                .map(BatchLineageClosure::getAncestorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> producedByProduction = producedByProduction(factoryId, materialIds);

        List<MaterialBatch> raws = materialIds.stream()
                .map(id -> resolver.materials().get(id))
                .filter(LineageGraphAssembler::isRawMaterialForPublic)
                .filter(mb -> !producedByProduction.contains(mb.getId()))
                .sorted(Comparator
                        .comparing((MaterialBatch mb) -> mb.getMaterialType() == null ? null
                                : mb.getMaterialType().getName(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MaterialBatch::getBatchNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<TraceabilityDTO.LineageNode> nodes = raws.stream()
                .map(mb -> resolver.toNode(LineageEdgeRecorder.NODE_MATERIAL_BATCH, mb.getId(), null))
                .toList();
        List<TraceabilityDTO.LineageDepthGroup> ancestors = nodes.isEmpty()
                ? List.of()
                : List.of(TraceabilityDTO.LineageDepthGroup.builder().depth(null).nodes(nodes).build());

        Set<String> individuals = new LinkedHashSet<>();
        for (MaterialBatch mb : raws) {
            String individualId = mb.getSourceIndividualId();
            if (individualId != null && resolver.individuals().containsKey(individualId)) {
                individuals.add(individualId);
            }
        }
        TraceabilityDTO.LineageSection section = TraceabilityDTO.LineageSection.builder()
                .rootType(rootType)
                .rootId(null)
                .ancestors(ancestors)
                .descendants(null)
                .maxDepth(null)
                .nodeCount(nodes.size())
                .sourceIndividualCount(individuals.size())
                .build();
        return new PublicLineage(section, raws);
    }

    /** 这些物料批里，链上有「生产批 → 它」边的那些（= 某道工序产出的，不是原料）。 */
    private Set<String> producedByProduction(String factoryId, Set<String> materialIds) {
        if (materialIds.isEmpty()) {
            return Set.of();
        }
        List<BatchLineageEdge> incoming = edgeRepository.findByFactoryIdAndTargetIdIn(factoryId, materialIds);
        return (incoming == null ? List.<BatchLineageEdge>of() : incoming).stream()
                .filter(e -> LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(e.getTargetType()))
                .filter(e -> LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getSourceType()))
                .map(BatchLineageEdge::getTargetId)
                .collect(Collectors.toSet());
    }

    /** 上游原料批次挂到的、去重后的源头个体数。没有就是 0（⛔ 不是 null）。 */
    private int countDistinctIndividuals(List<BatchLineageClosure> ancestorRows, NodeResolver resolver) {
        Set<String> ids = new LinkedHashSet<>();
        for (BatchLineageClosure row : ancestorRows) {
            if (!LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(row.getAncestorType())) {
                continue;
            }
            MaterialBatch mb = resolver.materials().get(row.getAncestorId());
            if (mb == null || mb.getSourceIndividualId() == null || mb.getSourceIndividualId().isBlank()) {
                continue;
            }
            // 只数**真的查得到**的个体：批次上挂着一个已被软删/不存在的 id 时，
            // 节点上也不会显示个体，两处口径必须一致。
            if (resolver.individuals().containsKey(mb.getSourceIndividualId())) {
                ids.add(mb.getSourceIndividualId());
            }
        }
        return ids.size();
    }

    /**
     * 召回影响面：这个批次往下游能波及到的生产批次 / 成品批次 / 出货单。
     */
    @Transactional(readOnly = true)
    public TraceabilityDTO.ImpactResponse assembleImpact(String factoryId, String rootType, String rootId,
                                                         int maxDepth) {
        TraceabilityDTO.LineageSection section =
                assemble(factoryId, rootType, rootId, maxDepth, true, false);
        // An empty graph only proves that no edges were returned. Verify the
        // root before reporting zero recorded impact. Existing graph evidence
        // remains queryable even if its historical root has been archived.
        if (section.getNodeCount() == 0) {
            requireExistingImpactRoot(factoryId, rootType, rootId);
        }
        List<TraceabilityDTO.LineageNode> all = section.getDescendants() == null
                ? List.of()
                : section.getDescendants().stream()
                        .flatMap(g -> g.getNodes().stream())
                        .collect(Collectors.toList());

        List<TraceabilityDTO.LineageNode> production = byType(all, LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
        List<TraceabilityDTO.LineageNode> finished = byType(all, LineageEdgeRecorder.NODE_FINISHED_BATCH);
        List<TraceabilityDTO.LineageNode> shipments = byType(all, LineageEdgeRecorder.NODE_SHIPMENT_RECORD);

        // S3-16: 影响面里有锅次的批次再按锅收窄 (额外一层, 不改上面的批次级结果)
        //     锅次这一层出错 ⇒ potScopes 为 null 并带原因, ⛔ 不让它把批次级召回影响面一起打挂
        List<com.cretas.aims.service.pot.PotTraceService.BatchPotScope> potScopes = null;
        String potScopesError = null;
        if (potTraceService != null) {
            java.util.Set<String> impacted = new java.util.LinkedHashSet<>();
            impacted.add(rootType + "#" + rootId);
            all.forEach(n -> impacted.add(n.getNodeType() + "#" + n.getNodeId()));
            try {
                potScopes = potTraceService.scopesFor(factoryId, impacted, null);
            } catch (RuntimeException e) {
                log.warn("[impact] 按锅收窄失败, 只返回批次级影响面: factory={} root={}:{}", factoryId, rootType, rootId, e);
                potScopesError = "按锅收窄暂时取不到, 请按批次级影响面召回";
            }
        }

        return TraceabilityDTO.ImpactResponse.builder()
                .potScopes(potScopes)
                .potScopesError(potScopesError)
                .rootType(rootType)
                .rootId(rootId)
                .productionBatches(production)
                .finishedBatches(finished)
                .shipments(shipments)
                .productionBatchCount(production.size())
                .finishedBatchCount(finished.size())
                .shipmentCount(shipments.size())
                .maxDepth(clampDepth(maxDepth))
                .queryTime(java.time.LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void requireExistingImpactRoot(String factoryId, String rootType, String rootId) {
        String ownerFactory = switch (rootType) {
            case LineageEdgeRecorder.NODE_MATERIAL_BATCH -> materialBatchRepository.findById(rootId)
                    .map(MaterialBatch::getFactoryId).orElse(null);
            case LineageEdgeRecorder.NODE_PRODUCTION_BATCH -> {
                final long id;
                try {
                    id = Long.parseLong(rootId);
                } catch (NumberFormatException error) {
                    throw new BusinessException(400, "生产批次 ID 必须为数字").withHintTarget("batchId");
                }
                yield productionBatchRepository.findById(id).map(ProductionBatch::getFactoryId).orElse(null);
            }
            case LineageEdgeRecorder.NODE_FINISHED_BATCH -> finishedGoodsBatchRepository.findById(rootId)
                    .map(FinishedGoodsBatch::getFactoryId).orElse(null);
            case LineageEdgeRecorder.NODE_SHIPMENT_RECORD -> salesDeliveryRecordRepository.findById(rootId)
                    .map(SalesDeliveryRecord::getFactoryId).orElse(null);
            default -> throw new BusinessException(400, "不支持的批次类型");
        };
        if (factoryId == null || !factoryId.equals(ownerFactory)) {
            throw new BusinessException(404, "当前工厂未找到该批次，无法判断召回影响面")
                    .withHint("请核对批次类型和 ID；零条血缘记录不能证明批次存在")
                    .withHintTarget("batchId");
        }
    }

    private static List<TraceabilityDTO.LineageNode> byType(List<TraceabilityDTO.LineageNode> nodes,
                                                            String nodeType) {
        return nodes.stream()
                .filter(n -> nodeType.equals(n.getNodeType()))
                .collect(Collectors.toList());
    }

    private static int countNodes(List<TraceabilityDTO.LineageDepthGroup> groups) {
        if (groups == null) {
            return 0;
        }
        return groups.stream().mapToInt(g -> g.getNodes() == null ? 0 : g.getNodes().size()).sum();
    }

    /**
     * 耳标号脱敏：只留末 4 位，前面用 {@code *} 补到原长。
     *
     * <p>⚠️ 为什么补到<b>原长</b>而不是固定 4 颗星：长度本身不敏感，而保留长度让店长/质检
     * 拿脱敏值与手上的耳标比对时能一眼排除「不是同一头」。
     *
     * <p>⛔ 耳标本身就 ≤4 位时<b>整串打星</b>，绝不原样返回 —— 那种情况下「末 4 位」等于全量，
     * 是这条规则最容易被绕过的边界（也是短 id 泄漏最常见的成因）。
     */
    static String maskTagNumber(String tagNumber) {
        if (tagNumber == null || tagNumber.isBlank()) {
            return null;
        }
        String trimmed = tagNumber.trim();
        if (trimmed.length() <= TAG_SUFFIX_VISIBLE) {
            return "*".repeat(trimmed.length());
        }
        return "*".repeat(trimmed.length() - TAG_SUFFIX_VISIBLE)
                + trimmed.substring(trimmed.length() - TAG_SUFFIX_VISIBLE);
    }

    /** 非正数取缺省（= 上限），超过上限夹到上限。 */
    public static int clampDepth(int requested) {
        if (requested <= 0) {
            return DEFAULT_MAX_DEPTH;
        }
        return Math.min(requested, MAX_DEPTH_CAP);
    }

    private static boolean isSameNode(String typeA, String idA, String typeB, String idB) {
        return Objects.equals(typeA, typeB) && Objects.equals(idA, idB);
    }

    private List<TraceabilityDTO.LineageDepthGroup> groupByDepth(
            List<BatchLineageClosure> rows,
            Function<BatchLineageClosure, String> typeOf,
            Function<BatchLineageClosure, String> idOf,
            NodeResolver resolver,
            Map<String, List<BatchLineageEdge>> edgeIndex) {

        Map<Integer, List<TraceabilityDTO.LineageNode>> byDepth = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        rows.stream()
                .sorted(Comparator.comparing(BatchLineageClosure::getDepth))
                .forEach(row -> {
                    String type = typeOf.apply(row);
                    String id = idOf.apply(row);
                    String key = type + "#" + id;
                    if (!seen.add(key)) {
                        return;   // 同一个节点可能经多条路径到达，只保留最近的那一跳
                    }
                    byDepth.computeIfAbsent(row.getDepth(), d -> new ArrayList<>())
                            .add(resolver.toNode(type, id, edgeIndex.get(key)));
                });

        return byDepth.entrySet().stream()
                .map(e -> TraceabilityDTO.LineageDepthGroup.builder()
                        .depth(e.getKey())
                        .nodes(e.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    /** 出边索引 / 入边索引，键都是 {@code type#id}。 */
    private record EdgeIndex(Map<String, List<BatchLineageEdge>> bySource,
                             Map<String, List<BatchLineageEdge>> byTarget) {}

    private EdgeIndex loadEdges(String factoryId, String rootType, String rootId,
                                List<BatchLineageClosure> ancestorRows,
                                List<BatchLineageClosure> descendantRows) {
        Set<String> ancestorIds = ancestorRows.stream()
                .map(BatchLineageClosure::getAncestorId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> descendantIds = descendantRows.stream()
                .map(BatchLineageClosure::getDescendantId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<BatchLineageEdge> sourceEdges = ancestorIds.isEmpty()
                ? List.of() : edgeRepository.findByFactoryIdAndSourceIdIn(factoryId, ancestorIds);
        Map<String, List<BatchLineageEdge>> bySource = index(
                (sourceEdges == null ? List.<BatchLineageEdge>of() : sourceEdges).stream()
                        .filter(e -> isSameNode(e.getTargetType(), e.getTargetId(), rootType, rootId))
                        .collect(Collectors.toList()),
                e -> e.getSourceType() + "#" + e.getSourceId());

        List<BatchLineageEdge> targetEdges = descendantIds.isEmpty()
                ? List.of() : edgeRepository.findByFactoryIdAndTargetIdIn(factoryId, descendantIds);
        Map<String, List<BatchLineageEdge>> byTarget = index(
                (targetEdges == null ? List.<BatchLineageEdge>of() : targetEdges).stream()
                        .filter(e -> isSameNode(e.getSourceType(), e.getSourceId(), rootType, rootId))
                        .collect(Collectors.toList()),
                e -> e.getTargetType() + "#" + e.getTargetId());
        return new EdgeIndex(bySource, byTarget);
    }

    private static Map<String, List<BatchLineageEdge>> index(List<BatchLineageEdge> edges,
                                                             Function<BatchLineageEdge, String> keyOf) {
        Map<String, List<BatchLineageEdge>> out = new HashMap<>();
        for (BatchLineageEdge e : edges == null ? List.<BatchLineageEdge>of() : edges) {
            out.computeIfAbsent(keyOf.apply(e), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 节点名称解析（批量，不 N+1）
    // ─────────────────────────────────────────────────────────────────────────

    private NodeResolver resolveNodes(String factoryId, List<BatchLineageClosure> ancestorRows,
                                      List<BatchLineageClosure> descendantRows, boolean desensitize) {
        Map<String, Set<String>> idsByType = new HashMap<>();
        ancestorRows.forEach(r -> idsByType
                .computeIfAbsent(r.getAncestorType(), t -> new LinkedHashSet<>())
                .add(r.getAncestorId()));
        descendantRows.forEach(r -> idsByType
                .computeIfAbsent(r.getDescendantType(), t -> new LinkedHashSet<>())
                .add(r.getDescendantId()));

        Map<String, MaterialBatch> materials = loadMaterials(idsByType.get(LineageEdgeRecorder.NODE_MATERIAL_BATCH));
        Map<String, Supplier> suppliers = desensitize ? Map.of() : loadSuppliers(materials.values());
        Map<String, ProductionBatch> productions =
                loadProductions(idsByType.get(LineageEdgeRecorder.NODE_PRODUCTION_BATCH));
        Map<String, FinishedGoodsBatch> finished =
                loadFinished(idsByType.get(LineageEdgeRecorder.NODE_FINISHED_BATCH));
        Map<String, SalesDeliveryRecord> shipments =
                loadShipments(idsByType.get(LineageEdgeRecorder.NODE_SHIPMENT_RECORD));
        Map<String, Customer> customers = desensitize ? Map.of() : loadCustomers(shipments.values());
        // ⚠️ 个体在**公开侧也要给**（那正是「扫码看到这块肉来自哪头牛」的全部意义），
        //    所以这里不按 desensitize 短路取数 —— 脱敏发生在 toNode 拼 DTO 那一步。
        Map<String, SourceIndividual> individuals = loadIndividuals(materials.values());

        return new NodeResolver(materials, suppliers, productions, finished, shipments, customers,
                individuals, desensitize);
    }

    private Map<String, MaterialBatch> loadMaterials(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return materialBatchRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MaterialBatch::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 按 {@code MaterialBatch.sourceIndividualId} 批量取源头个体。
     *
     * <p>⚠️ 绝大多数原料批次（调料、包材、辅料）<b>没有</b>个体，这里 id 集合为空就直接返回，
     * 不发查询 —— 这条路径在非畜禽场景下是零开销的。
     */
    private Map<String, SourceIndividual> loadIndividuals(Collection<MaterialBatch> batches) {
        Set<String> individualIds = batches.stream()
                .map(MaterialBatch::getSourceIndividualId).filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (individualIds.isEmpty()) {
            return Map.of();
        }
        return sourceIndividualRepository.findAllById(individualIds).stream()
                .collect(Collectors.toMap(SourceIndividual::getId, Function.identity(), (a, b) -> a));
    }

    private Map<String, Supplier> loadSuppliers(Collection<MaterialBatch> batches) {
        Set<String> supplierIds = batches.stream()
                .map(MaterialBatch::getSupplierId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity(), (a, b) -> a));
    }

    private Map<String, ProductionBatch> loadProductions(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        // ProductionBatch.id 是 Long，而血缘边把它当字符串存。解析不了的（脏数据/别的类型混进来）
        // 直接丢掉 → 该节点 resolved=false，⛔ 不静默换成 0。
        List<Long> longIds = new ArrayList<>();
        for (String id : ids) {
            try {
                longIds.add(Long.valueOf(id));
            } catch (NumberFormatException ignored) {
                log.warn("[lineage] PRODUCTION_BATCH 节点 id 不是数字, 无法解析实体: {}", id);
            }
        }
        if (longIds.isEmpty()) {
            return Map.of();
        }
        return productionBatchRepository.findAllById(longIds).stream()
                .collect(Collectors.toMap(b -> String.valueOf(b.getId()), Function.identity(), (a, b) -> a));
    }

    private Map<String, FinishedGoodsBatch> loadFinished(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return finishedGoodsBatchRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FinishedGoodsBatch::getId, Function.identity(), (a, b) -> a));
    }

    private Map<String, SalesDeliveryRecord> loadShipments(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return salesDeliveryRecordRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(SalesDeliveryRecord::getId, Function.identity(), (a, b) -> a));
    }

    private Map<String, Customer> loadCustomers(Collection<SalesDeliveryRecord> records) {
        Set<String> customerIds = records.stream()
                .map(SalesDeliveryRecord::getCustomerId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity(), (a, b) -> a));
    }

    /** 把 (type,id) + 相邻边翻译成一个 DTO 节点。 */
    private record NodeResolver(Map<String, MaterialBatch> materials,
                                Map<String, Supplier> suppliers,
                                Map<String, ProductionBatch> productions,
                                Map<String, FinishedGoodsBatch> finished,
                                Map<String, SalesDeliveryRecord> shipments,
                                Map<String, Customer> customers,
                                Map<String, SourceIndividual> individuals,
                                boolean desensitize) {

        TraceabilityDTO.LineageNode toNode(String type, String id, List<BatchLineageEdge> adjacent) {
            TraceabilityDTO.LineageNode.LineageNodeBuilder b = TraceabilityDTO.LineageNode.builder()
                    .nodeType(type)
                    // 🔴 nodeId 也要受 desensitize 管。它是**内部数据库主键**，
                    //    而 PRODUCTION_BATCH 的主键是顺序自增 BIGSERIAL —— 未认证的消费者
                    //    扫一次码就能拿到工厂内部批次表的连续整数, 可枚举、可推断生产节奏。
                    //    supplierName / 客户名当初都加了守卫, 唯独这一列漏了（漏的正是
                    //    「看起来只是个技术字段」的那一个）。公开侧一律置 null,
                    //    前端用 batchNumber 展示, 不依赖它。
                    .nodeId(desensitize ? null : id)
                    .resolved(false);
            applyEdgeFacts(b, adjacent);

            if (LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(type)) {
                MaterialBatch mb = materials.get(id);
                if (mb != null) {
                    b.resolved(true)
                            .batchNumber(mb.getBatchNumber())
                            .name(mb.getMaterialType() != null ? mb.getMaterialType().getName() : null);
                    if (!desensitize && mb.getSupplierId() != null) {
                        Supplier s = suppliers.get(mb.getSupplierId());
                        b.supplierName(s == null ? null : s.getName());
                    }
                    b.sourceIndividual(toIndividualBrief(mb.getSourceIndividualId()));
                }
            } else if (LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(type)) {
                ProductionBatch pb = productions.get(id);
                if (pb != null) {
                    b.resolved(true).batchNumber(pb.getBatchNumber()).name(pb.getProductName());
                }
            } else if (LineageEdgeRecorder.NODE_FINISHED_BATCH.equals(type)) {
                FinishedGoodsBatch fg = finished.get(id);
                if (fg != null) {
                    b.resolved(true).batchNumber(fg.getBatchNumber()).name(fg.getProductName());
                }
            } else if (LineageEdgeRecorder.NODE_SHIPMENT_RECORD.equals(type)) {
                SalesDeliveryRecord dr = shipments.get(id);
                if (dr != null) {
                    b.resolved(true).batchNumber(dr.getDeliveryNumber());
                    if (!desensitize) {
                        String customerName = dr.getCustomerName();
                        if (customerName == null && dr.getCustomerId() != null) {
                            Customer c = customers.get(dr.getCustomerId());
                            customerName = c == null ? null : c.getName();
                        }
                        b.name(customerName);
                    }
                }
            }
            return b.build();
        }

        /**
         * 把相邻边上的数量/单位/时间贴到节点上。
         *
         * <p>多条边时：单位一致才求和，⛔ 单位不一致<b>不换算也不挑一个</b> ——
         * 那正是「兜底把不知道翻译成一个数」。时间取最早那条（这个节点是什么时候进入这条链的）。
         */
        /**
         * 个体 → 节点上的摘要。<b>脱敏就发生在这一处</b>。
         *
         * <p>⚠️ 公开侧要给的东西是有意义的：品种、来源场、DNA 状态，
         * 那正是消费者扫码想看的「这块肉来自哪头牛」。
         * ⛔ 不给的是内部编码 / 基因库 ID / DNA 样本号 / 父母系谱 / 完整耳标号 ——
         * 后四样在 {@link TraceabilityDTO.SourceIndividualBrief} 上<b>压根没有字段</b>，
         * 所以不存在「忘了置 null」这种失误面。
         *
         * @return 该批次没挂个体时返回 {@code null}（⛔ 不是一个空壳对象 ——
         *         空壳会让「没登记个体」在前端长成「个体信息全缺」）
         */
        TraceabilityDTO.SourceIndividualBrief toIndividualBrief(String individualId) {
            if (individualId == null || individualId.isBlank()) {
                return null;
            }
            SourceIndividual si = individuals.get(individualId);
            if (si == null) {
                // 个体被软删/查不到。与节点的 resolved=false 同一条纪律：不编。
                return null;
            }
            return TraceabilityDTO.SourceIndividualBrief.builder()
                    // 主键只给内部侧 —— 溯源页靠它跳个体详情; 公开侧与 nodeId 同一条纪律。
                    .id(desensitize ? null : si.getId())
                    .individualCode(desensitize ? null : si.getIndividualCode())
                    .tagNumber(desensitize ? maskTagNumber(si.getTagNumber()) : si.getTagNumber())
                    .tagNumberMasked(desensitize)
                    .species(si.getSpecies())
                    .breed(si.getBreed())
                    .originFarm(si.getOriginFarm())
                    .dnaVerificationStatus(si.getDnaVerificationStatus() == null
                            ? null : si.getDnaVerificationStatus().name())
                    .build();
        }

        private void applyEdgeFacts(TraceabilityDTO.LineageNode.LineageNodeBuilder b,
                                    List<BatchLineageEdge> adjacent) {
            if (adjacent == null || adjacent.isEmpty()) {
                return;
            }
            adjacent.stream()
                    .map(BatchLineageEdge::getEventTime)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .ifPresent(b::eventTime);
            if (adjacent.stream().anyMatch(e -> e.getQuantityUsed() == null
                    || e.getUnit() == null || e.getUnit().isBlank())) {
                return;   // 缺少数量或单位 ⇒ 不能把剩余边的部分事实冒充完整汇总
            }
            Set<String> units = adjacent.stream()
                    .map(BatchLineageEdge::getUnit)
                    .collect(Collectors.toSet());
            if (units.size() > 1) {
                return;   // 单位不一致 ⇒ 数量不可加，诚实留 null
            }
            BigDecimal total = null;
            for (BatchLineageEdge e : adjacent) {
                if (e.getQuantityUsed() == null) {
                    continue;
                }
                total = total == null ? e.getQuantityUsed() : total.add(e.getQuantityUsed());
            }
            if (total != null) {
                b.quantity(total.doubleValue());
                b.unit(units.isEmpty() ? null : units.iterator().next());
            }
        }
    }
}
