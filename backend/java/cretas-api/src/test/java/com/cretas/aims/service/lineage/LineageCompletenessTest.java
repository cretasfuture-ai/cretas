package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
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
import com.cretas.aims.repository.lineage.LineageEdgeOutboxRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 溯源结果的完整性标记（食品 DAG 缺口 02）：「完整」还是「缺哪一段」。
 *
 * <p>同一条链（原料 → 生产批 100 → 在制批 → 生产批 200 → 成品）做四种扰动，
 * 每种扰动对应一种缺口；未扰动的那条必须 COMPLETE —— 它是其余几条的阴性对照
 * （否则「报了缺口」可能只是判据对什么都报）。
 *
 * <p>边和闭包都从同一份 {@link #edges} 列表算出来（闭包按 BFS 现算），
 * 删一条边时闭包跟着断 —— 与 prod 上触发器维护闭包的行为一致，⛔ 不手写两份让它们漂。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LineageGraphAssembler — 完整性: 断链 / 截断 / 查不到")
class LineageCompletenessTest {

    private static final String FACTORY = "F006";

    @Mock private BatchLineageClosureRepository closureRepository;
    @Mock private BatchLineageEdgeRepository edgeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SourceIndividualRepository sourceIndividualRepository;
    @Mock private LineageEdgeOutboxRepository outboxRepository;

    private final List<BatchLineageEdge> edges = new ArrayList<>();
    private final List<MaterialBatch> materials = new ArrayList<>();

    @BeforeEach
    void wireChain() {
        edges.add(edge("MATERIAL_BATCH", "mb-raw", "PRODUCTION_BATCH", "100"));
        edges.add(edge("PRODUCTION_BATCH", "100", "MATERIAL_BATCH", "wip-1"));
        edges.add(edge("MATERIAL_BATCH", "wip-1", "PRODUCTION_BATCH", "200"));
        edges.add(edge("PRODUCTION_BATCH", "200", "FINISHED_BATCH", "fg-1"));

        materials.add(material("mb-raw", "RAW-001", null));
        materials.add(material("wip-1", "WIP-001", LineageEdgeRecorder.NODE_PRODUCTION_BATCH));

        when(closureRepository.findFullGraph(eq(FACTORY), anyString(), anyString(), any()))
                .thenAnswer(inv -> closuresFor(inv.getArgument(2), inv.getArgument(1), inv.getArgument(3)));
        when(edgeRepository.findByFactoryIdAndTargetIdIn(eq(FACTORY), any()))
                .thenAnswer(inv -> edgesWhere(inv.getArgument(1), true));
        when(edgeRepository.findByFactoryIdAndSourceIdIn(eq(FACTORY), any()))
                .thenAnswer(inv -> edgesWhere(inv.getArgument(1), false));
        when(materialBatchRepository.findAllById(any()))
                .thenAnswer(inv -> materials.stream().filter(m -> contains(inv.getArgument(0), m.getId())).toList());
        when(productionBatchRepository.findAllById(any()))
                .thenReturn(List.of(production(100L, "PB-100"), production(200L, "PB-200")));
        when(finishedGoodsBatchRepository.findAllById(any())).thenReturn(List.of(finished("fg-1", "FG-001")));
    }

    private LineageGraphAssembler assembler() {
        return new LineageGraphAssembler(closureRepository, edgeRepository, materialBatchRepository,
                supplierRepository, productionBatchRepository, finishedGoodsBatchRepository,
                salesDeliveryRecordRepository, customerRepository, sourceIndividualRepository);
    }

    private TraceabilityDTO.LineageCompleteness trace(int maxDepth) {
        return assembler().assemble(FACTORY, "FINISHED_BATCH", "fg-1", maxDepth, false, false).getCompleteness();
    }

    @Test
    @DisplayName("整条链追到外购原料 ⇒ COMPLETE, 没有缺口 (阴性对照)")
    void unbrokenChainIsComplete() {
        TraceabilityDTO.LineageCompleteness c = trace(50);

        assertThat(c.getStatus()).isEqualTo(TraceabilityDTO.LineageCompleteness.COMPLETE);
        assertThat(c.getGaps()).isEmpty();
        assertThat(c.getTruncated()).isFalse();
        assertThat(c.getSummary()).contains("1 个原料批");
    }

    @Test
    @DisplayName("在制批缺「生产批 → 它」那条边 ⇒ INCOMPLETE, 指出断在 WIP-001")
    void missingProducerEdgeOfWipIsReportedAsBrokenChain() {
        edges.removeIf(e -> "wip-1".equals(e.getTargetId()));

        TraceabilityDTO.LineageCompleteness c = trace(50);

        assertThat(c.getStatus()).isEqualTo(TraceabilityDTO.LineageCompleteness.INCOMPLETE);
        assertThat(c.getGaps()).singleElement().satisfies(g -> {
            assertThat(g.getKind()).isEqualTo(TraceabilityDTO.LineageGap.MISSING_UPSTREAM);
            assertThat(g.getNodeId()).isEqualTo("wip-1");
            assertThat(g.getBatchNumber()).isEqualTo("WIP-001");
            assertThat(g.getDepth()).isEqualTo(2);
        });
        assertThat(c.getSummary()).contains("缺上游").contains("WIP-001");
    }

    @Test
    @DisplayName("成品连生产批都没挂上 ⇒ 缺口就在起点 (depth 0)")
    void finishedBatchWithoutProducerIsBrokenAtRoot() {
        edges.removeIf(e -> "fg-1".equals(e.getTargetId()));

        TraceabilityDTO.LineageCompleteness c = trace(50);

        assertThat(c.getStatus()).isEqualTo(TraceabilityDTO.LineageCompleteness.INCOMPLETE);
        assertThat(c.getGaps()).singleElement().satisfies(g -> {
            assertThat(g.getNodeId()).isEqualTo("fg-1");
            assertThat(g.getDepth()).isZero();
        });
    }

    @Test
    @DisplayName("自环不算来源: 生产批 100 只剩同批领用自环 ⇒ 仍是缺上游")
    void selfLoopDoesNotCountAsUpstream() {
        edges.removeIf(e -> "100".equals(e.getTargetId()));
        BatchLineageEdge loop = edge("PRODUCTION_BATCH", "100", "PRODUCTION_BATCH", "100");
        loop.setEdgeType("WIP_CONSUME");
        edges.add(loop);

        TraceabilityDTO.LineageCompleteness c = trace(50);

        assertThat(c.getGaps()).extracting(TraceabilityDTO.LineageGap::getNodeId).containsExactly("100");
    }

    @Test
    @DisplayName("跳数上限卡在生产批 100 上, 它还有入边 ⇒ 截断, 不能说「到头了」")
    void depthCapWithFurtherEdgesIsTruncation() {
        TraceabilityDTO.LineageCompleteness c = trace(3);

        assertThat(c.getStatus()).isEqualTo(TraceabilityDTO.LineageCompleteness.INCOMPLETE);
        assertThat(c.getTruncated()).isTrue();
        assertThat(c.getTruncatedAtDepth()).isEqualTo(3);
        assertThat(c.getGaps()).singleElement().satisfies(g -> {
            assertThat(g.getKind()).isEqualTo(TraceabilityDTO.LineageGap.DEPTH_TRUNCATED);
            assertThat(g.getNodeId()).isEqualTo("100");
        });
    }

    @Test
    @DisplayName("链上节点实体查不到 ⇒ NODE_UNRESOLVED")
    void unresolvedNodeIsAGap() {
        materials.removeIf(m -> "wip-1".equals(m.getId()));

        TraceabilityDTO.LineageCompleteness c = trace(50);

        assertThat(c.getStatus()).isEqualTo(TraceabilityDTO.LineageCompleteness.INCOMPLETE);
        assertThat(c.getGaps()).extracting(TraceabilityDTO.LineageGap::getKind)
                .containsExactly(TraceabilityDTO.LineageGap.NODE_UNRESOLVED);
    }

    @Test
    @DisplayName("链上还有补写中的边 ⇒ 结论里带上条数; 台账没注入 ⇒ null 不是 0")
    void pendingWritesAreReported() {
        assertThat(trace(50).getPendingEdgeWrites()).isNull();

        when(outboxRepository.countOpenTargeting(eq(FACTORY), any())).thenReturn(2L);
        LineageGraphAssembler withOutbox = assembler();
        ReflectionTestUtils.setField(withOutbox, "outboxRepository", outboxRepository);
        TraceabilityDTO.LineageCompleteness c =
                withOutbox.assemble(FACTORY, "FINISHED_BATCH", "fg-1", 50, false, false).getCompleteness();

        assertThat(c.getPendingEdgeWrites()).isEqualTo(2L);
        assertThat(c.getSummary()).contains("2 条关系正在补写");
    }

    @Test
    @DisplayName("公开视图不带完整性 (消费者页不出这一块)")
    void publicViewHasNoCompleteness() {
        assertThat(assembler().assemblePublic(FACTORY, "FINISHED_BATCH", "fg-1").section().getCompleteness())
                .isNull();
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    /** 从 {@link #edges} 现算闭包（起点往上 BFS），depth ≤ cap。 */
    private List<BatchLineageClosure> closuresFor(String rootType, String rootId, Object cap) {
        int depthCap = cap instanceof Number n ? n.intValue() : 50;
        List<BatchLineageClosure> out = new ArrayList<>();
        List<String[]> frontier = new ArrayList<>();
        frontier.add(new String[]{rootType, rootId});
        for (int d = 1; d <= depthCap && !frontier.isEmpty(); d++) {
            List<String[]> next = new ArrayList<>();
            for (String[] node : frontier) {
                for (BatchLineageEdge e : edges) {
                    if (e.getTargetType().equals(node[0]) && e.getTargetId().equals(node[1])
                            && !(e.getSourceType().equals(e.getTargetType()) && e.getSourceId().equals(e.getTargetId()))) {
                        BatchLineageClosure c = new BatchLineageClosure();
                        c.setFactoryId(FACTORY);
                        c.setDepth(d);
                        c.setAncestorType(e.getSourceType());
                        c.setAncestorId(e.getSourceId());
                        c.setDescendantType(rootType);
                        c.setDescendantId(rootId);
                        out.add(c);
                        next.add(new String[]{e.getSourceType(), e.getSourceId()});
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    private List<BatchLineageEdge> edgesWhere(Collection<String> ids, boolean byTarget) {
        Set<String> set = Set.copyOf(ids);
        return edges.stream()
                .filter(e -> set.contains(byTarget ? e.getTargetId() : e.getSourceId()))
                .collect(Collectors.toList());
    }

    private static boolean contains(Object ids, String id) {
        return ids instanceof Iterable<?> it && java.util.stream.StreamSupport.stream(it.spliterator(), false)
                .anyMatch(id::equals);
    }

    private static BatchLineageEdge edge(String sourceType, String sourceId, String targetType, String targetId) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY);
        e.setEdgeType("RAW_TO_PRODUCTION");
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(BigDecimal.ONE);
        e.setUnit("kg");
        return e;
    }

    private static MaterialBatch material(String id, String batchNumber, String sourceDocType) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setBatchNumber(batchNumber);
        mb.setSourceDocType(sourceDocType);
        RawMaterialType type = new RawMaterialType();
        type.setName("牛腩");
        mb.setMaterialType(type);
        return mb;
    }

    private static ProductionBatch production(Long id, String batchNumber) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setBatchNumber(batchNumber);
        return b;
    }

    private static FinishedGoodsBatch finished(String id, String batchNumber) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setBatchNumber(batchNumber);
        return fg;
    }
}
