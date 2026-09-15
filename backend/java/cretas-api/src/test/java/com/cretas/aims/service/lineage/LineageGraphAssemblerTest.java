package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
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
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LineageGraphAssembler} —— 闭包 + 边 → 按跳数分组的来源链。
 *
 * <h2>三条被守的性质</h2>
 * <ol>
 *   <li>3 层闭包组装出 3 个 depth 组，且每组里是对的节点（不是全塞进一组）。</li>
 *   <li>公开接口<b>不泄漏</b>供应商/客户（阴性对照：同一份数据在内部接口上必须<b>看得到</b>，
 *       否则「没泄漏」可能只是因为它本来就查不出来 —— 那是恒真式）。</li>
 *   <li>节点实体查不到时 {@code resolved=false} 且名称留 null，⛔ 不编名字。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LineageGraphAssembler — 多跳组装 / 脱敏 / 诚实 null")
class LineageGraphAssemblerTest {

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

    private LineageGraphAssembler newAssembler() {
        return new LineageGraphAssembler(closureRepository, edgeRepository, materialBatchRepository,
                supplierRepository, productionBatchRepository, finishedGoodsBatchRepository,
                salesDeliveryRecordRepository, customerRepository, sourceIndividualRepository);
    }

    /**
     * 三层上游: mb-far (depth 3) → pb-mid (depth 2) → mb-near (depth 1) → 根 pb-8967。
     * 根的下游: fg-1 (depth 1) → dr-1 (depth 2)。
     */
    private void wireThreeLayerGraph() {
        when(closureRepository.findFullGraph(eq(FACTORY), eq("8967"), eq("PRODUCTION_BATCH"), any()))
                .thenReturn(List.of(
                        closure(0, "PRODUCTION_BATCH", "8967", "PRODUCTION_BATCH", "8967"),
                        closure(1, "MATERIAL_BATCH", "mb-near", "PRODUCTION_BATCH", "8967"),
                        closure(2, "PRODUCTION_BATCH", "8100", "PRODUCTION_BATCH", "8967"),
                        closure(3, "MATERIAL_BATCH", "mb-far", "PRODUCTION_BATCH", "8967"),
                        closure(1, "PRODUCTION_BATCH", "8967", "FINISHED_BATCH", "fg-1"),
                        closure(2, "PRODUCTION_BATCH", "8967", "SHIPMENT_RECORD", "dr-1")));

        when(materialBatchRepository.findAllById(any()))
                .thenReturn(List.of(materialBatch("mb-near", "MB-NEAR-001", "牛腩", "sup-1"),
                        materialBatch("mb-far", "MB-FAR-001", "整牛", "sup-1")));
        when(supplierRepository.findAllById(any()))
                .thenReturn(List.of(supplier("sup-1", "胜龙牛业")));
        when(productionBatchRepository.findAllById(any()))
                .thenReturn(List.of(productionBatch(8100L, "PB-8100", "分割半成品")));
        when(finishedGoodsBatchRepository.findAllById(any()))
                .thenReturn(List.of(finishedBatch("fg-1", "FG-0001", "酱牛肉")));
        when(salesDeliveryRecordRepository.findAllById(any()))
                .thenReturn(List.of(deliveryRecord("dr-1", "DN-0001", "六膳门中央厨房")));

        when(edgeRepository.findByFactoryIdAndSourceIdIn(anyString(), any()))
                .thenReturn(List.of(edge("MATERIAL_BATCH", "mb-near", "PRODUCTION_BATCH", "8967",
                        new BigDecimal("12.5"), "kg")));
        when(edgeRepository.findByFactoryIdAndTargetIdIn(anyString(), any()))
                .thenReturn(List.of(edge("PRODUCTION_BATCH", "8967", "FINISHED_BATCH", "fg-1",
                        new BigDecimal("100"), "盒")));
    }

    // ── ① 三层 → 三组 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("3 层闭包 ⇒ ancestors 分 3 组, 每组是对的节点")
    void threeLayerGraphSplitsIntoThreeDepthGroups() {
        wireThreeLayerGraph();

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        assertThat(section.getAncestors()).hasSize(3);
        assertThat(section.getAncestors()).extracting(TraceabilityDTO.LineageDepthGroup::getDepth)
                .containsExactly(1, 2, 3);
        // 每一组里是对的那个节点 —— 光有「3 组」不够, 全塞进一组再拆成 3 组也能凑出 3。
        assertThat(nodeIdsAtDepth(section, 1)).containsExactly("mb-near");
        assertThat(nodeIdsAtDepth(section, 2)).containsExactly("8100");
        assertThat(nodeIdsAtDepth(section, 3)).containsExactly("mb-far");
        // 自引用 depth 0 不能混进来
        assertThat(section.getAncestors()).noneMatch(g -> g.getDepth() == 0);
    }

    @Test
    @DisplayName("edge 上的数量/单位贴到节点上")
    void edgeFactsAreAttachedToNodes() {
        wireThreeLayerGraph();

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        TraceabilityDTO.LineageNode near = nodeAt(section, 1, "mb-near");
        assertThat(near.getQuantity()).isEqualTo(12.5);
        assertThat(near.getUnit()).isEqualTo("kg");
    }

    // ── ② 脱敏 + 阴性对照 ────────────────────────────────────────────────────

    @Test
    @DisplayName("内部接口能看到供应商与客户名（阴性对照的正面那一半）")
    void internalViewExposesSupplierAndCustomer() {
        wireThreeLayerGraph();

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        // 🔴 没有这一条, 下面那条「公开接口看不到」就可能只是因为数据根本没接上 —— 恒真式。
        assertThat(nodeAt(section, 1, "mb-near").getSupplierName()).isEqualTo("胜龙牛业");
        assertThat(section.getDescendants()).isNotNull();
        TraceabilityDTO.LineageNode shipment = descendantNode(section, "dr-1");
        assertThat(shipment.getName()).isEqualTo("六膳门中央厨房");
    }

    @Test
    @DisplayName("🔴 公开接口不许泄漏 nodeId —— 序列化后的 JSON 里一个内部主键都不许有")
    void publicViewDoesNotLeakInternalNodeIds() throws Exception {
        wireThreeLayerGraph();

        TraceabilityDTO.LineageSection publicSection =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, false, true);
        TraceabilityDTO.LineageSection internalSection =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        // ⚠️「必发生」的那一半: 内部视图必须**看得到** nodeId。
        //    没有它, 下面那条「公开视图没有 nodeId」在「组装器根本没填过 nodeId」时也成立 —— 恒真式。
        assertThat(internalSection.getAncestors())
                .flatExtracting(TraceabilityDTO.LineageDepthGroup::getNodes)
                .extracting(TraceabilityDTO.LineageNode::getNodeId)
                .contains("8100", "mb-near", "mb-far");

        assertThat(publicSection.getAncestors())
                .flatExtracting(TraceabilityDTO.LineageDepthGroup::getNodes)
                .allSatisfy(n -> assertThat(n.getNodeId())
                        .as("nodeId 是内部数据库主键(PRODUCTION_BATCH 还是自增整数), 公开接口必须置空")
                        .isNull());

        // 判据落到**真正发出去的那个字节流**上: 序列化之后整段 JSON 里不许出现那几个内部 id。
        // 只断言 DTO 字段为 null 不够 —— 万一哪天有人把 id 塞进别的字段, 那条断言照样绿。
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(publicSection);
        assertThat(json).doesNotContain("\"8100\"").doesNotContain("mb-near").doesNotContain("mb-far");
    }

    @Test
    @DisplayName("公开接口: 不给 descendants, 供应商恒为 null")
    void publicViewHidesSupplierAndDescendants() {
        wireThreeLayerGraph();

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, false, true);

        assertThat(section.getAncestors()).isNotEmpty();
        assertThat(section.getAncestors())
                .flatExtracting(TraceabilityDTO.LineageDepthGroup::getNodes)
                .allSatisfy(n -> assertThat(n.getSupplierName()).isNull());
        // descendants 是 null（本接口不提供）而不是空列表（本接口提供但没有下游）—— 两个读数不同。
        assertThat(section.getDescendants()).isNull();
    }

    // ── ③ 查不到实体 ⇒ 诚实 null ─────────────────────────────────────────────

    @Test
    @DisplayName("批次实体查不到 ⇒ resolved=false 且名称留 null（⛔ 不编「未知批次」）")
    void unresolvableNodeStaysHonest() {
        when(closureRepository.findFullGraph(eq(FACTORY), eq("8967"), eq("PRODUCTION_BATCH"), any()))
                .thenReturn(List.of(closure(1, "MATERIAL_BATCH", "mb-gone", "PRODUCTION_BATCH", "8967")));
        when(materialBatchRepository.findAllById(any())).thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdIn(anyString(), any())).thenReturn(List.of());

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        TraceabilityDTO.LineageNode gone = nodeAt(section, 1, "mb-gone");
        assertThat(gone.getResolved()).isFalse();
        assertThat(gone.getBatchNumber()).isNull();
        assertThat(gone.getName()).isNull();
    }

    @Test
    @DisplayName("没有任何血缘记录 ⇒ ancestors 空列表 + nodeCount 0（而不是抛错或 null）")
    void emptyGraphYieldsEmptyAncestors() {
        when(closureRepository.findFullGraph(eq(FACTORY), eq("8967"), eq("PRODUCTION_BATCH"), any()))
                .thenReturn(List.of());

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        assertThat(section.getAncestors()).isEmpty();
        assertThat(section.getNodeCount()).isZero();
    }

    // ── ④ 影响面分组 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("召回影响面: 下游按类型分三组 + 计数")
    void impactSplitsDescendantsByType() {
        wireThreeLayerGraph();

        TraceabilityDTO.ImpactResponse impact =
                newAssembler().assembleImpact(FACTORY, "PRODUCTION_BATCH", "8967", 5);

        assertThat(impact.getFinishedBatchCount()).isEqualTo(1);
        assertThat(impact.getShipmentCount()).isEqualTo(1);
        assertThat(impact.getProductionBatchCount()).isZero();
        assertThat(impact.getShipments()).extracting(TraceabilityDTO.LineageNode::getName)
                .containsExactly("六膳门中央厨房");
    }

    // -- (5) 源头个体（「这块肉来自哪头牛」） --------------------------------

    /** mb-near 挂个体 SI-1，mb-far 挂个体 SI-2；两头牛耳标末 4 位<b>故意相同</b>。 */
    private void wireIndividuals() {
        when(materialBatchRepository.findAllById(any()))
                .thenReturn(List.of(
                        materialBatchWithIndividual("mb-near", "MB-NEAR-001", "牛腩", "sup-1", "SI-1"),
                        materialBatchWithIndividual("mb-far", "MB-FAR-001", "整牛", "sup-1", "SI-2")));
        when(sourceIndividualRepository.findAllById(any()))
                .thenReturn(List.of(
                        individual("SI-1", "COW-0001", "1109876547788", "牛", "西门塔尔", "胜龙一号牧场",
                                DnaVerificationStatus.MATCHED),
                        // 末 4 位与 SI-1 相同 —— 拿脱敏后的耳标去重会把两头牛数成一头。
                        individual("SI-2", "COW-0002", "2201234567788", "牛", "安格斯", "胜龙二号牧场",
                                DnaVerificationStatus.SAMPLED)));
    }

    @Test
    @DisplayName("原料批次挂了个体 ⇒ 节点带出品种/来源场/DNA 状态；没挂的节点为 null")
    void materialNodesCarryTheirSourceIndividual() {
        wireThreeLayerGraph();
        wireIndividuals();

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        TraceabilityDTO.SourceIndividualBrief near = nodeAt(section, 1, "mb-near").getSourceIndividual();
        assertThat(near).isNotNull();
        assertThat(near.getSpecies()).isEqualTo("牛");
        assertThat(near.getBreed()).isEqualTo("西门塔尔");
        assertThat(near.getOriginFarm()).isEqualTo("胜龙一号牧场");
        assertThat(near.getDnaVerificationStatus()).isEqualTo("MATCHED");
        // 内部接口给完整耳标 + 内部编码
        assertThat(near.getTagNumber()).isEqualTo("1109876547788");
        assertThat(near.getTagNumberMasked()).isFalse();
        assertThat(near.getIndividualCode()).isEqualTo("COW-0001");

        // 生产批次节点没有个体 —— 不是空壳对象
        assertThat(nodeAt(section, 2, "8100").getSourceIndividual()).isNull();
    }

    @Test
    @DisplayName("原料批次没挂个体 ⇒ sourceIndividual 为 null，且不发个体查询")
    void materialWithoutIndividualQueriesNothing() {
        wireThreeLayerGraph();   // 默认的 materialBatch 没有 sourceIndividualId

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        assertThat(nodeAt(section, 1, "mb-near").getSourceIndividual()).isNull();
        assertThat(section.getSourceIndividualCount()).isZero();
        // 非畜禽场景（调料/包材）这条路径必须是零开销的
        verify(sourceIndividualRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("公开接口脱敏：耳标只留末 4 位，内部编码/基因库/样本号/系谱一律不出现")
    void publicViewMasksTheIndividual() throws Exception {
        wireThreeLayerGraph();
        wireIndividuals();

        TraceabilityDTO.LineageSection publicSection =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, false, true);
        TraceabilityDTO.LineageSection internalSection =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        // 「必发生」的那一半：内部视图**看得到**完整耳标与内部编码。
        // 没有它，「公开视图里找不到它们」在「个体压根没挂上」时同样成立 —— 恒真式。
        assertThat(internalSection.getAncestors())
                .flatExtracting(TraceabilityDTO.LineageDepthGroup::getNodes)
                .anySatisfy(n -> {
                    assertThat(n.getSourceIndividual()).isNotNull();
                    assertThat(n.getSourceIndividual().getTagNumber()).isEqualTo("1109876547788");
                    assertThat(n.getSourceIndividual().getIndividualCode()).isEqualTo("COW-0001");
                    // 内部侧给主键 —— 溯源页靠它跳个体详情。这一条是下面「公开侧 id 为 null」的阳性对照。
                    assertThat(n.getSourceIndividual().getId()).isEqualTo("SI-1");
                });

        TraceabilityDTO.SourceIndividualBrief masked =
                publicNode(publicSection, "MB-NEAR-001").getSourceIndividual();
        assertThat(masked).isNotNull();
        // 允许出的：品种 / 来源场 / DNA 状态 —— 那正是扫码的人想看的
        assertThat(masked.getSpecies()).isEqualTo("牛");
        assertThat(masked.getBreed()).isEqualTo("西门塔尔");
        assertThat(masked.getOriginFarm()).isEqualTo("胜龙一号牧场");
        assertThat(masked.getDnaVerificationStatus()).isEqualTo("MATCHED");
        // 耳标只留末 4 位，长度保持不变
        assertThat(masked.getTagNumber()).isEqualTo("*********7788");
        assertThat(masked.getTagNumberMasked()).isTrue();
        assertThat(masked.getIndividualCode()).isNull();
        // 主键与 nodeId 同一条纪律: 公开侧恒为 null (前端以它判断「这一行能不能点」)。
        assertThat(masked.getId()).isNull();

        // 判据落到**真正发出去的字节流**上：只断言 DTO 字段为 null 不够 ——
        // 哪天有人把敏感值塞进别的字段，那条断言照样绿。
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(publicSection);
        assertThat(json)
                .as("公开响应里不许出现完整耳标 / 内部编码 / 基因库 ID / DNA 样本号 / 系谱")
                .doesNotContain("1109876547788")
                .doesNotContain("2201234567788")
                .doesNotContain("COW-0001")
                // 个体主键 (SI-1 / SI-2) 不许以任何字段名出现在公开字节流里
                .doesNotContain("\"SI-1\"")
                .doesNotContain("\"SI-2\"")
                .doesNotContain("GENEBANK")
                .doesNotContain("DNA-SAMPLE")
                .doesNotContain("externalGeneBankId")
                .doesNotContain("dnaSampleCode")
                .doesNotContain("sireIndividualId")
                .doesNotContain("damIndividualId");
    }

    @Test
    @DisplayName("「本批来自 N 头牛」按真实个体 id 去重 —— 末 4 位相同的两头不许被数成一头")
    void individualCountDedupesOnRealIdsNotMaskedTags() {
        wireThreeLayerGraph();
        wireIndividuals();

        TraceabilityDTO.LineageSection publicSection =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, false, true);

        // 两头牛的脱敏耳标都是 *********7788；按脱敏值去重会得到 1，那是一句假话。
        assertThat(publicSection.getSourceIndividualCount()).isEqualTo(2);
        assertThat(publicSection.getAncestors())
                .flatExtracting(TraceabilityDTO.LineageDepthGroup::getNodes)
                .filteredOn(n -> n.getSourceIndividual() != null)
                .extracting(n -> n.getSourceIndividual().getTagNumber())
                .as("前提确认：两头牛脱敏后长得一模一样 —— 这正是不能在前端去重的原因")
                .containsOnly("*********7788");
    }

    @Test
    @DisplayName("耳标 <= 4 位 ⇒ 整串打星（不许因为「末 4 位就是全部」而原样返回）")
    void shortTagIsFullyMasked() {
        assertThat(LineageGraphAssembler.maskTagNumber("7788")).isEqualTo("****");
        assertThat(LineageGraphAssembler.maskTagNumber("88")).isEqualTo("**");
        assertThat(LineageGraphAssembler.maskTagNumber("123456")).isEqualTo("**3456");
        assertThat(LineageGraphAssembler.maskTagNumber(null)).isNull();
        assertThat(LineageGraphAssembler.maskTagNumber("  ")).isNull();
    }

    @Test
    @DisplayName("个体 id 挂着但个体查不到（软删）⇒ 节点不带个体，计数也不算它（两处同口径）")
    void danglingIndividualIsNotFabricated() {
        wireThreeLayerGraph();
        when(materialBatchRepository.findAllById(any()))
                .thenReturn(List.of(materialBatchWithIndividual(
                        "mb-near", "MB-NEAR-001", "牛腩", "sup-1", "SI-GONE")));
        when(sourceIndividualRepository.findAllById(any())).thenReturn(List.of());

        TraceabilityDTO.LineageSection section =
                newAssembler().assemble(FACTORY, "PRODUCTION_BATCH", "8967", 5, true, false);

        assertThat(nodeAt(section, 1, "mb-near").getSourceIndividual()).isNull();
        assertThat(section.getSourceIndividualCount())
                .as("节点上不显示的个体, 计数里也不许算 —— 否则「来自 1 头牛」下面一头都看不到")
                .isZero();
    }

    private static TraceabilityDTO.LineageNode publicNode(TraceabilityDTO.LineageSection section,
                                                          String batchNumber) {
        return section.getAncestors().stream()
                .flatMap(g -> g.getNodes().stream())
                .filter(n -> batchNumber.equals(n.getBatchNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("公开视图里找不到批次 " + batchNumber));
    }

    private static MaterialBatch materialBatchWithIndividual(String id, String batchNumber,
                                                             String typeName, String supplierId,
                                                             String individualId) {
        MaterialBatch mb = materialBatch(id, batchNumber, typeName, supplierId);
        mb.setSourceIndividualId(individualId);
        return mb;
    }

    private static SourceIndividual individual(String id, String code, String tag, String species,
                                               String breed, String farm, DnaVerificationStatus dna) {
        SourceIndividual si = new SourceIndividual();
        si.setId(id);
        si.setFactoryId(FACTORY);
        si.setIndividualCode(code);
        si.setTagNumber(tag);
        si.setSpecies(species);
        si.setBreed(breed);
        si.setOriginFarm(farm);
        si.setDnaVerificationStatus(dna);
        // 这几样**绝不能**出现在公开响应里
        si.setExternalGeneBankId("GENEBANK-XYZ-001");
        si.setDnaSampleCode("DNA-SAMPLE-777");
        si.setSireIndividualId("SIRE-1");
        si.setDamIndividualId("DAM-1");
        return si;
    }

    @Test
    @DisplayName("maxDepth 超上限被夹到 10, 非正数取默认 5")
    void depthIsClamped() {
        assertThat(LineageGraphAssembler.clampDepth(99)).isEqualTo(10);
        assertThat(LineageGraphAssembler.clampDepth(0)).isEqualTo(5);
        assertThat(LineageGraphAssembler.clampDepth(3)).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static List<String> nodeIdsAtDepth(TraceabilityDTO.LineageSection section, int depth) {
        return section.getAncestors().stream()
                .filter(g -> g.getDepth() == depth)
                .flatMap(g -> g.getNodes().stream())
                .map(TraceabilityDTO.LineageNode::getNodeId)
                .toList();
    }

    private static TraceabilityDTO.LineageNode nodeAt(TraceabilityDTO.LineageSection section,
                                                      int depth, String nodeId) {
        return section.getAncestors().stream()
                .filter(g -> g.getDepth() == depth)
                .flatMap(g -> g.getNodes().stream())
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("depth " + depth + " 上找不到节点 " + nodeId));
    }

    private static TraceabilityDTO.LineageNode descendantNode(TraceabilityDTO.LineageSection section,
                                                              String nodeId) {
        return section.getDescendants().stream()
                .flatMap(g -> g.getNodes().stream())
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("下游里找不到节点 " + nodeId));
    }

    private static BatchLineageClosure closure(int depth, String ancestorType, String ancestorId,
                                               String descendantType, String descendantId) {
        BatchLineageClosure c = new BatchLineageClosure();
        c.setFactoryId(FACTORY);
        c.setDepth(depth);
        c.setAncestorType(ancestorType);
        c.setAncestorId(ancestorId);
        c.setDescendantType(descendantType);
        c.setDescendantId(descendantId);
        return c;
    }

    private static BatchLineageEdge edge(String sourceType, String sourceId,
                                         String targetType, String targetId,
                                         BigDecimal qty, String unit) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY);
        e.setEdgeType("RAW_TO_PRODUCTION");
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(qty);
        e.setUnit(unit);
        return e;
    }

    private static MaterialBatch materialBatch(String id, String batchNumber, String typeName,
                                               String supplierId) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setBatchNumber(batchNumber);
        mb.setSupplierId(supplierId);
        RawMaterialType type = new RawMaterialType();
        type.setName(typeName);
        mb.setMaterialType(type);
        return mb;
    }

    private static Supplier supplier(String id, String name) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private static ProductionBatch productionBatch(Long id, String batchNumber, String productName) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setBatchNumber(batchNumber);
        b.setProductName(productName);
        return b;
    }

    private static FinishedGoodsBatch finishedBatch(String id, String batchNumber, String productName) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setBatchNumber(batchNumber);
        fg.setProductName(productName);
        return fg;
    }

    private static SalesDeliveryRecord deliveryRecord(String id, String deliveryNumber, String customerName) {
        SalesDeliveryRecord r = new SalesDeliveryRecord();
        r.setId(id);
        r.setDeliveryNumber(deliveryNumber);
        r.setCustomerName(customerName);
        return r;
    }
}
