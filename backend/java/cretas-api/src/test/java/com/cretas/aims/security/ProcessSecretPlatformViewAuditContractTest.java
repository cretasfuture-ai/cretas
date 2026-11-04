package com.cretas.aims.security;

import com.cretas.aims.dto.orchestration.BomTreeNode;
import com.cretas.aims.dto.orchestration.BomTreeResult;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import com.cretas.aims.service.pot.PotProcessSecretRedactor;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审查跟进 (#1412): b 的说明承诺「每一次查看、编辑都记入保密访问记录」。三条只判不抛的完整查看读路径
 * (BOM 递归展开的子层半成品 / 锅次详情解除遮挡 / AI 成本上下文的完整查看集合) 原来只 decide 不写审计 ——
 * b 关时平台看了不留痕。现在平台账号的放行每次写一条 ALLOW VIEW_FULL; 负责人照旧不记; 写不进去按不放行。
 */
class ProcessSecretPlatformViewAuditContractTest {

    static final String F = "F-PV";
    static final String SECRET = "P-PV";
    static final long OWNER = 10L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretAuditRecorder recorder;
    FactorySecrecyConfig secrecy;
    ProcessSecretAccessService access;
    boolean platformRecipeBlock;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        recorder = mock(ProcessSecretAuditRecorder.class);
        access = new ProcessSecretAccessService(policies, grants, recorder);
        secrecy = mock(FactorySecrecyConfig.class);
        platformRecipeBlock = false;   // b 关
        when(secrecy.isOn(eq(F), any())).thenAnswer(inv ->
                inv.getArgument(1) != SecrecyItem.PLATFORM_RECIPE_BLOCK || platformRecipeBlock);
        access.setFactorySecrecyConfig(secrecy);
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        when(policies.existsByFactoryIdAndOwnerUserId(F, OWNER)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    }

    static AuthenticatedActor owner() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OWNER, F, "factory_super_admin");
    }

    /** 每次调用 = 一次新的 HTTP 请求 (同一请求里已记过的不重复记)。 */
    static void newRequestAs(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    void verifyAllowViewFull(int times, AuthenticatedActor actor) {
        verify(recorder, times(times)).record(eq(F), eq(SECRET), eq(actor), eq(ProcessSecretAction.VIEW_FULL),
                eq("ALLOW"), any(), any());
    }

    RecursiveBomExpansionService bomService() {
        BomRecipeItemRepository items = mock(BomRecipeItemRepository.class);
        com.cretas.aims.service.canvas.ThresholdResolverService thresholds =
                mock(com.cretas.aims.service.canvas.ThresholdResolverService.class);
        when(thresholds.getInteger(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(10);
        BomRecipeItem semi = new BomRecipeItem();
        semi.setSubProductTypeId(SECRET);
        semi.setMaterialName("秘制半成品");
        semi.setStandardQuantity(new BigDecimal("2"));
        BomRecipeItem spice = new BomRecipeItem();
        spice.setMaterialTypeId("SPICE-1");
        spice.setMaterialName("秘方香料");
        spice.setStandardQuantity(new BigDecimal("3.25"));
        when(items.findCurrentByProduct(F, "ROOT")).thenReturn(List.of(semi));
        when(items.findCurrentByProduct(F, SECRET)).thenReturn(List.of(spice));
        return new RecursiveBomExpansionService(items,
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.ProductTypeRepository.class),
                mock(com.cretas.aims.repository.RawMaterialTypeRepository.class), thresholds, access);
    }

    @Test
    void platformBomExpansionWithBOffAuditsTheProtectedChildEveryTime() {
        RecursiveBomExpansionService service = bomService();
        AuthenticatedActor platform = platform();

        newRequestAs(platform);
        BomTreeNode child = service.expandTree(F, "ROOT", BigDecimal.ONE).getRoot().getChildren().get(0);
        assertThat(child.isProcessSecretRestricted()).as("b 关: 平台看到完整子配方").isFalse();
        assertThat(child.getChildren()).extracting(BomTreeNode::getName).containsExactly("秘方香料");
        verifyAllowViewFull(1, platform);

        newRequestAs(platform);
        service.expandTree(F, "ROOT", BigDecimal.ONE);
        verifyAllowViewFull(2, platform);   // 每一次查看都记

        // 负责人照旧看完整, 不在这里刷审计
        newRequestAs(owner());
        BomTreeResult ownerView = service.expandTree(F, "ROOT", BigDecimal.ONE);
        assertThat(ownerView.getRoot().getChildren().get(0).isProcessSecretRestricted()).isFalse();
        verifyAllowViewFull(0, owner());

        // b 开: 平台看不到, 不写 ALLOW
        platformRecipeBlock = true;
        newRequestAs(platform);
        assertThat(service.expandTree(F, "ROOT", BigDecimal.ONE).getRoot().getChildren().get(0)
                .isProcessSecretRestricted()).isTrue();
        verifyAllowViewFull(2, platform);
    }

    @Test
    void anAuditThatCannotBeWrittenHidesTheChildFromThePlatform() {
        doThrow(new IllegalStateException("audit table down")).when(recorder)
                .record(any(), any(), any(), any(), any(), any(), any());
        newRequestAs(platform());
        assertThat(bomService().expandTree(F, "ROOT", BigDecimal.ONE).getRoot().getChildren().get(0)
                .isProcessSecretRestricted()).as("没留痕就不放出").isTrue();
    }

    @Test
    void platformPotBatchDetailWithBOffIsAudited() {
        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        ProductionBatch a = new ProductionBatch();
        a.setId(1L);
        a.setFactoryId(F);
        a.setProductTypeId(SECRET);
        ProductionBatch b = new ProductionBatch();
        b.setId(2L);
        b.setFactoryId(F);
        b.setProductTypeId(SECRET);
        when(batches.findAllById(any())).thenReturn(List.of(a, b));
        PotProcessSecretRedactor redactor = new PotProcessSecretRedactor(access, batches,
                mock(ProductionPlanRepository.class), mock(BomRecipeRepository.class));
        AuthenticatedActor platform = platform();

        newRequestAs(platform);
        assertThat(redactor.restrictedBatches(F, List.of(1L, 2L))).isEmpty();
        verifyAllowViewFull(1, platform);   // 同一产品一次调用记一条

        newRequestAs(owner());
        assertThat(redactor.restrictedBatches(F, List.of(1L, 2L))).isEmpty();
        verifyAllowViewFull(0, owner());
    }

    @Test
    void platformAiCostContextWithBOffIsAudited() {
        ProcessSecretLlmCostScope scope = new ProcessSecretLlmCostScope(mock(ProcessSecretAggregateExclusions.class), access);
        AuthenticatedActor platform = platform();

        newRequestAs(platform);
        assertThat(scope.forCurrentCaller(F).isEmpty()).as("b 关: 平台不隐藏").isTrue();
        verifyAllowViewFull(1, platform);

        newRequestAs(owner());
        assertThat(scope.forCurrentCaller(F).isEmpty()).isTrue();
        verifyAllowViewFull(0, owner());
        verify(recorder, never()).record(any(), any(), eq(owner()), any(), eq("DENY"), any(), any());
    }
}
