package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.WorkflowBomExportRowDTO;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工艺 / BOM 导出（会议 {@code [14:18]}/{@code [14:48]}/{@code [28:07]} 要了三次的那张 Excel）。
 *
 * <p>这张表会被客户拿去<b>线下备料和存档</b>，所以最要紧的两条不是「导得出来」，而是：</p>
 * <ol>
 *   <li><b>工序顺序是真的</b> —— 顺序错的工艺表比没有更糟；</li>
 *   <li><b>算不出的地方说算不出</b> —— 留白会被读成「不用填」，0 会被读成「不用料」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowBomExportServiceTest {

    private static final String FACTORY = "LSM";
    private static final String PRODUCT = "pt-burger";

    @Mock private ProductProcessWorkflowRevisionRepository revisionRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private ProductTypeRepository productTypeRepository;

    private WorkflowBomExportService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowBomExportService(
                revisionRepository, bomRecipeRepository, bomRecipeItemRepository, productTypeRepository);

        ProductType pt = new ProductType();
        pt.setId(PRODUCT);
        pt.setName("美式汉堡饼");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.of(pt));
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT)).thenReturn(List.of());
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.empty());
    }

    // ── 工序顺序 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("工序顺序按边排, 不按节点在数组里的先后")
    void ordersProcessesByEdgesNotArrayOrder() {
        // 数组里故意倒着放: [绞肉, 拆箱]; 边说 拆箱 → 绞肉
        // ⚠️ 若实现直接用数组序, 本条会红 —— 这正是要守的
        List<Map<String, Object>> processes = List.of(
                process("n2", "绞肉"), process("n1", "拆箱"));
        List<Map<String, Object>> edges = List.of(edge("n1", "n2"));

        List<Map<String, Object>> ordered = service.orderProcesses(processes, edges);

        assertThat(ordered).hasSize(2);
        assertThat(name(ordered.get(0))).isEqualTo("拆箱");
        assertThat(name(ordered.get(1))).isEqualTo("绞肉");
    }

    @Test
    @DisplayName("图有环时退回原序, 且一个工序都不能丢")
    void cycleFallsBackToArrayOrderWithoutDroppingAnyProcess() {
        List<Map<String, Object>> processes = List.of(
                process("a", "甲"), process("b", "乙"), process("c", "丙"));
        // a → b → c → a: 拓扑排不出来
        List<Map<String, Object>> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));

        List<Map<String, Object>> ordered = service.orderProcesses(processes, edges);

        // ⛔ 最要紧的是「不丢」: 静默丢掉排不进去的工序, 客户拿到的表会少几道
        assertThat(ordered).as("有环时丢掉了工序").hasSize(3);
        assertThat(ordered.stream().map(WorkflowBomExportServiceTest::name))
                .containsExactly("甲", "乙", "丙");
    }

    @Test
    @DisplayName("物料节点之间的边不参与工序排序")
    void edgesToNonProcessNodesAreIgnored() {
        List<Map<String, Object>> processes = List.of(process("p1", "唯一工序"));
        // 边指向一个不在 processes 里的物料节点 —— 不能因此报错或丢工序
        List<Map<String, Object>> edges = List.of(edge("p1", "material-node"), edge("raw", "p1"));

        assertThat(service.orderProcesses(processes, edges)).hasSize(1);
    }

    // ── 算不出就说算不出 ───────────────────────────────────────────────────

    @Test
    @DisplayName("有配比但没填标准出成率 ⇒ 备注说明, 出成率列留空而不是 0")
    void shareWithoutYieldRateIsCalledOutInTheNote() {
        publishedRevision(nodes(
                "[{\"id\":\"in:a\",\"direction\":\"INPUT\",\"inputShareRatio\":30,"
                        + "\"materialName\":\"2082上脑\",\"materialKind\":\"RAW_MATERIAL\",\"unit\":\"kg\"}]"));

        List<WorkflowBomExportRowDTO> rows = service.buildRows(FACTORY, List.of(PRODUCT));

        assertThat(rows).hasSize(1);
        WorkflowBomExportRowDTO row = rows.get(0);
        assertThat(row.getInputSharePercent()).isEqualByComparingTo("30");
        // 🔴 出成率没填 ⇒ 留空。⛔ 不能是 0 —— 0% 出成率会被读成「投多少都出不来」
        assertThat(row.getStandardYieldPercent()).as("出成率未填时被填成了一个数").isNull();
        assertThat(row.getNote()).contains("未填标准出成率");
        // 阳性对照: 端口自身的信息确实带出来了 (否则上面几条可能只是因为整行是空的)
        assertThat(row.getMaterialName()).isEqualTo("2082上脑");
        assertThat(row.getUnit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("既没标准用量也没配比 ⇒ 说清是「报工当场决定」, 不留空让人以为漏配了")
    void noQuantityAndNoShareSaysItIsDecidedAtReportingTime() {
        publishedRevision(nodes(
                "[{\"id\":\"in:x\",\"direction\":\"INPUT\","
                        + "\"materialName\":\"冰水\",\"materialKind\":\"AUXILIARY\",\"unit\":\"kg\"}]"));

        List<WorkflowBomExportRowDTO> rows = service.buildRows(FACTORY, List.of(PRODUCT));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStandardQuantity()).as("没有标准用量时填了一个数").isNull();
        assertThat(rows.get(0).getNote()).contains("报工当场决定");
    }

    @Test
    @DisplayName("产出行不挂「算不出」的备注 —— 那些提示只对投入有意义")
    void outputRowsDoNotCarryInputNotes() {
        publishedRevision(nodes(
                "[{\"id\":\"out:1\",\"direction\":\"OUTPUT\","
                        + "\"materialName\":\"汉堡饼半成品\",\"materialKind\":\"SEMI_FINISHED\",\"unit\":\"kg\"}]"));

        List<WorkflowBomExportRowDTO> rows = service.buildRows(FACTORY, List.of(PRODUCT));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDirection()).isEqualTo("产出");
        assertThat(rows.get(0).getNote()).isNull();
    }

    // ── 只导已发布 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("只有草稿的成品不导工序, 但要给一行说清楚 —— ⛔ 不静默跳过")
    void draftOnlyProductGetsAnExplicitRowInsteadOfSilence() {
        ProductProcessWorkflowRevision draft = revision(
                ProductProcessWorkflowRevision.Status.DRAFT, nodes("[]"));
        when(revisionRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(draft));

        List<WorkflowBomExportRowDTO> rows = service.buildRows(FACTORY, List.of(PRODUCT));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProductName()).isEqualTo("美式汉堡饼");
        assertThat(rows.get(0).getProcessName()).isNull();
        assertThat(rows.get(0).getNote()).contains("没有已发布的工艺");
    }

    @Test
    @DisplayName("同时有草稿和已发布 ⇒ 导已发布那一版")
    void publishedWinsOverNewerDraft() {
        ProductProcessWorkflowRevision published = revision(
                ProductProcessWorkflowRevision.Status.PUBLISHED,
                nodes("[{\"id\":\"in:p\",\"direction\":\"INPUT\",\"materialName\":\"已发布的料\"}]"));
        ProductProcessWorkflowRevision newerDraft = revision(
                ProductProcessWorkflowRevision.Status.DRAFT,
                nodes("[{\"id\":\"in:d\",\"direction\":\"INPUT\",\"materialName\":\"草稿的料\"}]"));
        // 仓库按 createdAt 倒序返回 ⇒ 草稿在前
        when(revisionRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(newerDraft, published));

        List<WorkflowBomExportRowDTO> rows = service.buildRows(FACTORY, List.of(PRODUCT));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMaterialName())
                .as("导出了草稿 —— 两个人各自导出会拿到不同的版本").isEqualTo("已发布的料");
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private void publishedRevision(String nodesJson) {
        when(revisionRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(revision(ProductProcessWorkflowRevision.Status.PUBLISHED, nodesJson)));
    }

    private static ProductProcessWorkflowRevision revision(
            ProductProcessWorkflowRevision.Status status, String nodesJson) {
        ProductProcessWorkflowRevision r = new ProductProcessWorkflowRevision();
        r.setFactoryId(FACTORY);
        r.setProductTypeId(PRODUCT);
        r.setStatus(status);
        r.setRevisionNumber(1);
        r.setCreatedAt(LocalDateTime.of(2026, 9, 2, 7, 51));
        r.setNodesJson(nodesJson);
        r.setEdgesJson("[]");
        return r;
    }

    /** 一个 PROCESS 节点包住给定的 ports JSON。 */
    private static String nodes(String portsJson) {
        return "[{\"id\":\"n1\",\"kind\":\"PROCESS\",\"data\":{\"processName\":\"绞肉\",\"ports\":"
                + portsJson + "}}]";
    }

    private static Map<String, Object> process(String id, String processName) {
        return Map.of("id", id, "kind", "PROCESS",
                "data", Map.of("processName", processName, "ports", List.of()));
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("id", source + "->" + target, "source", source, "target", target);
    }

    @SuppressWarnings("unchecked")
    private static String name(Map<String, Object> node) {
        return (String) ((Map<String, Object>) node.get("data")).get("processName");
    }
}
