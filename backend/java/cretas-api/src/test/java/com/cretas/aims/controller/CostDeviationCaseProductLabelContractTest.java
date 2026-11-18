package com.cretas.aims.controller;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.DailyReconcileRunRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter;
import com.cretas.aims.service.shortage.CostDeviationOwnerResolver;
import com.cretas.aims.service.shortage.CostDeviationScanService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 44: 「发现的问题」/ 成本损耗异常列表上, 明细 product_name 为空 ≠ 产品被删了。
 * 走真实出口 {@link CostDeviationCaseController#list}: 名字空但产品在 ⇒ 显示真名;
 * 产品档案真查不到 ⇒ 才说「产品已删除或不存在」(阴性对照)。
 */
class CostDeviationCaseProductLabelContractTest {

    private static final String F = "F006";

    private final ShortageCaseRepository caseRepository = mock(ShortageCaseRepository.class);
    private final CostDeviationCaseDetailRepository detailRepository = mock(CostDeviationCaseDetailRepository.class);
    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final PlanCostVarianceService varianceService = mock(PlanCostVarianceService.class);
    private final PriceMaskResolver priceMaskResolver = mock(PriceMaskResolver.class);
    private final ProcessSecretLlmCostScope costScope = mock(ProcessSecretLlmCostScope.class);

    private CostDeviationCaseController controller;

    @BeforeEach
    void setUp() {
        controller = new CostDeviationCaseController(caseRepository, mock(ShortageCaseEventRepository.class),
                detailRepository, planRepository, mock(ProductionBatchRepository.class), mock(UserRepository.class),
                varianceService, mock(MaterialBalanceService.class), mock(CostDeviationScanService.class),
                mock(CostDeviationCaseWriter.class), priceMaskResolver, costScope, mock(UnitContractService.class),
                mock(CostDeviationOwnerResolver.class), mock(DailyReconcileRunRepository.class));
    }

    private void givenCases(Object[][] rows) {
        List<ShortageCase> cases = new java.util.ArrayList<>();
        List<CostDeviationCaseDetail> details = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            String caseId = (String) r[0], planId = (String) r[1], storedName = (String) r[2], productTypeId = (String) r[3];
            ShortageCase c = new ShortageCase();
            c.setId(caseId);
            c.setFactoryId(F);
            c.setSourceType(ShortageCase.SourceType.MATERIAL_BALANCE);
            c.setStage(ShortageCase.Stage.DETECTED);
            cases.add(c);
            CostDeviationCaseDetail d = new CostDeviationCaseDetail();
            d.setCaseId(caseId);
            d.setFactoryId(F);
            d.setPlanId(planId);
            d.setPlanNumber("PLAN-" + planId);
            d.setProductName(storedName);
            details.add(d);
            ProductionPlan p = new ProductionPlan();
            p.setId(planId);
            p.setFactoryId(F);
            p.setProductTypeId(productTypeId);
            when(planRepository.findByIdAndFactoryId(planId, F)).thenReturn(Optional.of(p));
        }
        when(caseRepository.findForFactoryBySources(eq(F), anyBoolean(), anyCollection(), any()))
                .thenReturn(new PageImpl<>(cases));
        when(detailRepository.findByCaseIdIn(any())).thenReturn(details);
        when(costScope.isProtectedBatch(anyString(), any(), anyString())).thenReturn(false);
    }

    private Map<String, String> labels() {
        return controller.list(F, true, 0, 50, null).getData().stream()
                .collect(Collectors.toMap(CostDeviationCaseController.CaseView::id,
                        CostDeviationCaseController.CaseView::productName, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void emptyStoredName_butProductExists_showsRealName_onlyMissingProductSaysDeleted() {
        givenCases(new Object[][]{
                {"c-raw", "p-raw", null, "RMT_F006_LSM_X"},        // 挂原料的工艺图: 名字写入时被置空, 产品在
                {"c-gone", "p-gone", null, "PT-DELETED"},          // 产品档案真查不到
                {"c-stored", "p-stored", "【演示L1】酱牛肉 250g", "PT-1"}, // 已存名字照用, 不回查
                {"c-anon", "p-anon", null, "RMT_F006_LSM_Y"},      // 产品在但只有内部占位名
        });
        when(varianceService.productName(F, "RMT_F006_LSM_X")).thenReturn("西装鸡（16只）");
        when(varianceService.productName(F, "PT-DELETED")).thenReturn(null);
        when(varianceService.productName(F, "RMT_F006_LSM_Y")).thenReturn("RMT_F006_LSM_Y");

        Map<String, String> l = labels();
        assertThat(l.get("c-raw")).isEqualTo("西装鸡（16只）");
        assertThat(l.get("c-gone")).isEqualTo(CostDeviationCaseController.PRODUCT_MISSING_LABEL);
        assertThat(l.get("c-stored")).isEqualTo("【演示L1】酱牛肉 250g");
        assertThat(l.get("c-anon")).isEqualTo(CostDeviationCaseController.PRODUCT_UNNAMED_LABEL);
        // 只有真查不到的那一条说「已删除」
        assertThat(l.values().stream().filter(CostDeviationCaseController.PRODUCT_MISSING_LABEL::equals).count()).isEqualTo(1);
    }

    @Test
    void planItselfNotFound_saysDeleted() {
        assertThat(CostDeviationCaseController.productLabel(null)).isEqualTo(CostDeviationCaseController.PRODUCT_MISSING_LABEL);
        assertThat(CostDeviationCaseController.productLabel("  ")).isEqualTo(CostDeviationCaseController.PRODUCT_MISSING_LABEL);
        assertThat(CostDeviationCaseController.productLabel("__RAW_WORKFLOW_OWNER__RMT_1"))
                .isEqualTo(CostDeviationCaseController.PRODUCT_UNNAMED_LABEL);
        assertThat(CostDeviationCaseController.productLabel("香煎鸡胸")).isEqualTo("香煎鸡胸");
    }
}
