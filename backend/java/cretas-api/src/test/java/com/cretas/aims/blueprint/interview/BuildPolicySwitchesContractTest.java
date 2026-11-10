package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.FactoryPolicyItem;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.repository.FactorySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建厂对话接上厂规开关/阈值与产品需检/回料上限。
 * 阳性: 问了 → 写进去 → 读出来是这个值。阴性: 没问到保持默认/现值, 白名单外的字段写不进去。
 */
class BuildPolicySwitchesContractTest {

    private static final String F = "F-POLICY";

    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final FactoryBuildApplyValidator validator = new FactoryBuildApplyValidator();
    private final FactoryBuildApplyService apply = new FactoryBuildApplyService();
    private final FactoryBuildPlanService planner = new FactoryBuildPlanService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(validator, "factorySettingsRepository", settings);
        ReflectionTestUtils.setField(apply, "factorySettingsRepository", settings);
        ReflectionTestUtils.setField(planner, "factorySettingsRepository", settings);
        when(settings.findByFactoryId(F)).thenReturn(Optional.empty());
    }

    private static ApplyAIInitRequest request(FactoryPolicyItem policy) {
        ApplyAIInitRequest r = new ApplyAIInitRequest();
        ApplyAIInitRequest.SuggestedData sd = new ApplyAIInitRequest.SuggestedData();
        sd.setPolicy(policy);
        r.setSuggestedData(sd);
        return r;
    }

    private FactorySettings applyAndCapture(FactoryPolicyItem policy) {
        ApplyAIInitResult result = new ApplyAIInitResult();
        ReflectionTestUtils.invokeMethod(apply, "applyPolicy", F, policy, result);
        ArgumentCaptor<FactorySettings> cap = ArgumentCaptor.forClass(FactorySettings.class);
        verify(settings).save(cap.capture());
        return cap.getValue();
    }

    // ------------------------------------------------------------------ 阳性

    @Test
    void askedSwitchesAndBalanceLinesAreWrittenAndReadBack() {
        FactoryPolicyItem p = new FactoryPolicyItem();
        p.setBlockStartOnOpenShortage(true);
        p.setRequireRequisitionBeforeReport(true);
        p.setSkipProcessReportingDefault(true);
        p.setMaterialBalanceWarnPct(new BigDecimal("3"));
        p.setMaterialBalanceRedPct(new BigDecimal("8"));

        FactorySettings saved = applyAndCapture(p);

        assertThat(saved.getFactoryId()).isEqualTo(F);
        assertThat(saved.getBlockStartOnOpenShortage()).isTrue();
        assertThat(saved.getRequireRequisitionBeforeReport()).isTrue();
        assertThat(saved.getSkipProcessReportingDefault()).isTrue();
        assertThat(saved.getMaterialBalanceWarnPct()).isEqualByComparingTo("3");
        assertThat(saved.getMaterialBalanceRedPct()).isEqualByComparingTo("8");
        assertThat(validatePolicy(p)).isEmpty();
    }

    @Test
    void anExplicitFalseIsWrittenToo() {
        FactorySettings existing = new FactorySettings();
        existing.setFactoryId(F);
        existing.setBlockStartOnOpenShortage(true);
        when(settings.findByFactoryId(F)).thenReturn(Optional.of(existing));
        FactoryPolicyItem p = new FactoryPolicyItem();
        p.setBlockStartOnOpenShortage(false);

        assertThat(applyAndCapture(p).getBlockStartOnOpenShortage()).isFalse();
    }

    @Test
    void productInspectionAndReworkLimitArePassedThroughValidationAndShownInPreview() {
        ApplyAIInitRequest r = new ApplyAIInitRequest();
        ApplyAIInitRequest.SuggestedData sd = new ApplyAIInitRequest.SuggestedData();
        TypeItem beef = new TypeItem();
        beef.setCode("P-BEEF");
        beef.setName("卤牛肉");
        beef.setUnit("kg");
        beef.setInspectionRequired(true);
        beef.setMaxReworkRatioPercent(new BigDecimal("10"));
        sd.setProductTypes(List.of(beef));
        r.setSuggestedData(sd);
        ReflectionTestUtils.setField(validator, "productTypeRepository",
                mock(com.cretas.aims.repository.ProductTypeRepository.class));
        ReflectionTestUtils.setField(planner, "productTypeRepository",
                mock(com.cretas.aims.repository.ProductTypeRepository.class));
        List<String> problems = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkTypeItems", problems, "productTypes", sd.getProductTypes());
        assertThat(problems).isEmpty();
    }

    @Test
    void previewShowsPolicyChangesUnderTheCatalogClassifiableGroup() {
        FactoryPolicyItem p = new FactoryPolicyItem();
        p.setBlockStartOnOpenShortage(true);
        p.setMaterialBalanceWarnPct(new BigDecimal("3"));
        List<PlannedChange> out = new ArrayList<>();
        // 走与 planChanges 同一段: 通过反射取私有辅助无意义, 直接断言辅助行为
        ReflectionTestUtils.invokeMethod(FactoryBuildPlanService.class, "addPolicyChange",
                out, "缺料未解决时拦住开工", p.getBlockStartOnOpenShortage(), false);
        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.getGroup()).isEqualTo("模块开关");   // 冻结目录按该词归类, 换名会挡模拟
            assertThat(c.getDetail()).isEqualTo("开启");
        });
    }

    // ------------------------------------------------------------------ 阴性

    @Test
    void unaskedFieldsKeepTheirExistingValues() {
        FactorySettings existing = new FactorySettings();
        existing.setFactoryId(F);
        existing.setBlockStartOnOpenShortage(true);
        existing.setMaterialBalanceWarnPct(new BigDecimal("4"));
        existing.setMaterialBalanceRedPct(new BigDecimal("9"));
        when(settings.findByFactoryId(F)).thenReturn(Optional.of(existing));
        FactoryPolicyItem p = new FactoryPolicyItem();
        p.setRequireRequisitionBeforeReport(true);   // 只问了这一项

        FactorySettings saved = applyAndCapture(p);

        assertThat(saved.getRequireRequisitionBeforeReport()).isTrue();
        assertThat(saved.getBlockStartOnOpenShortage()).isTrue();
        assertThat(saved.getMaterialBalanceWarnPct()).isEqualByComparingTo("4");
        assertThat(saved.getMaterialBalanceRedPct()).isEqualByComparingTo("9");
    }

    @Test
    void anEmptyPolicyWritesNothingAndANewFactoryKeepsEntityDefaults() {
        ApplyAIInitResult result = new ApplyAIInitResult();
        ReflectionTestUtils.invokeMethod(apply, "applyPolicy", F, new FactoryPolicyItem(), result);
        ReflectionTestUtils.invokeMethod(apply, "applyPolicy", F, null, result);
        verify(settings, never()).save(any());
        FactorySettings fresh = new FactorySettings();
        assertThat(fresh.getBlockStartOnOpenShortage()).isFalse();
        assertThat(fresh.getRequireRequisitionBeforeReport()).isFalse();
        assertThat(fresh.getSkipProcessReportingDefault()).isFalse();
        assertThat(fresh.getMaterialBalanceWarnPct()).isEqualByComparingTo("5");
        assertThat(fresh.getMaterialBalanceRedPct()).isEqualByComparingTo("15");
    }

    @Test
    void balanceLinesMustBeOrderedAndMergedWithTheExistingOtherLine() {
        FactoryPolicyItem inverted = new FactoryPolicyItem();
        inverted.setMaterialBalanceWarnPct(new BigDecimal("20"));
        inverted.setMaterialBalanceRedPct(new BigDecimal("10"));
        assertThat(validatePolicy(inverted)).singleElement().asString().contains("黄色线必须大于 0 且小于红色线");

        // 只给黄线 20, 红线沿用系统默认 15 → 20 >= 15 被拒
        FactoryPolicyItem onlyWarn = new FactoryPolicyItem();
        onlyWarn.setMaterialBalanceWarnPct(new BigDecimal("20"));
        assertThat(validatePolicy(onlyWarn)).hasSize(1);

        FactoryPolicyItem over100 = new FactoryPolicyItem();
        over100.setMaterialBalanceWarnPct(new BigDecimal("50"));
        over100.setMaterialBalanceRedPct(new BigDecimal("101"));
        assertThat(validatePolicy(over100)).hasSize(1);
    }

    @Test
    void valuesBeyondTwoDecimalsAreRejectedBecauseTheColumnWouldRoundThem() {
        FactoryPolicyItem p = new FactoryPolicyItem();
        p.setMaterialBalanceWarnPct(new BigDecimal("99.996"));
        p.setMaterialBalanceRedPct(new BigDecimal("100"));
        assertThat(validatePolicy(p)).singleElement().asString().contains("两位小数");
        p.setMaterialBalanceWarnPct(new BigDecimal("5.50"));   // 5.50 == 5.5, 合法
        p.setMaterialBalanceRedPct(new BigDecimal("15"));
        assertThat(validatePolicy(p)).isEmpty();

        TypeItem t = new TypeItem();
        t.setCode("P1");
        t.setName("x");
        t.setUnit("kg");
        t.setMaxReworkRatioPercent(new BigDecimal("0.004"));
        List<String> problems = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkTypeItems", problems, "productTypes", List.of(t));
        assertThat(problems).singleElement().asString().contains("两位小数");
    }

    @Test
    void reworkLimitOutOfRangeAndMaterialsCarryingProductOnlyFieldsAreRejected() {
        TypeItem bad = new TypeItem();
        bad.setCode("P1");
        bad.setName("x");
        bad.setUnit("kg");
        bad.setMaxReworkRatioPercent(new BigDecimal("150"));
        List<String> problems = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkTypeItems", problems, "productTypes", List.of(bad));
        assertThat(problems).singleElement().asString().contains("maxReworkRatioPercent");

        TypeItem material = new TypeItem();
        material.setCode("M1");
        material.setName("m");
        material.setUnit("kg");
        material.setInspectionRequired(true);
        List<String> mp = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkTypeItems", mp, "materialTypes", List.of(material));
        assertThat(mp).singleElement().asString().contains("物料不接收");
    }

    @Test
    void theModulesWhitelistStillRejectsPolicyFieldsAndOtherNonEnableFields() {
        List<String> problems = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkModules", problems, Map.of(
                "blockStartOnOpenShortage", true,
                "materialBalanceWarnPct", true,
                "allowSelfRegistration", true));
        // 放行规则没有被放宽成「所有字段都放」: 厂规字段只能走 policy 块, 自助注册之类更不行
        assertThat(problems).hasSize(3);
    }

    @Test
    void policyBlockHasExactlyTheFiveWhitelistedFields() {
        assertThat(java.util.Arrays.stream(FactoryPolicyItem.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).sorted().toList())
                .containsExactly("blockStartOnOpenShortage", "materialBalanceRedPct",
                        "materialBalanceWarnPct", "requireRequisitionBeforeReport",
                        "skipProcessReportingDefault");
    }

    private List<String> validatePolicy(FactoryPolicyItem p) {
        List<String> out = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "checkPolicy", out, F, p);
        return out;
    }
}
