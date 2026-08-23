package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService.CostAllocationBasis;
import com.cretas.aims.service.bom.BomWorkflowRevisionService.TerminalOutput;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.WorkflowActualIoSemantics;
import com.cretas.aims.service.workflow.WorkflowByproductNodes;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 坎 3 的契约: <b>「谁是副产 / 谁承担联合成本」由用户在画布上标的东西决定，
 * 不由终端节点 id 的字典序决定。</b>
 *
 * <h3>它在守什么（2026-08-18 实测）</h3>
 *
 * <p>{@code resolveTerminalOutputs} 曾经在 ACTUAL_IO 下写着
 * {@code roleValue = terminalIndex == 0 ? "MAIN" : "BY_PRODUCT"}、
 * {@code ratio = terminalIndex == 0 ? 100 : 0}，而 {@code terminals} 按
 * {@code Node::getId} 排序。两个后果：</p>
 * <ol>
 *   <li><b>谁拿 100% 由节点 id 的字典序裁定</b>。同一批产出在报工侧走的是实测质量占比
 *       （{@code ProcessSheetServiceImpl} 的 {@code basis=MASS}，prod 实测 甲 80% / 乙 20%），
 *       同一个问题两个互相矛盾的答案。</li>
 *   <li><b>第 2 个正经成品被无声标成副产品</b>（客户 2026-07-31 现场：一对多 workflow
 *       两个都是正经成品，生成 BOM 后被要求填「单位可变现净值」——一个他从没被问过的字段）。</li>
 * </ol>
 *
 * <h3>判据来源：⛔ 不新造，用既有那条</h3>
 *
 * <p>「这个产出 Cell 是不是副产」在仓里已经有唯一判据：物料节点上的
 * {@code data.isByproduct}（2026-08-07 阶段 2 换的载体），JVM 侧收在
 * {@link WorkflowByproductNodes}。⛔ 刻意<b>不</b>用 {@code ProcessPort.outputRole} ——
 * web-admin {@code workflow/types.ts} 明写那个字段已弃用，画布编辑器目录下它
 * <b>只有类型声明与测试，生产 view 0 处 v-model</b>，而 {@code normalizeDraft}
 * 每次保存都把它从 PROCESS 端口上删掉。用它等于造一个前端没有入口的字段。</p>
 *
 * <h3>桩数据为什么长这样（「真实上游真的会给出这个形状吗」）</h3>
 *
 * <ul>
 *   <li>{@code reportingSelectionMode=ACTUAL_IO}：prod 实测 89/89 个 PROCESS 节点全是它。</li>
 *   <li>副产标记<b>刻意标在字典序靠前的 {@code t-alpha} 上</b>：旧规则下 {@code t-alpha}
 *       会拿 MAIN/100%、真成品 {@code t-beta} 反而被判成 BY_PRODUCT/0% ——
 *       两条规则给出的答案<b>完全相反</b>，所以这条断言对「有没有人把编号规则加回来」是可见的。</li>
 *   <li>副产标记用<b>字符串 {@code "true"}</b>：nodes_json 是 jsonb，历史快照里这个标记
 *       既出现过布尔也出现过字符串（{@link WorkflowByproductNodes} 的注释记着这件事）。
 *       用字符串同时证明这里走的是那条共享判据，而不是就地写了个 {@code Boolean.TRUE.equals}。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiOutputAllocationBasisContractTest {

    private static final String FACTORY = "F006";
    /** 字典序靠前的终端 —— 旧规则下它无条件拿 MAIN/100%。 */
    private static final String SKU_ALPHA = "FG-ALPHA";
    private static final String SKU_BETA = "FG-BETA";

    @Mock private BomRecipeRepository recipeRepository;
    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private ProductProcessWorkflowRevisionRepository revisionRepository;
    @Mock private ProductProcessWorkflowActivationRepository activationRepository;
    @Mock private ProductProcessWorkflowCatalogValidator catalogValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductProcessWorkflowValidator validator;
    private WorkflowRevisionSnapshotService snapshotService;
    private BomWorkflowRevisionService service;

    @BeforeEach
    void setUp() {
        validator = new ProductProcessWorkflowValidator();
        snapshotService = new WorkflowRevisionSnapshotService(
                revisionRepository, recipeRepository, validator, objectMapper);
        service = new BomWorkflowRevisionService(recipeRepository, workflowRepository, revisionRepository,
                activationRepository, validator, catalogValidator, snapshotService, objectMapper);
    }

    // ------------------------------------------------------------------
    // 1. 真实入口 = BomWorkflowRevisionService#bindExactRevision
    //    它是 BomRecipeServiceImpl 三处调用的那个方法, 也是唯一把角色/比例写进
    //    bom_recipes 行的地方(applyBinding)。⛔ 不是直接调 resolveTerminalOutputs。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实入口: 用户标的副产才是副产 —— 字典序靠前的那个不再自动拿 100%")
    void theByproductMarkDecidesTheRoleNotTheNodeIdOrder() throws Exception {
        // 旧规则: t-alpha(字典序靠前) → MAIN/100%, t-beta → BY_PRODUCT/0%
        // 新规则: t-alpha 被标成副产 → BY_PRODUCT/0%, t-beta(唯一计价产出) → MAIN/100%
        // 两条规则的答案完全相反, 所以这条断言看得见「编号规则有没有回来」。
        ProductProcessWorkflowRevision revision = revisionOf(oneRealOutputPlusMarkedByproduct());
        when(revisionRepository.findByIdAndFactoryId(71L, FACTORY)).thenReturn(Optional.of(revision));

        BomRecipe betaRecipe = bindThrough(SKU_BETA);
        assertEquals(BomRecipe.OutputRole.MAIN, betaRecipe.getOutputRole(),
                "唯一的正经成品应当承担全部联合成本, 实际却不是它 —— 字典序编号规则回来了");
        assertEquals(0, new BigDecimal("100").compareTo(betaRecipe.getCostAllocationRatio()));

        BomRecipe alphaRecipe = bindThrough(SKU_ALPHA);
        assertEquals(BomRecipe.OutputRole.BY_PRODUCT, alphaRecipe.getOutputRole(),
                "用户在画布上标了副产, 落库的角色却不是 BY_PRODUCT");
        assertEquals(0, BigDecimal.ZERO.compareTo(alphaRecipe.getCostAllocationRatio()),
                "副产不参与比例分摊, 它按 NRV 抵扣");
    }

    @Test
    @DisplayName("真实入口: 两个都是正经成品时 CO_PRODUCT 真的落到 BOM 行(全库原来 0 行)")
    void twoRealOutputsBecomeCoProductsInsteadOfAFabricatedHundredZero() throws Exception {
        ProductProcessWorkflowRevision revision = revisionOf(twoRealOutputs());
        when(revisionRepository.findByIdAndFactoryId(71L, FACTORY)).thenReturn(Optional.of(revision));

        BomRecipe betaRecipe = bindThrough(SKU_BETA);
        assertEquals(BomRecipe.OutputRole.CO_PRODUCT, betaRecipe.getOutputRole(),
                "第二个正经成品仍被无声标成副产品 —— 那正是客户 2026-07-31 被要求填 NRV 的成因");
        assertNull(betaRecipe.getCostAllocationRatio(),
                "⛔ 设计期算不出就不许写一个数。写 0 会被读成「这个成品不承担成本」");

        BomRecipe alphaRecipe = bindThrough(SKU_ALPHA);
        assertEquals(BomRecipe.OutputRole.MAIN, alphaRecipe.getOutputRole(),
                "Family 仍要有唯一结构锚点(sharedRecipeId 挂在它上面)");
        assertNull(alphaRecipe.getCostAllocationRatio(),
                "锚点是结构角色, ⛔ 不许顺带把 100% 也给它 —— 那就是字典序发钱");
    }

    // ------------------------------------------------------------------
    // 2. 口径本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两个正经成品: 没有任何一个拿到 100%, 口径标成 REPORTED_MASS")
    void twoRealOutputsMarkTheBasisAsUndecidableAtDesignTime() throws Exception {
        List<TerminalOutput> outputs = terminalOutputsThroughPinnedRecipe(twoRealOutputs());

        assertEquals(2, outputs.size());
        assertTrue(outputs.stream().allMatch(
                        output -> output.costAllocationBasis() == CostAllocationBasis.REPORTED_MASS),
                "口径没标出来, 下游就分不清「没配」和「设计期不可判」");
        assertTrue(outputs.stream().allMatch(output -> output.costAllocationRatio() == null),
                "有产出拿到了一个凭空来的比例: " + outputs.stream()
                        .map(TerminalOutput::costAllocationRatio).toList());
        assertFalse(outputs.stream().anyMatch(
                        output -> output.outputRole() == BomRecipe.OutputRole.BY_PRODUCT),
                "没人标过副产, ⛔ 系统不许替他标一个");
        assertEquals(1, outputs.stream()
                .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN).count());
    }

    @Test
    @DisplayName("阳性对照: 一真一副时口径仍是 DECLARED / 100 / 0 —— 常见形态没被改坏")
    void oneRealOutputPlusOneByproductKeepsTheDeclaredHundredZeroShape() throws Exception {
        List<TerminalOutput> outputs = terminalOutputsThroughPinnedRecipe(oneRealOutputPlusMarkedByproduct());

        assertTrue(outputs.stream().allMatch(
                        output -> output.costAllocationBasis() == CostAllocationBasis.DECLARED),
                "一真一副是用户标清楚了的, 不该被标成「设计期不可判」");
        BigDecimal total = outputs.stream().map(TerminalOutput::costAllocationRatio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("100").compareTo(total));
    }

    @Test
    @DisplayName("阳性对照: 单产出仍然拿 100%(那是恒等式, 不是裁定)")
    void singleTerminalStillTakesTheWholeCostBecauseThatIsAnIdentity() throws Exception {
        List<TerminalOutput> outputs = terminalOutputsThroughPinnedRecipe(singleRealOutput());

        assertEquals(1, outputs.size());
        assertEquals(BomRecipe.OutputRole.MAIN, outputs.getFirst().outputRole());
        assertEquals(0, new BigDecimal("100").compareTo(outputs.getFirst().costAllocationRatio()),
                "把单产出也判成不可判会让所有普通 BOM 一起结不了单");
        assertEquals(CostAllocationBasis.DECLARED, outputs.getFirst().costAllocationBasis());
    }

    @Test
    @DisplayName("判据只有一条: 副产 truthiness 走 WorkflowByproductNodes, 布尔与字符串都认")
    void theByproductCriterionHasExactlyOneDefinition() {
        // 形态 D: 同一个东西有两份就一定会漂。nodes_json 是 jsonb, 这个标记
        // 历史上既存过布尔也存过字符串 —— 只认 Boolean.TRUE 会让存量副产静默退化。
        assertTrue(WorkflowByproductNodes.isByproductFlag(Boolean.TRUE));
        assertTrue(WorkflowByproductNodes.isByproductFlag("true"));
        assertFalse(WorkflowByproductNodes.isByproductFlag(Boolean.FALSE));
        assertFalse(WorkflowByproductNodes.isByproductFlag(null));
    }

    // ------------------------------------------------------------------
    // 3. 坎 4 对齐: 新形状(CO_PRODUCT + ratio null)在两个判据下必须是同一个答案,
    //    而且必须落在「要比例」那一支, ⛔ 不能被误认成占位角色而静默放行。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("坎 4 重新对齐: CO_PRODUCT + ratio=null 在行级/配方级都判成 ALLOCATION_RATIO")
    void newlyReachableCoProductShapeAgreesAcrossBothCostPolicyForms() {
        // 阳性对照: 三态里另外两态没被这次改动带坏
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.BY_PRODUCT_NRV,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.BY_PRODUCT, new BigDecimal("12.5")));
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ACTUAL_IO_PLACEHOLDER,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.BY_PRODUCT, null),
                "prod 已落库的 15 行历史占位仍然要走这一支 —— 它不是死代码");

        // 本次新增的可达形状
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.CO_PRODUCT, null),
                "CO_PRODUCT 被误判成占位角色的话, 「算不出」会被当成「不用算」而静默放行");
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.MAIN, null));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private BomRecipe bindThrough(String productTypeId) {
        BomRecipe recipe = bareRecipe(productTypeId);
        when(recipeRepository.saveAndFlush(recipe)).thenReturn(recipe);
        service.bindExactRevision(FACTORY, recipe, 71L);
        return recipe;
    }

    /** 走 resolvePinnedTerminalOutputs(真实 service 方法, 只桩掉 repository)。 */
    private List<TerminalOutput> terminalOutputsThroughPinnedRecipe(
            ProductProcessWorkflowDTO definition) throws Exception {
        BomRecipe recipe = bareRecipe(SKU_ALPHA);
        recipe.setWorkflowRevisionId(71L);
        recipe.setWorkflowId(41L);
        recipe.setWorkflowDefinitionVersion(2);
        recipe.setWorkflowRevisionHash("snapshot-hash");
        recipe.setWorkflowSchemaVersion(1);
        recipe.setWorkflowNodesSnapshotJson(objectMapper.writeValueAsString(definition.getNodes()));
        recipe.setWorkflowEdgesSnapshotJson(objectMapper.writeValueAsString(definition.getEdges()));
        return service.resolvePinnedTerminalOutputs(FACTORY, recipe);
    }

    private ProductProcessWorkflowRevision revisionOf(ProductProcessWorkflowDTO definition)
            throws Exception {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(71L);
        revision.setFactoryId(FACTORY);
        revision.setProductTypeId(SKU_ALPHA);
        revision.setWorkflowId(41L);
        revision.setDefinitionVersion(2);
        revision.setRevisionNumber(3);
        revision.setSchemaVersion(1);
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setNodesJson(objectMapper.writeValueAsString(definition.getNodes()));
        revision.setEdgesJson(objectMapper.writeValueAsString(definition.getEdges()));
        revision.setViewportJson(objectMapper.writeValueAsString(definition.getViewport()));
        revision.setRevisionHash(snapshotService.hash(revision));
        revision.setProcessCount(1);
        revision.setStructurallyComplete(true);
        return revision;
    }

    private BomRecipe bareRecipe(String productTypeId) {
        return BomRecipe.builder()
                .id("BOM-" + productTypeId).factoryId(FACTORY).recipeCode("BOM-" + productTypeId)
                .productTypeId(productTypeId).productName(productTypeId)
                .outputQuantityPerUnit(BigDecimal.ONE)
                .status(BomRecipe.Status.DRAFT).build();
    }

    /** 一进两出: t-alpha 被标成副产(字典序靠前), t-beta 是唯一的正经成品。 */
    private ProductProcessWorkflowDTO oneRealOutputPlusMarkedByproduct() {
        return twoTerminalActualIo(true);
    }

    /** 一进两出: 两个都是正经成品(客户 2026-07-31 现场的形状)。 */
    private ProductProcessWorkflowDTO twoRealOutputs() {
        return twoTerminalActualIo(false);
    }

    private ProductProcessWorkflowDTO twoTerminalActualIo(boolean alphaIsByproduct) {
        List<Map<String, Object>> ports = List.of(
                port("in", "INPUT", "raw-a", 0),
                port("out-alpha", "OUTPUT", "t-alpha", 0),
                port("out-beta", "OUTPUT", "t-beta", 1));
        ProductProcessWorkflowDTO.Node alpha = material("t-alpha", SKU_ALPHA, 200);
        if (alphaIsByproduct) {
            // 字符串 "true": nodes_json 是 jsonb, 历史快照里出现过字符串形态。
            alpha.getData().put("isByproduct", "true");
        }
        return definition(new ArrayList<>(List.of(
                rawMaterial("raw-a", "RM-A"),
                actualIoProcess("split", ports),
                alpha,
                material("t-beta", SKU_BETA, 220))), List.of(
                edge("e1", "raw-a", "output", "split", "in"),
                edge("e2", "split", "out-alpha", "t-alpha", "input"),
                edge("e3", "split", "out-beta", "t-beta", "input")));
    }

    private ProductProcessWorkflowDTO singleRealOutput() {
        return definition(new ArrayList<>(List.of(
                rawMaterial("raw-a", "RM-A"),
                actualIoProcess("split", List.of(
                        port("in", "INPUT", "raw-a", 0),
                        port("out-alpha", "OUTPUT", "t-alpha", 0))),
                material("t-alpha", SKU_ALPHA, 200))), List.of(
                edge("e1", "raw-a", "output", "split", "in"),
                edge("e2", "split", "out-alpha", "t-alpha", "input")));
    }

    private ProductProcessWorkflowDTO definition(List<ProductProcessWorkflowDTO.Node> nodes,
                                                 List<ProductProcessWorkflowDTO.Edge> edges) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(41L);
        dto.setFactoryId(FACTORY);
        dto.setProductTypeId(SKU_ALPHA);
        dto.setSchemaVersion(1);
        dto.setVersion(2);
        dto.setNodes(nodes);
        dto.setEdges(new ArrayList<>(edges));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflowDTO.Node rawMaterial(String id, String skuId) {
        return node(id, "RAW_MATERIAL", skuId, 0);
    }

    private ProductProcessWorkflowDTO.Node material(String id, String skuId, double x) {
        return node(id, "FINISHED_GOOD", skuId, x);
    }

    private ProductProcessWorkflowDTO.Node node(String id, String kind, String skuId, double x) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", id);
        data.put("skuId", skuId);
        data.put("skuCode", skuId);
        return new ProductProcessWorkflowDTO.Node(id, kind,
                new ProductProcessWorkflowDTO.Position(x, 0D), data);
    }

    private ProductProcessWorkflowDTO.Node actualIoProcess(String id, List<Map<String, Object>> ports) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-SPLIT");
        data.put("processName", id);
        data.put("inputUnit", "kg");
        data.put("outputUnit", "kg");
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        // prod 实测 89/89 个 PROCESS 节点都带这个标记。
        data.put(WorkflowActualIoSemantics.MODE_FIELD, WorkflowActualIoSemantics.ACTUAL_IO);
        return new ProductProcessWorkflowDTO.Node(id, "PROCESS",
                new ProductProcessWorkflowDTO.Position(100D, 0D), data);
    }

    private Map<String, Object> port(String id, String direction, String materialNodeId, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", "INPUT".equals(direction) ? "RAW_MATERIAL" : "FINISHED_GOOD");
        port.put("unit", "kg");
        port.put("ordinal", ordinal);
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(String id, String source, String sourceHandle,
                                                String target, String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }
}
