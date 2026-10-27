package com.cretas.aims.service.processsecret;

import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentAuditRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.bom.impl.BomPriceAdjustmentServiceImpl;
import com.cretas.aims.service.bom.impl.BomYieldEstimateServiceImpl;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.cretas.aims.service.yield.YieldReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工艺保密写入侧 (Steve 2026-10-27 第 3 条): 绕过配方 / 工序路由编辑闸的几条写入口。
 * 每条都经真实入口方法调用: 负责人照常写; 本厂无授权账号整单 403、一行不写、审计里有这道闸的 DENY。
 * 判定服务与配方守卫是真的, 只有仓库和审计写入是桩。
 */
class ProcessSecretWriteSliceGuardTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;
    static final long OPERATOR = 5L;

    ProcessSecretAuditRecorder recorder;
    BomRecipeRepository recipes;
    ProcessSecretRecipeGuard guard;

    @BeforeEach
    void setUpGuard() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        recipes = mock(BomRecipeRepository.class);
        when(recipes.findById("r-secret")).thenReturn(Optional.of(recipe("r-secret", SECRET)));
        guard = new ProcessSecretRecipeGuard(recipes, new ProcessSecretAccessService(policies, grants, recorder));
    }

    static BomRecipe recipe(String id, String product) {
        BomRecipe r = new BomRecipe();
        r.setId(id);
        r.setFactoryId(F);
        r.setProductTypeId(product);
        r.setIsCurrent(true);
        r.setStatus(BomRecipe.Status.ACTIVE);
        r.setOverallYieldRate(new BigDecimal("80.00"));
        return r;
    }

    static <T> T as(long userId, Supplier<T> call) {
        AuthenticatedActor actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, "operator");
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            return call.get();
        }
    }

    void assertDeniedByThisGate(Supplier<?> call, String resourceRefFragment) {
        assertThatThrownBy(() -> as(OPERATOR, call))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(recorder).record(eq(F), eq(SECRET), any(), eq(ProcessSecretAction.EDIT), eq("DENY"), anyString(),
                contains(resourceRefFragment));
    }

    @Nested
    @DisplayName("出成率批量应用 (/bom/yield-estimate/recalculate-apply)")
    class YieldBulkApply {
        BomRecipeRepository serviceRecipes;
        BomYieldEstimateServiceImpl service;

        @BeforeEach
        void setUp() {
            serviceRecipes = mock(BomRecipeRepository.class);
            when(serviceRecipes.findById("r-secret")).thenReturn(Optional.of(recipe("r-secret", SECRET)));
            when(serviceRecipes.save(any())).thenAnswer(inv -> inv.getArgument(0));
            BomChangeLogRepository changeLogs = mock(BomChangeLogRepository.class);
            when(changeLogs.save(any())).thenAnswer(inv -> {
                BomChangeLog log = inv.getArgument(0);
                log.setId("log-1");
                return log;
            });
            service = new BomYieldEstimateServiceImpl(mock(ProductionBatchRepository.class),
                    mock(ProductTypeRepository.class), serviceRecipes, mock(BomRecipeItemRepository.class),
                    changeLogs, mock(YieldReportService.class));
            ReflectionTestUtils.setField(service, "processSecretGuard", guard);
        }

        List<BomYieldApplyRequest> request() {
            BomYieldApplyRequest r = new BomYieldApplyRequest();
            r.setRecipeId("r-secret");
            r.setYieldRate(new BigDecimal("85"));
            r.setExpectedCurrentYieldRate(new BigDecimal("80.00"));
            return List.of(r);
        }

        @Test
        void operatorWithoutGrantCannotRewriteTheStandardYield() {
            assertDeniedByThisGate(() -> service.recalculateApply(F, request()), "bom yield recalculate-apply");
            verify(serviceRecipes, never()).save(any());
        }

        @Test
        void ownerAppliesAsBefore() {
            assertThat(as(OWNER, () -> service.recalculateApply(F, request())).getApplied()).isEqualTo(1);
            verify(serviceRecipes).save(any());
        }
    }

    @Nested
    @DisplayName("BOM 调价审批 (/bom/price-adjustments/{id}/approve)")
    class PriceAdjustmentApproval {
        BomRecipeItemRepository items;
        BomPriceAdjustmentServiceImpl service;

        @BeforeEach
        void setUp() {
            BomPriceAdjustmentProposal proposal = new BomPriceAdjustmentProposal();
            proposal.setId(31L);
            proposal.setFactoryId(F);
            proposal.setRecipeItemId(7L);
            proposal.setStatus(BomPriceAdjustmentProposal.Status.PENDING);
            BomPriceAdjustmentProposalRepository proposals = mock(BomPriceAdjustmentProposalRepository.class);
            when(proposals.findByIdAndFactoryId(31L, F)).thenReturn(Optional.of(proposal));
            when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));
            BomRecipeItem item = new BomRecipeItem();
            item.setId(7L);
            item.setFactoryId(F);
            item.setRecipeId("r-secret");
            item.setMaterialTypeId("M1");
            item.setStandardQuantity(new BigDecimal("0.3"));
            item.setUnitPrice(new BigDecimal("10"));
            items = mock(BomRecipeItemRepository.class);
            when(items.findByIdForUpdate(7L)).thenReturn(Optional.of(item));
            when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
            RawMaterialType material = new RawMaterialType();
            material.setId("M1");
            material.setFactoryId(F);
            material.setUnitPrice(new BigDecimal("12"));
            RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
            when(materials.findById("M1")).thenReturn(Optional.of(material));
            BomPriceAdjustmentAuditRepository audits = mock(BomPriceAdjustmentAuditRepository.class);
            when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));
            service = new BomPriceAdjustmentServiceImpl(items, mock(BomRecipeRepository.class), proposals, audits,
                    materials);
            ReflectionTestUtils.setField(service, "processSecretGuard", guard);
        }

        @Test
        void operatorWithoutGrantCannotApproveARecipeLinePriceChange() {
            assertDeniedByThisGate(() -> service.approve(F, 31L, OPERATOR, "ok"), "bom price-adjustment approve 31");
            verify(items, never()).save(any());
        }

        @Test
        void ownerApprovesAsBefore() {
            as(OWNER, () -> service.approve(F, 31L, OWNER, "ok"));
            verify(items).save(any());
        }
    }

    @Nested
    @DisplayName("产品类型的工艺参数 (PUT /product-types/{id}, /config)")
    class ProductTypeProcessFields {
        ProductTypeRepository products;
        ProductTypeServiceImpl service;

        @BeforeEach
        void setUp() {
            ProductType existing = new ProductType();
            existing.setId(SECRET);
            existing.setFactoryId(F);
            existing.setCode("P17-CODE");
            existing.setName("酱牛肉");
            existing.setUnit("袋");
            existing.setCreatedBy(1L);
            existing.setWipToFgYield(new BigDecimal("0.9000"));
            existing.setProcessingSteps("[{\"stageType\": \"MIX\", \"orderIndex\": 1}]");
            products = mock(ProductTypeRepository.class);
            when(products.findById(SECRET)).thenReturn(Optional.of(existing));
            when(products.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));
            service = new ProductTypeServiceImpl(products, new ObjectMapper(), mock(CustomerRepository.class),
                    mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class),
                    mock(com.cretas.aims.service.unit.ProductSpecificationConversionSyncService.class));
            ReflectionTestUtils.setField(service, "processSecretGuard", guard);
        }

        @Test
        void operatorWithoutGrantCannotChangeTheWipToFinishedYield() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setWipToFgYield(new BigDecimal("0.95"));
            assertDeniedByThisGate(() -> service.updateProductType(F, SECRET, dto), "product-type update P17");
            verify(products, never()).save(any(ProductType.class));
        }

        @Test
        void operatorWithoutGrantCannotChangeTheProcessingSteps() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setProcessingSteps(List.of(ProductTypeDTO.ProcessingStepDTO.builder().stageType("FRY").orderIndex(1)
                    .build()));
            assertDeniedByThisGate(() -> service.updateProductType(F, SECRET, dto), "product-type update P17");
            verify(products, never()).save(any(ProductType.class));
        }

        @Test
        void nonProcessFieldsAndAnUnchangedConfigRoundTripStayOpenToEveryone() {
            ProductTypeDTO rename = new ProductTypeDTO();
            rename.setNotes("改备注");
            as(OPERATOR, () -> service.updateProductType(F, SECRET, rename));
            // /config 把现有加工步骤原样回写: 库里 jsonb 带空格, 重新序列化不带 —— 内容相同就不算改
            ProductTypeDTO sameSteps = new ProductTypeDTO();
            sameSteps.setWipToFgYield(new BigDecimal("0.90"));
            sameSteps.setProcessingSteps(List.of(ProductTypeDTO.ProcessingStepDTO.builder().stageType("MIX")
                    .orderIndex(1).build()));
            as(OPERATOR, () -> service.updateProductType(F, SECRET, sameSteps));
            verify(products, org.mockito.Mockito.times(2)).save(any(ProductType.class));
            verify(recorder, never()).record(any(), any(), any(), any(), eq("DENY"), any(), any());
        }

        @Test
        void ownerChangesProcessFieldsAsBefore() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setWipToFgYield(new BigDecimal("0.95"));
            as(OWNER, () -> service.updateProductType(F, SECRET, dto));
            verify(products).save(any(ProductType.class));
        }
    }

    @Nested
    @DisplayName("对话建厂落库 (覆盖转换率 / 替换工序链)")
    class FactoryBuildApply {
        FactoryBuildApplyService service;

        @BeforeEach
        void setUp() {
            ProductTypeRepository products = mock(ProductTypeRepository.class);
            ProductType secret = new ProductType();
            secret.setId(SECRET);
            secret.setFactoryId(F);
            when(products.findByFactoryIdAndCode(F, "P17-CODE")).thenReturn(Optional.of(secret));
            when(products.findByFactoryIdAndCode(F, "NEW-CODE")).thenReturn(Optional.empty());
            service = new FactoryBuildApplyService();
            ReflectionTestUtils.setField(service, "productTypeRepository", products);
            ReflectionTestUtils.setField(service, "processSecretGuard", guard);
        }

        ApplyAIInitRequest request(String conversionTo, String chainProduct) {
            ApplyAIInitRequest request = new ApplyAIInitRequest();
            ApplyAIInitRequest.SuggestedData data = new ApplyAIInitRequest.SuggestedData();
            if (conversionTo != null) {
                ApplyAIInitRequest.ConversionItem c = new ApplyAIInitRequest.ConversionItem();
                c.setFromCode("RM-1");
                c.setToCode(conversionTo);
                c.setRate(new BigDecimal("0.5"));
                data.setConversionRates(List.of(c));
            }
            if (chainProduct != null) {
                ApplyAIInitRequest.ProductProcessItem pp = new ApplyAIInitRequest.ProductProcessItem();
                pp.setProductCode(chainProduct);
                pp.setProcessNames(List.of("拌料"));
                data.setProductProcesses(List.of(pp));
            }
            request.setSuggestedData(data);
            return request;
        }

        void check(long user, ApplyAIInitRequest request, boolean overwrite) {
            as(user, () -> {
                ReflectionTestUtils.invokeMethod(service, "requireProcessSecretEdit", F, request, overwrite);
                return null;
            });
        }

        @Test
        void overwritingAProtectedProductsConversionRateNeedsEditPermission() {
            assertDeniedByThisGate(() -> {
                ReflectionTestUtils.invokeMethod(service, "requireProcessSecretEdit", F, request("P17-CODE", null), true);
                return null;
            }, "conversion overwrite P17-CODE");
        }

        @Test
        void replacingAProtectedProductsProcessChainNeedsEditPermissionEvenWithoutOverwrite() {
            assertDeniedByThisGate(() -> {
                ReflectionTestUtils.invokeMethod(service, "requireProcessSecretEdit", F, request(null, "P17-CODE"), false);
                return null;
            }, "process chain replace P17-CODE");
        }

        @Test
        void skippedExistingPairsNewProductsAndTheOwnerAreNotStopped() {
            check(OPERATOR, request("P17-CODE", null), false);   // 不覆盖: 已有的对会跳过, 不写
            check(OPERATOR, request("NEW-CODE", "NEW-CODE"), true);   // 本次新建的产品, 还没有保密策略
            check(OWNER, request("P17-CODE", "P17-CODE"), true);
            verify(recorder, never()).record(any(), any(), any(), any(), eq("DENY"), any(), any());
        }
    }
}
