package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** 与 {@code LineageQueryTool} 同一个上限，别让一次溯源把整张图拉回来。 */
    public static final int MAX_DEPTH_CAP = 10;
    public static final int DEFAULT_MAX_DEPTH = 5;

    private final BatchLineageClosureRepository closureRepository;
    private final BatchLineageEdgeRepository edgeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final SupplierRepository supplierRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    private final CustomerRepository customerRepository;

    /**
     * 组装一个批次的血缘视图。
     *
     * @param includeDescendants 是否给下游（公开接口不给）
     * @param desensitize        是否抹掉供应商等内部信息（公开接口给 true）
     * @return 永远非 null；没有任何血缘记录时 {@code ancestors} 是空列表、{@code nodeCount} 为 0
     */
    public TraceabilityDTO.LineageSection assemble(String factoryId, String rootType, String rootId,
                                                   int maxDepth, boolean includeDescendants,
                                                   boolean desensitize) {
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
        EdgeIndex edges = loadEdges(factoryId, ancestorRows, descendantRows);

        List<TraceabilityDTO.LineageDepthGroup> ancestors = groupByDepth(
                ancestorRows, BatchLineageClosure::getAncestorType, BatchLineageClosure::getAncestorId,
                resolver, edges.bySource());
        List<TraceabilityDTO.LineageDepthGroup> descendants = includeDescendants
                ? groupByDepth(descendantRows,
                        BatchLineageClosure::getDescendantType, BatchLineageClosure::getDescendantId,
                        resolver, edges.byTarget())
                : null;

        int nodeCount = countNodes(ancestors) + countNodes(descendants);
        return TraceabilityDTO.LineageSection.builder()
                .rootType(rootType)
                .rootId(rootId)
                .ancestors(ancestors)
                .descendants(descendants)
                .maxDepth(depthCap)
                .nodeCount(nodeCount)
                .build();
    }

    /**
     * 召回影响面：这个批次往下游能波及到的生产批次 / 成品批次 / 出货单。
     */
    public TraceabilityDTO.ImpactResponse assembleImpact(String factoryId, String rootType, String rootId,
                                                         int maxDepth) {
        TraceabilityDTO.LineageSection section =
                assemble(factoryId, rootType, rootId, maxDepth, true, false);
        List<TraceabilityDTO.LineageNode> all = section.getDescendants() == null
                ? List.of()
                : section.getDescendants().stream()
                        .flatMap(g -> g.getNodes().stream())
                        .collect(Collectors.toList());

        List<TraceabilityDTO.LineageNode> production = byType(all, LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
        List<TraceabilityDTO.LineageNode> finished = byType(all, LineageEdgeRecorder.NODE_FINISHED_BATCH);
        List<TraceabilityDTO.LineageNode> shipments = byType(all, LineageEdgeRecorder.NODE_SHIPMENT_RECORD);

        return TraceabilityDTO.ImpactResponse.builder()
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

    private EdgeIndex loadEdges(String factoryId, List<BatchLineageClosure> ancestorRows,
                                List<BatchLineageClosure> descendantRows) {
        Set<String> ancestorIds = ancestorRows.stream()
                .map(BatchLineageClosure::getAncestorId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> descendantIds = descendantRows.stream()
                .map(BatchLineageClosure::getDescendantId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, List<BatchLineageEdge>> bySource = ancestorIds.isEmpty()
                ? Map.of()
                : index(edgeRepository.findByFactoryIdAndSourceIdIn(factoryId, ancestorIds),
                        e -> e.getSourceType() + "#" + e.getSourceId());
        Map<String, List<BatchLineageEdge>> byTarget = descendantIds.isEmpty()
                ? Map.of()
                : index(edgeRepository.findByFactoryIdAndTargetIdIn(factoryId, descendantIds),
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

        return new NodeResolver(materials, suppliers, productions, finished, shipments, customers, desensitize);
    }

    private Map<String, MaterialBatch> loadMaterials(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return materialBatchRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MaterialBatch::getId, Function.identity(), (a, b) -> a));
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
        private void applyEdgeFacts(TraceabilityDTO.LineageNode.LineageNodeBuilder b,
                                    List<BatchLineageEdge> adjacent) {
            if (adjacent == null || adjacent.isEmpty()) {
                return;
            }
            Set<String> units = adjacent.stream()
                    .map(BatchLineageEdge::getUnit)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            adjacent.stream()
                    .map(BatchLineageEdge::getEventTime)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .ifPresent(b::eventTime);
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
