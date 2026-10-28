package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 「发给大模型的成本数据按调用者裁剪」各出口测试共用的数据: 一个保密产品 + 两个普通产品,
 * 成本数字各不相同且一眼可辨 (保密 7777.77, 普通 1234.56 / 4321.00), 断言直接在发给模型的字符串上找。
 *
 * <p>判定走真实的 {@link ProcessSecretAccessService#decide} → {@link ProcessSecretLlmCostScope}
 * → {@link ProcessSecretAggregateExclusions#forHidden}, 只桩掉策略 / 授权 / 计划 / 配方几张表。
 * 保密负责人是 {@link #OWNER}, {@link #GRANTEE} 持有效的完整查看授权; 调用者用 {@link #as} 放进受信异步作用域,
 * 不放 = 认不出身份 (与没有用户的定时任务一样, 按无权处理)。</p>
 */
public final class ProcessSecretLlmFixture {

    public static final String FACTORY = "F-PS";
    public static final String OTHER_FACTORY = "F-OTHER";
    public static final String SECRET = "P-SECRET";
    public static final String NORMAL = "P-NORMAL";
    public static final String NORMAL2 = "P-NORMAL2";
    public static final String SECRET_PLAN = "PL-SECRET";
    public static final String NORMAL_PLAN = "PL-NORMAL";
    public static final String SECRET_NAME = "保密卤味";
    public static final long OWNER = 501L;
    public static final long OTHER_USER = 502L;
    public static final long GRANTEE = 503L;

    /** 保密产品的成本: 无权调用者的提示词里不许出现, 有权调用者的提示词里必须出现。 */
    public static final BigDecimal SECRET_COST = new BigDecimal("7777.77");
    public static final BigDecimal NORMAL_COST = new BigDecimal("1234.56");
    public static final BigDecimal NORMAL2_COST = new BigDecimal("4321.00");

    private ProcessSecretLlmFixture() {
    }

    private static <T> T lenientMock(Class<T> type) {
        return mock(type, withSettings().strictness(Strictness.LENIENT));
    }

    // ---------------- 调用者 ----------------

    public static AuthenticatedActor owner() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OWNER, FACTORY, "factory_super_admin");
    }

    public static AuthenticatedActor grantee() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, GRANTEE, FACTORY, "finance_manager");
    }

    /** 同厂、无授权 (头衔再高也不因头衔放行)。 */
    public static AuthenticatedActor sameFactoryUser() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OTHER_USER, FACTORY, "factory_super_admin");
    }

    public static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "platform_admin");
    }

    public static AuthenticatedActor otherFactoryUser() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 777L, OTHER_FACTORY, "factory_super_admin");
    }

    /** 把调用者放进受信异步作用域 (try-with-resources)。 */
    public static AuthenticatedActor.ActorScope as(AuthenticatedActor actor) {
        return AuthenticatedActor.trustedAsyncScope(actor);
    }

    // ---------------- 保密范围 ----------------

    /** 真实的判定链; {@code protectedIds} 为空 = 本厂没有保密产品 (阴性对照)。 */
    public static ProcessSecretLlmCostScope scope(Set<String> protectedIds) {
        return scope(protectedIds, plans());
    }

    public static ProcessSecretLlmCostScope scope(Set<String> protectedIds, List<ProductionPlan> plans) {
        ProcessSecretAccessService access = access(protectedIds);
        return new ProcessSecretLlmCostScope(exclusions(access, plans), access);
    }

    public static ProcessSecretAccessService access(Set<String> protectedIds) {
        ProcessSecretPolicyRepository policies = lenientMock(ProcessSecretPolicyRepository.class);
        List<ProcessSecretPolicy> list = protectedIds.stream().sorted().map(ProcessSecretLlmFixture::policy).toList();
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(eq(FACTORY))).thenReturn(list);
        when(policies.findByFactoryIdAndProductTypeId(eq(FACTORY), anyString())).thenAnswer(inv ->
                protectedIds.contains(inv.<String>getArgument(1))
                        ? Optional.of(policy(inv.getArgument(1))) : Optional.empty());
        ProcessSecretGrantRepository grants = lenientMock(ProcessSecretGrantRepository.class);
        when(grants.findActive(eq(FACTORY), anyString(), eq(ProcessSecretGranteeType.FACTORY_USER), eq(GRANTEE), any()))
                .thenAnswer(inv -> List.of(ProcessSecretGrant.builder().factoryId(FACTORY)
                        .productTypeId(inv.getArgument(1)).granteeType(ProcessSecretGranteeType.FACTORY_USER)
                        .granteeId(GRANTEE).canViewFull(true).canExport(false).canEdit(false).purpose("test")
                        .expiresAt(LocalDateTime.now().plusDays(1)).grantedBy(OWNER).build()));
        return new ProcessSecretAccessService(policies, grants, lenientMock(ProcessSecretAuditRecorder.class));
    }

    private static ProcessSecretAggregateExclusions exclusions(ProcessSecretAccessService access, List<ProductionPlan> plans) {
        ProductionPlanRepository planRepository = lenientMock(ProductionPlanRepository.class);
        when(planRepository.findByFactoryId(anyString())).thenReturn(plans);
        BomRecipeRepository recipes = lenientMock(BomRecipeRepository.class);
        when(recipes.findAllById(any())).thenReturn(List.of());
        return new ProcessSecretAggregateExclusions(access, planRepository, recipes);
    }

    private static ProcessSecretPolicy policy(String productTypeId) {
        return ProcessSecretPolicy.builder().id(1L).factoryId(FACTORY).productTypeId(productTypeId)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
    }

    public static ProcessSecretLlmCostScope protectedScope() {
        return scope(Set.of(SECRET));
    }

    public static ProcessSecretLlmCostScope unprotectedScope() {
        return scope(Set.of());
    }

    // ---------------- 数据 ----------------

    public static List<ProductionPlan> plans() {
        return List.of(plan(SECRET_PLAN, SECRET, null), plan(NORMAL_PLAN, NORMAL, null));
    }

    public static ProductionPlan plan(String id, String productTypeId, LocalDateTime endTime) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY);
        plan.setProductTypeId(productTypeId);
        plan.setEndTime(endTime);
        return plan;
    }

    public static ProductionBatch batch(long id, String productTypeId, String planId, BigDecimal totalCost,
                                        BigDecimal quantity) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("B-" + id);
        batch.setProductTypeId(productTypeId);
        batch.setProductionPlanId(planId);
        batch.setProductName(SECRET.equals(productTypeId) ? SECRET_NAME : "普通" + id);
        batch.setTotalCost(totalCost);
        batch.setMaterialCost(totalCost.multiply(new BigDecimal("0.6")));
        batch.setLaborCost(totalCost.multiply(new BigDecimal("0.3")));
        batch.setEquipmentCost(totalCost.multiply(new BigDecimal("0.1")));
        batch.setActualQuantity(quantity);
        batch.setCreatedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        return batch;
    }

    /** 与 ProcessingServiceImpl.getWeeklyBatchesCost 同形的批次成本摘要行 (含 productTypeId / productionPlanId)。 */
    public static Map<String, Object> weeklyRow(long batchId, String productTypeId, String planId, BigDecimal totalCost) {
        Map<String, Object> row = new HashMap<>();
        row.put("batchId", batchId);
        row.put("batchNumber", "B-" + batchId);
        row.put("productName", SECRET.equals(productTypeId) ? SECRET_NAME : "普通" + batchId);
        row.put("productTypeId", productTypeId);
        row.put("productionPlanId", planId);
        row.put("status", "COMPLETED");
        row.put("createdAt", LocalDateTime.of(2026, 10, 20, 9, 0));
        Map<String, Object> costSummary = new HashMap<>();
        costSummary.put("totalCost", totalCost);
        costSummary.put("unitCost", totalCost.divide(new BigDecimal("100")));
        row.put("costSummary", costSummary);
        row.put("actualQuantity", new BigDecimal("100"));
        row.put("yieldRate", new BigDecimal("95"));
        return row;
    }

    /** 一保密两普通的批次成本摘要行。 */
    public static List<Map<String, Object>> weeklyRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(weeklyRow(1L, SECRET, SECRET_PLAN, SECRET_COST));
        rows.add(weeklyRow(2L, NORMAL, NORMAL_PLAN, NORMAL_COST));
        rows.add(weeklyRow(3L, NORMAL2, null, NORMAL2_COST));
        return rows;
    }

    /** 测试里逐行算出的「无权调用者能看的行合计」: 不是「总数 − 保密」。 */
    @SuppressWarnings("unchecked")
    public static BigDecimal sumOfNonProtectedRows(List<Map<String, Object>> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            if (SECRET.equals(row.get("productTypeId")) || SECRET_PLAN.equals(row.get("productionPlanId"))) continue;
            sum = sum.add((BigDecimal) ((Map<String, Object>) row.get("costSummary")).get("totalCost"));
        }
        return sum;
    }
}
