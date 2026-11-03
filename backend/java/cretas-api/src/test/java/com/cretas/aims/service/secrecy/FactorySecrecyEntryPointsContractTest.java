package com.cretas.aims.service.secrecy;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.controller.ProcessSecretController;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.datacenter.impl.ExportExecutor;
import com.cretas.aims.service.execution.WorkdeskOutputSummarizer;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretExternalService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretPolicyService;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * c / e / f 与 a 的单独接线点 (保密策略接口、管理员改凭证闸、导出), 每项在同一个入口上做开 / 关对照;
 * 读不到配置按开启。配置一律经真实的 {@link FactorySecrecyConfig} (仓库是内存里的行)。
 */
class FactorySecrecyEntryPointsContractTest {

    static final String F = "F-SEC-E";
    static final String SECRET = "P-SEC-E";
    static final long OWNER = 21L;

    final List<FactorySecrecySetting> rows = new ArrayList<>();
    final FactorySecrecySettingRepository settings = mock(FactorySecrecySettingRepository.class);
    FactorySecrecyConfig config;

    @BeforeEach
    void setUp() {
        when(settings.findByFactoryId(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getFactoryId().equals(inv.getArgument(0))).toList());
        config = new FactorySecrecyConfig(settings, (org.springframework.transaction.PlatformTransactionManager) null);
    }

    /** MockMvc 在被测处理抛异常时不还原 RequestContextHolder: 不清就漏进同线程的下一个测试 (会冒出一个登录身份)。 */
    @org.junit.jupiter.api.AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    void set(String factory, SecrecyItem item, Boolean enabled) {
        rows.removeIf(r -> r.getFactoryId().equals(factory) && r.getItemCode().equals(item.name()));
        rows.add(FactorySecrecySetting.builder().factoryId(factory).itemCode(item.name()).enabled(enabled).updatedBy(1L).build());
        config.invalidate(factory);
    }

    // ------------------------------------------------------------------ c: 出外脱敏

    @Test
    void egressRedactionOffSendsTheWorkdeskSummaryUnredactedForThatFactoryOnly() {
        DashScopeClient llm = mock(DashScopeClient.class);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(anyString(), sent.capture())).thenReturn("今日跟进: 张记食品有限公司 应收 12800 元。");
        WorkdeskOutputSummarizer summarizer = summarizer(llm);

        set(F, SecrecyItem.EGRESS_REDACTION, true);
        summarizer.apply(followup(), F);
        assertThat(sent.getValue()).doesNotContain("张记食品有限公司", "13812345678").contains("[客户A]");

        set(F, SecrecyItem.EGRESS_REDACTION, false);
        summarizer.apply(followup(), F);
        assertThat(sent.getValue()).contains("张记食品有限公司", "13812345678").doesNotContain("[客户A]");
    }

    /**
     * 提示词约束 (a): 跨厂一句是隔离, 一律带; 工艺保密一句只在该厂工艺保密开着 (且有保密产品) 时带。
     * 走真实入口: 工作台摘要发给模型的系统提示词。
     */
    @Test
    void theCrossFactorySentenceIsAlwaysSentAndOnlyTheProcessSecretClauseFollowsItemA() {
        DashScopeClient llm = mock(DashScopeClient.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(system.capture(), anyString())).thenReturn("今日跟进: 张记食品有限公司 应收 12800 元。");
        WorkdeskOutputSummarizer summarizer = summarizer(llm);
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretPolicy policy = policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, mock(ProcessSecretGrantRepository.class),
                mock(ProcessSecretAuditRecorder.class));
        access.setFactorySecrecyConfig(config);
        ReflectionTestUtils.setField(summarizer, "llmCostScope", new ProcessSecretLlmCostScope(
                mock(com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions.class), access));

        set(F, SecrecyItem.PROCESS_SECRET, true);
        summarizer.apply(followup(), F);
        assertThat(system.getValue()).contains(ProcessSecretLlmCostScope.CROSS_FACTORY_HARNESS,
                ProcessSecretLlmCostScope.PROCESS_SECRET_CLAUSE);

        set(F, SecrecyItem.PROCESS_SECRET, false);
        access.invalidate(F);
        summarizer.apply(followup(), F);
        assertThat(system.getValue()).as("a 关: 跨厂约束照样发")
                .contains(ProcessSecretLlmCostScope.CROSS_FACTORY_HARNESS)
                .doesNotContain(ProcessSecretLlmCostScope.PROCESS_SECRET_CLAUSE);
        assertThat(system.getAllValues()).hasSize(2);   // 两次都真的发了 (否则上面的读数无意义)
    }

    @Test
    void anUnknownEgressFactoryIsStillRefusedWithRedactionOffEverywhere() {
        set(F, SecrecyItem.EGRESS_REDACTION, false);
        set("F-OTHER", SecrecyItem.EGRESS_REDACTION, false);
        LlmEgressGuard guard = guard();
        // 没有显式工厂、没有 analysing、没有登录身份 → 拒发
        assertThat(guard.structured("t", null, Map.of("customerName", "张记食品有限公司")).refused()).isTrue();
        assertThat(guard.texts("t", null, "联系 13812345678").refused()).isTrue();
        // 平台账号不按 token 猜工厂
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin"))) {
            assertThat(guard.structured("t", null, Map.of("customerName", "张记食品有限公司")).refused()).isTrue();
            // 服务端认定的工厂 (analysing) 照旧生效: 那家关了才原样发
            try (LlmEgressGuard.AnalysedFactoryScope scope = LlmEgressGuard.analysing(F)) {
                LlmEgressRedactor.Result r = guard.structured("t", null, Map.of("customerName", "张记食品有限公司"));
                assertThat(r.refused()).isFalse();
                assertThat(r.payload()).contains("张记食品有限公司");
            }
        }
    }

    @Test
    void theUtteranceExitMasksNumbersUnlessTheKnownFactoryTurnedRedactionOff() {
        LlmEgressGuard guard = guard();
        set(F, SecrecyItem.EGRESS_REDACTION, true);
        try (LlmEgressGuard.AnalysedFactoryScope scope = LlmEgressGuard.analysing(F)) {
            assertThat(guard.utterance("t", "打给 13812345678").text(0)).doesNotContain("13812345678");
            set(F, SecrecyItem.EGRESS_REDACTION, false);
            assertThat(guard.utterance("t", "打给 13812345678").text(0)).contains("13812345678");
        }
        // 不知道工厂: 照旧抹号码 (这条路不因没有工厂而拒绝)
        assertThat(guard.utterance("t", "打给 13812345678").text(0)).doesNotContain("13812345678");
    }

    /** 审查 #3: 工厂表里没有的工厂编号, 外发照样脱敏 (不按「没有配置行 = 出外脱敏关」原样发)。 */
    @Test
    void egressForAFactoryIdThatDoesNotExistIsStillRedacted() {
        com.cretas.aims.repository.FactoryRepository factories = mock(com.cretas.aims.repository.FactoryRepository.class);
        when(factories.existsById(F)).thenReturn(true);
        when(factories.existsById("F-GHOST")).thenReturn(false);
        config = new FactorySecrecyConfig(settings, factories, (org.springframework.transaction.PlatformTransactionManager) null);
        LlmEgressGuard guard = guard();
        LlmEgressRedactor.Result ghost = guard.structured("t", "F-GHOST", Map.of("customerName", "张记食品有限公司"));
        assertThat(ghost.refused()).isFalse();
        assertThat(ghost.payload()).doesNotContain("张记食品有限公司");
        // 对照: 存在的工厂、出外脱敏关 → 原样发
        set(F, SecrecyItem.EGRESS_REDACTION, false);
        assertThat(guard.structured("t", F, Map.of("customerName", "张记食品有限公司")).payload()).contains("张记食品有限公司");
    }

    @Test
    void egressFailsClosedWhenTheConfigCannotBeRead() {
        when(settings.findByFactoryId(anyString())).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));
        LlmEgressRedactor.Result r = guard().structured("t", F, Map.of("customerName", "张记食品有限公司"));
        assertThat(r.refused()).isFalse();
        assertThat(r.payload()).doesNotContain("张记食品有限公司");
    }

    // ------------------------------------------------------------------ f: AI 保密审计

    @Test
    void theAiAccessAuditFollowsItsSwitch() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.audit(anyString(), any(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        ProcessSecretLlmCostScope scope = mock(ProcessSecretLlmCostScope.class);
        when(scope.readerView(anyString())).thenReturn(ProcessSecretLlmCostScope.ReaderView.NO_PROTECTED);
        ProcessSecretAiAccessAudit audit = new ProcessSecretAiAccessAudit(access, scope);
        audit.setFactorySecrecyConfig(config);

        set(F, SecrecyItem.PROCESS_SECRET, true);   // f 跟随 a → 开
        try (ProcessSecretAiAccessAudit.Access ignored = audit.begin(F, ProcessSecretAiAccessAudit.Entry.AI_TOOL)) {
            // 请求外: 最外层结束时写
        }
        verify(access).audit(eq(F), any(), any(), eq(ProcessSecretAction.AI_ACCESS), anyString(), anyString(), anyString());

        set(F, SecrecyItem.AI_SECRECY_AUDIT, false);   // 单独关
        org.mockito.Mockito.clearInvocations(access);
        try (ProcessSecretAiAccessAudit.Access ignored = audit.begin(F, ProcessSecretAiAccessAudit.Entry.AI_TOOL)) {
        }
        verify(access, never()).audit(anyString(), any(), any(), any(), anyString(), anyString(), anyString());

        // 工厂不明照记
        try (ProcessSecretAiAccessAudit.Access ignored = audit.begin(null, ProcessSecretAiAccessAudit.Entry.AI_TOOL)) {
        }
        verify(access).audit(eq("UNKNOWN"), any(), any(), eq(ProcessSecretAction.AI_ACCESS), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ e: 客户侧工艺包

    @Test
    void customerHeldOffStopsRegisteringPackagesAndReceivingResults() {
        ProcessSecretPolicy held = policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        ProcessSecretExternalService external = com.cretas.aims.service.processsecret.SecrecyExternalTestHooks.external(held);
        external.setFactorySecrecyConfig(config);
        AuthenticatedActor owner = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OWNER, F, "operator");
        ProcessSecretExternalService.PackageRequest request = new ProcessSecretExternalService.PackageRequest(
                SECRET, "pkg-1", "v1", "kg", "not-a-key");

        set(F, SecrecyItem.CUSTOMER_HELD, false);
        assertThatThrownBy(() -> external.registerPackage(owner, F, request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("本厂未开启「客户侧保管」");
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        // 开着: 过了这道检查, 停在后面的公钥校验 (说明开关那一步放行了)
        assertThatThrownBy(() -> external.registerPackage(owner, F, request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("executorPublicKey");
    }

    // ------------------------------------------------------------------ a: 保密策略接口 / 改凭证闸 / 导出

    @Test
    void processSecretEndpointsAnswerNotEnabledWhileTheSwitchIsOff() throws Exception {
        ProcessSecretPolicyService policyService = mock(ProcessSecretPolicyService.class);
        when(policyService.listPolicies(any(), eq(F))).thenReturn(List.of());
        ProcessSecretExternalService external = mock(ProcessSecretExternalService.class);
        ProcessSecretController controller = new ProcessSecretController(policyService, external);
        controller.setFactorySecrecyConfig(config);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        AuthenticatedActor owner = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OWNER, F, "operator");

        set(F, SecrecyItem.PROCESS_SECRET, false);
        assertThatThrownBy(() -> mvc.perform(get("/api/mobile/" + F + "/process-secrets/policies")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, owner)))
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause().hasMessage(ProcessSecretController.NOT_ENABLED_MESSAGE);
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        verify(policyService, never()).listPolicies(any(), anyString());
        assertThatThrownBy(() -> mvc.perform(get("/api/mobile/" + F + "/process-secrets/external-packages")
                .param("productTypeId", SECRET).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, owner)))
                .hasRootCauseInstanceOf(BusinessException.class);
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

        // Q2=A: a 关、e 开 —— 策略接口和 B 档接口都要能用 (配置 B 档产品)
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        mvc.perform(get("/api/mobile/" + F + "/process-secrets/policies")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, owner)).andExpect(status().isOk());
        mvc.perform(get("/api/mobile/" + F + "/process-secrets/external-packages").param("productTypeId", SECRET)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, owner)).andExpect(status().isOk());
        verify(policyService).listPolicies(owner, F);

        set(F, SecrecyItem.CUSTOMER_HELD, false);
        set(F, SecrecyItem.PROCESS_SECRET, true);
        mvc.perform(get("/api/mobile/" + F + "/process-secrets/policies")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, owner)).andExpect(status().isOk());
        verify(policyService, org.mockito.Mockito.times(2)).listPolicies(owner, F);
    }

    @Test
    void adminCredentialResetIsBlockedForAnOwnerOnlyWhileTheirTierIsInEffect() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        when(policies.existsByFactoryIdAndOwnerUserId(F, OWNER)).thenReturn(true);
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy(ProcessSecretStorageMode.CLOUD_RESTRICTED)));
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, mock(ProcessSecretGrantRepository.class),
                mock(ProcessSecretAuditRecorder.class));
        access.setFactorySecrecyConfig(config);
        AuthenticatedActor admin = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, F, "factory_super_admin");

        set(F, SecrecyItem.PROCESS_SECRET, true);
        assertThatThrownBy(() -> access.requireAdminMayChangeCredentials(admin, F, OWNER)).isInstanceOf(BusinessException.class);
        set(F, SecrecyItem.PROCESS_SECRET, false);
        access.requireAdminMayChangeCredentials(admin, F, OWNER);   // A 档不生效: 放行
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy(ProcessSecretStorageMode.CUSTOMER_HELD)));
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        assertThatThrownBy(() -> access.requireAdminMayChangeCredentials(admin, F, OWNER))
                .as("a 关、e 开: B 档负责人仍受保护").isInstanceOf(BusinessException.class);
    }

    @Test
    void exportMasksTheProtectedProductOnlyWhileProcessSecretIsOnAndWhenTheConfigCannotBeRead() {
        ExportExecutor executor = exportExecutor();
        ExportRule rule = new ExportRule();
        rule.setId(7L);
        rule.setFactoryId(F);
        rule.setTargetEntity("ProductionBatch");
        AuthenticatedActor colleague = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, F, "operator");

        set(F, SecrecyItem.PROCESS_SECRET, true);
        assertThat(exportRow(executor, rule, colleague)).containsEntry("totalCost", "已保密");
        set(F, SecrecyItem.PROCESS_SECRET, false);
        assertThat(exportRow(executor, rule, colleague)).containsEntry("totalCost", 900);
        // 读不到配置 → 按开启
        when(settings.findByFactoryId(anyString())).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));
        config.invalidate(F);
        assertThat(exportRow(executor, rule, colleague)).containsEntry("totalCost", "已保密");
    }

    // ------------------------------------------------------------------ helpers

    LlmEgressGuard guard() {
        ProcessSecretSampleFilter names = mock(ProcessSecretSampleFilter.class);
        when(names.protectedProductNames(anyString())).thenReturn(Set.of());
        LlmEgressGuard guard = new LlmEgressGuard(new ObjectMapper(), names);
        guard.setFactorySecrecyConfig(config);
        return guard;
    }

    WorkdeskOutputSummarizer summarizer(DashScopeClient llm) {
        WorkdeskOutputSummarizer summarizer = new WorkdeskOutputSummarizer(llm, new ObjectMapper());
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
        ReflectionTestUtils.setField(summarizer, "egressGuard", guard());
        return summarizer;
    }

    static IntentExecuteResponse followup() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerName", "张记食品有限公司");
        customer.put("phone", "13812345678");
        customer.put("receivableAmount", 12800);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("_toolCount", 1);
        data.put("customers", List.of(customer));
        return IntentExecuteResponse.builder()
                .intentName("每日客户跟进")
                .formattedText("{\"_toolCount\":1,\"customers\":[{\"customerName\":\"张记食品有限公司\"}]}")
                .resultData(data)
                .build();
    }

    static ProcessSecretPolicy policy(ProcessSecretStorageMode mode) {
        return ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET).storageMode(mode)
                .ownerUserId(OWNER).createdBy(OWNER).build();
    }

    ExportExecutor exportExecutor() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretPolicy policy = policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, mock(ProcessSecretAuditRecorder.class));
        access.setFactorySecrecyConfig(config);
        return new ExportExecutor(new ObjectMapper(),
                ProcessSecretTestHooks.scrubber(access, mock(BomRecipeRepository.class),
                        mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class)),
                new ProcessSecretCostMask(access, mock(ProductionPlanRepository.class), mock(ProductionBatchRepository.class),
                        mock(BomRecipeRepository.class), mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                        mock(com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository.class)));
    }

    static Map<String, Object> exportRow(ExportExecutor executor, ExportRule rule, AuthenticatedActor actor) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productTypeId", SECRET);
        row.put("batchNumber", "B1");
        row.put("quantity", 100);
        row.put("totalCost", 900);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            return com.cretas.aims.service.datacenter.impl.SecrecyExportTestHooks.mask(executor, rule, List.of(row)).get(0);
        }
    }
}
