package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回料纯度闸<b>接上了没有</b> —— 断言跑在产品真实入口 {@code listWipByFactory(factory, productTypeId, maxOrder)}
 * 上, 不是直接调 {@link ReworkPurityGuard}。
 *
 * <p>🔴 为什么要单独一个测: {@link ReworkPurityGuardTest} 证明的是「判定算得对」,
 * 它对「生产上根本没人调它」完全免疫 —— 本仓最高频的 bug 形状就是这个。
 * 这里只桩掉两个 repository, 判定/过滤/标记全部跑真实实现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("回料纯度闸接在半成品投料下拉上")
class WipInventoryReworkPurityWiringTest {

    private static final String FACTORY = "F006";

    private static final String US = "pt-us";          // 美式汉堡饼   level 1
    private static final String SNACK = "pt-snack";    // 小吃吧汉堡饼 level 2
    private static final String COOKED = "pt-cooked";  // 熟肉饼       level 3
    private static final String PATTY = "pt-patty";    // 汉堡排       不收回料
    private static final String UNSET = "pt-unset";    // 未配纯度等级

    @InjectMocks private WipInventoryServiceImpl service;

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductProcessWorkflowRepository productProcessWorkflowRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void injectWorkflowRepositoryField() {
        ReflectionTestUtils.setField(service, "productProcessWorkflowRepo", productProcessWorkflowRepo);
    }

    // ==================== Helpers ====================

    private ProductType product(String id, Integer purityLevel, Boolean acceptsRework) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setFactoryId(FACTORY);
        pt.setName(id);
        pt.setCode("C-" + id);
        pt.setUnit("kg");
        pt.setReworkPurityLevel(purityLevel);
        pt.setAcceptsRework(acceptsRework);
        return pt;
    }

    private SemiFinishedInventory reworkRow(String batchNo, String sourceProductTypeId) {
        SemiFinishedInventory w = row(batchNo, sourceProductTypeId);
        w.setIsRework(Boolean.TRUE);
        return w;
    }

    private SemiFinishedInventory row(String batchNo, String productTypeId) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setFactoryId(FACTORY);
        w.setIntermediateBatchNo(batchNo);
        w.setProductTypeId(productTypeId);
        w.setProcessOrder(1);
        w.setProducedQuantity(new BigDecimal("10"));
        w.setConsumedQuantity(BigDecimal.ZERO);
        w.setAvailableQuantity(new BigDecimal("10"));
        w.setUnit("kg");
        w.setStatus("AVAILABLE");
        w.setIsRework(Boolean.FALSE);
        return w;
    }

    /**
     * 桩住两个 repository。族过滤刻意让 {@code resolveFamilies} 返回空 map ⇒ planFamily 为 null
     * ⇒ 族这道闸整段跳过 —— 正是生产上的实际情况 (它拦不住这件事), 于是这个测量到的是<b>纯度闸自己</b>。
     */
    private void stub(List<SemiFinishedInventory> rows, List<ProductType> products) {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY)).thenReturn(new ArrayList<>(rows));
        when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(products);
        when(productFamilyResolver.resolveFamilies(anyString(), any())).thenReturn(Map.of());
        when(productProcessWorkflowRepo.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(FACTORY))
                .thenReturn(List.of());
    }

    private ProductProcessWorkflow publishedWorkflow(String ownerProductTypeId, String reworkProductTypeId) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setFactoryId(FACTORY);
        workflow.setProductTypeId(ownerProductTypeId);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setNodesJson("[{\"id\":\"rw\",\"data\":{\"skuId\":\"" + reworkProductTypeId
                + "\",\"isRework\":true}}]");
        return workflow;
    }

    private List<String> batchNosFor(String targetProductTypeId) {
        return service.listWipByFactory(FACTORY, targetProductTypeId, null).stream()
                .map(WipRowDTO::getIntermediateBatchNo)
                .toList();
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("先跑一次不带过滤的全量 —— 证明这 5 行本来都在, 后面的缺席才是闸干的")
    void unfilteredSnapshotListsEveryRowSoLaterAbsencesAreAttributable() {
        stub(List.of(
                reworkRow("RW-US", US), reworkRow("RW-SNACK", SNACK),
                reworkRow("RW-COOKED", COOKED), reworkRow("RW-PATTY", PATTY),
                row("GOOD", US)),
                List.of(product(US, 1, true), product(SNACK, 2, true),
                        product(COOKED, 3, true), product(PATTY, null, false)));

        List<WipRowDTO> all = service.listWipByFactory(FACTORY);

        assertThat(all).extracting(WipRowDTO::getIntermediateBatchNo)
                .containsExactlyInAnyOrder("RW-US", "RW-SNACK", "RW-COOKED", "RW-PATTY", "GOOD");
        // 无目标产品的快照谈不上纯度判定 —— 标记必须是 null, ⛔ 不能默认成 ENFORCED。
        assertThat(all).allSatisfy(r -> assertThat(r.getReworkPurityStatus()).isNull());
    }

    @Test
    @DisplayName("投料下拉: 美式只收美式的回料; 小吃吧收美式+小吃吧; 熟肉饼三个都收")
    void pickerAppliesTheCustomerMatrix() {
        List<SemiFinishedInventory> rows = List.of(
                reworkRow("RW-US", US), reworkRow("RW-SNACK", SNACK), reworkRow("RW-COOKED", COOKED));
        List<ProductType> products = List.of(
                product(US, 1, true), product(SNACK, 2, true), product(COOKED, 3, true));

        stub(rows, products);
        assertThat(batchNosFor(US)).containsExactly("RW-US");

        stub(rows, products);
        assertThat(batchNosFor(SNACK)).containsExactlyInAnyOrder("RW-US", "RW-SNACK");

        stub(rows, products);
        assertThat(batchNosFor(COOKED)).containsExactlyInAnyOrder("RW-US", "RW-SNACK", "RW-COOKED");
    }

    @Test
    @DisplayName("生产形态: 库存挂独立回料 SKU 时，纯度取发布工艺 owner 成品档案而不是回料 SKU")
    void pickerResolvesPurityFromPublishedWorkflowOwner() {
        String cookedReworkSku = "pt-rework-cooked";
        stub(List.of(reworkRow("RW-COOKED-SKU", cookedReworkSku)),
                List.of(product(cookedReworkSku, null, true), product(COOKED, 3, true),
                        product(US, 1, true), product(PATTY, 4, false)));
        when(productProcessWorkflowRepo.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(FACTORY))
                .thenReturn(List.of(publishedWorkflow(COOKED, cookedReworkSku)));

        assertThat(batchNosFor(US)).doesNotContain("RW-COOKED-SKU");

        // 同一生产形态下的阳性对照：熟肉饼收自己的回料，且必须是已执行配置的 ENFORCED。
        List<WipRowDTO> cookedOptions = service.listWipByFactory(FACTORY, COOKED, null);
        assertThat(cookedOptions).filteredOn(r -> "RW-COOKED-SKU".equals(r.getIntermediateBatchNo()))
                .singleElement()
                .satisfies(r -> assertThat(r.getReworkPurityStatus()).isEqualTo("ENFORCED"));

        assertThat(batchNosFor(PATTY)).doesNotContain("RW-COOKED-SKU");
    }

    @Test
    @DisplayName("汉堡排 accepts_rework=false: 回料一条不剩, 而良品行照常在 (闸只打回料)")
    void pattyRejectsEveryReworkButKeepsGoodSemiFinished() {
        stub(List.of(reworkRow("RW-US", US), reworkRow("RW-SNACK", SNACK), row("GOOD", US)),
                List.of(product(US, 1, true), product(SNACK, 2, true), product(PATTY, null, false)));

        assertThat(batchNosFor(PATTY)).containsExactly("GOOD");
    }

    @Test
    @DisplayName("等级未配置: 放行, 且返回项带 UNCONFIGURED 标记 (不是 ENFORCED)")
    void unconfiguredPurityPassesWithAnHonestMarker() {
        stub(List.of(reworkRow("RW-UNSET", UNSET), reworkRow("RW-US", US)),
                List.of(product(UNSET, null, true), product(US, 1, true), product(COOKED, 3, true)));

        List<WipRowDTO> options = service.listWipByFactory(FACTORY, COOKED, null);

        assertThat(options).extracting(WipRowDTO::getIntermediateBatchNo)
                .containsExactlyInAnyOrder("RW-UNSET", "RW-US");
        assertThat(options).filteredOn(r -> "RW-UNSET".equals(r.getIntermediateBatchNo()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getReworkPurityStatus()).isEqualTo("UNCONFIGURED");
                    assertThat(r.getIsRework()).isTrue();
                });
        // 阴性对照: 同一次调用里配了等级的那条必须是 ENFORCED —— 否则「全是 UNCONFIGURED」
        // 这个读数无法区分「标记算对了」和「标记恒为 UNCONFIGURED」。
        assertThat(options).filteredOn(r -> "RW-US".equals(r.getIntermediateBatchNo()))
                .singleElement()
                .satisfies(r -> assertThat(r.getReworkPurityStatus()).isEqualTo("ENFORCED"));
    }

    @Test
    @DisplayName("目标产品档案查不到时不拦 —— 未配置口径一致, 且不静默清空下拉")
    void missingTargetProductDoesNotEmptyTheDropdown() {
        stub(List.of(reworkRow("RW-COOKED", COOKED)),
                List.of(product(COOKED, 3, true)));   // 目标 US 的档案刻意不给

        List<WipRowDTO> options = service.listWipByFactory(FACTORY, US, null);

        assertThat(options).extracting(WipRowDTO::getIntermediateBatchNo).containsExactly("RW-COOKED");
        assertThat(options).singleElement()
                .satisfies(r -> assertThat(r.getReworkPurityStatus()).isEqualTo("UNCONFIGURED"));
    }

    @Test
    @DisplayName("非回料行永不带纯度标记 —— 闸的作用面只有 isRework")
    void goodSemiFinishedNeverCarriesAPurityMarker() {
        stub(List.of(row("GOOD-COOKED", COOKED), reworkRow("RW-US", US)),
                List.of(product(COOKED, 3, true), product(US, 1, true)));

        List<WipRowDTO> options = service.listWipByFactory(FACTORY, US, null);

        // 熟肉饼的【良品】半成品照样能选给美式 (那是同族/阶段两道闸的事, 不归纯度管),
        // 而熟肉饼的【回料】不能 —— 这一对正是「闸只打回料」的判据。
        assertThat(options).extracting(WipRowDTO::getIntermediateBatchNo)
                .containsExactlyInAnyOrder("GOOD-COOKED", "RW-US");
        assertThat(options).filteredOn(r -> "GOOD-COOKED".equals(r.getIntermediateBatchNo()))
                .singleElement()
                .satisfies(r -> assertThat(r.getReworkPurityStatus()).isNull());
    }
}
