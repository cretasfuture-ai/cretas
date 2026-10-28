package com.cretas.aims.service.datacenter.impl;

import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则导出 (任意实体) 走与 JSON 出口相同的工艺保密裁剪 (Steve 2026-10-28 选 A: 成本合计也隐藏;
 * 整表导出按跨批汇总处理), 没有产品归属的成本行也遮, 筛选条件只能看到遮过的值。
 */
class ExportExecutorProcessSecretTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    ExportExecutor executor;
    ProductionPlanRepository plans;
    ProductionBatchRepository batches;
    com.cretas.aims.repository.MaterialBatchRepository materials;
    ExportRule rule;

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        plans = mock(ProductionPlanRepository.class);
        batches = mock(ProductionBatchRepository.class);
        materials = mock(com.cretas.aims.repository.MaterialBatchRepository.class);
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants,
                mock(ProcessSecretAuditRecorder.class));
        executor = new ExportExecutor(new ObjectMapper(),
                ProcessSecretTestHooks.scrubber(access, mock(BomRecipeRepository.class),
                        mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class)),
                new ProcessSecretCostMask(access, plans,
                        batches, mock(BomRecipeRepository.class), materials, mock(com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository.class)));
        rule = new ExportRule();
        rule.setId(7L);
        rule.setFactoryId(F);
        rule.setTargetEntity("ProductionBatch");
    }

    static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) row.put((String) keyValues[i], keyValues[i + 1]);
        return row;
    }

    static List<Map<String, Object>> batches() {
        return List.of(
                row("productTypeId", SECRET, "batchNumber", "B1", "quantity", 100, "materialCost", 812,
                        "totalCost", 900, "unitCost", 9),
                row("productTypeId", "PLAIN", "batchNumber", "B2", "quantity", 50, "totalCost", 800));
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    @Test
    void protectedBatchCostsAndCrossBatchQuantitiesAreWrittenAsSecretForAnAccountWithoutAGrant() {
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, batches());
        }
        assertThat(rows.get(0)).containsEntry("totalCost", "已保密").containsEntry("materialCost", "已保密")
                .containsEntry("unitCost", "已保密").doesNotContainKey("processSecret");
        // 整表导出 = 跨批汇总 (单批可看、汇总不给): 保密产品各批数量也不给
        assertThat(rows.get(0)).containsEntry("quantity", "已保密");
        // 阴性对照: 同一份导出里的普通产品照常
        assertThat(rows.get(1)).containsEntry("totalCost", 800).containsEntry("quantity", 50);
    }

    @Test
    void theOwnerExportsTheRealCosts() {
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            rows = executor.maskProcessSecret(rule, batches());
        }
        assertThat(rows.get(0)).containsEntry("totalCost", 900).containsEntry("unitCost", 9)
                .containsEntry("quantity", 100);
    }

    @Test
    void aScheduledExportWithNoCallerGetsNeitherCostsNorQuantitiesOfAProtectedProduct() {
        List<Map<String, Object>> rows = executor.maskProcessSecret(rule, batches());
        assertThat(rows.get(0)).containsEntry("totalCost", "已保密").containsEntry("quantity", "已保密");
        assertThat(rows.get(1)).containsEntry("totalCost", 800);
    }

    @Test
    void costRowsWithoutAProductAreMaskedWhileTheFactoryHasAProtectedProductTheCallerCannotSee() {
        // 半成品流水 / 返工记录这类实体没有产品字段, 认不出属于谁: 宁可多遮
        List<Map<String, Object>> txns = List.of(row("semiFinishedId", 3, "quantity", 5, "unitCostAtTxn", 16.2,
                "balanceCostAfter", 81));
        List<Map<String, Object>> operator;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            operator = executor.maskProcessSecret(rule, txns);
        }
        assertThat(operator.get(0)).containsEntry("unitCostAtTxn", "已保密").containsEntry("balanceCostAfter", "已保密")
                .containsEntry("quantity", 5).containsEntry("semiFinishedId", 3);
        // 阴性对照: 负责人照常
        List<Map<String, Object>> owner;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            owner = executor.maskProcessSecret(rule, txns);
        }
        assertThat(owner.get(0)).containsEntry("unitCostAtTxn", 16.2);
    }

    @Test
    void theRuleFilterOnlySeesMaskedValuesSoItCannotProbeAHiddenCost() throws Exception {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(batches());
        java.lang.reflect.Field field = ExportExecutor.class.getDeclaredField("em");
        field.setAccessible(true);
        field.set(executor, em);
        rule.setFilterExpression("#row['unitCost'] > 1");

        List<Map<String, Object>> probe;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            probe = executor.queryAndFilter(rule, Map.of());
        }
        // 真实 unitCost = 9 > 1, 但筛选只能看到「已保密」: 保密行不会因为成本大于阈值被选中
        assertThat(probe).isEmpty();

        List<Map<String, Object>> ownerRows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            ownerRows = executor.queryAndFilter(rule, Map.of());
        }
        assertThat(ownerRows).hasSize(1).first().satisfies(r -> assertThat(r).containsEntry("batchNumber", "B1"));
    }

    @Test
    void recipeDerivedConsumptionRowsAreMarkedAutomatic() throws Exception {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                row("productTypeId", SECRET, "sourceType", "SEASONING", "quantity", 0.1725),
                row("productTypeId", SECRET, "sourceType", "RAW_MATERIAL", "quantity", 115)));
        java.lang.reflect.Field field = ExportExecutor.class.getDeclaredField("em");
        field.setAccessible(true);
        field.set(executor, em);
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            rows = executor.queryAndFilter(rule, Map.of());
        }
        assertThat(rows.get(0)).containsEntry("automatic", true);
        assertThat(rows.get(1)).doesNotContainKey("automatic");
    }
    @Test
    void rowsLinkedToAProtectedPlanAreMaskedEvenWhenTheirOwnProductIsPlain() {
        // 独立审查 N-M1 / N-M2: 受限计划的副产、跨计划领用的半成品, 单价就是滚进配方金额的单位成本
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId("PLAN-9");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("PLAN-9", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.ProductionPlan plainPlan = new com.cretas.aims.entity.ProductionPlan();
        plainPlan.setId("PLAN-0");
        plainPlan.setFactoryId(F);
        plainPlan.setProductTypeId("PLAIN");
        when(plans.findByIdAndFactoryId("PLAN-0", F)).thenReturn(Optional.of(plainPlan));
        List<Map<String, Object>> input = List.of(
                row("productTypeId", "PLAIN", "productionPlanId", "PLAN-9", "unitCost", 12, "quantity", 3),
                row("productTypeId", "PLAIN", "productionPlanId", "PLAN-0", "unitCost", 8, "quantity", 4));
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, input);
        }
        assertThat(rows.get(0)).containsEntry("unitCost", "已保密").containsEntry("quantity", 3);
        // 阴性对照: 普通计划的普通产品照常
        assertThat(rows.get(1)).containsEntry("unitCost", 8);
    }

    @Test
    void aPurchaseMaterialBatchKeepsItsPriceButAWipBatchOfAProtectedPlanDoesNot() {
        com.cretas.aims.entity.ProductionBatch source = new com.cretas.aims.entity.ProductionBatch();
        source.setProductTypeId(SECRET);
        when(batches.findByIdAndFactoryId(77L, F)).thenReturn(Optional.of(source));
        List<Map<String, Object>> input = List.of(
                row("batchNumber", "MB-P", "sourceDocType", "PURCHASE_ORDER", "sourceDocId", "PO-1", "unitPrice", 7),
                row("batchNumber", "MB-W", "sourceDocType", "PRODUCTION_BATCH", "sourceDocId", "77", "unitPrice", 16.2));
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, input);
        }
        assertThat(rows.get(0)).as("采购价不是秘密").containsEntry("unitPrice", 7);
        assertThat(rows.get(1)).containsEntry("unitPrice", "已保密");
    }

    @Test
    void aWipBatchWhoseSourceCannotBeIdentifiedIsMasked() {
        List<Map<String, Object>> input = List.of(
                row("batchNumber", "MB-X", "sourceDocType", "PRODUCTION_BATCH", "sourceDocId", "not-a-number",
                        "unitPrice", 16.2));
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, input);
        }
        assertThat(rows.get(0)).containsEntry("unitPrice", "已保密");
    }

    @Test
    void materialBatchTotalsOfAProtectedPlansWipBatchAreMaskedButAPurchasesAreNot() {
        // 终审 H1: totalPrice / totalValue = 单价 × 入库量
        com.cretas.aims.entity.ProductionBatch source = new com.cretas.aims.entity.ProductionBatch();
        source.setProductTypeId(SECRET);
        when(batches.findByIdAndFactoryId(77L, F)).thenReturn(Optional.of(source));
        List<Map<String, Object>> input = List.of(
                row("batchNumber", "MB-W", "sourceDocType", "PRODUCTION_BATCH", "sourceDocId", "77",
                        "unitPrice", 16.2, "totalPrice", 810, "totalValue", 810, "receiptQuantity", 50),
                row("batchNumber", "MB-P", "sourceDocType", "PURCHASE_ORDER", "sourceDocId", "PO-1",
                        "unitPrice", 7, "totalPrice", 700, "totalValue", 700, "receiptQuantity", 100));
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, input);
        }
        assertThat(rows.get(0)).containsEntry("totalPrice", "已保密").containsEntry("totalValue", "已保密")
                .containsEntry("receiptQuantity", 50);
        assertThat(rows.get(1)).containsEntry("totalPrice", 700).containsEntry("totalValue", 700);
    }

    @Test
    void aNumericBatchIdIsTheProductionBatchAndAConsumptionOfADeletedPlanIsMasked() {
        // 终审 M2: 半成品库存的 batchId 是数字的生产批次 id; M1: 计划查不到 (已软删) 按认不出归属处理
        com.cretas.aims.entity.ProductionBatch source = new com.cretas.aims.entity.ProductionBatch();
        source.setProductTypeId(SECRET);
        when(batches.findByIdAndFactoryId(77L, F)).thenReturn(Optional.of(source));
        List<Map<String, Object>> input = List.of(
                row("productTypeId", "WIP-SKU", "batchId", 77, "unitCost", 16.2),
                row("productionPlanId", "PLAN-GONE", "unitPrice", 7, "totalCost", 70),
                row("productTypeId", "PLAIN", "batchId", 88, "unitCost", 8));
        when(batches.findByIdAndFactoryId(88L, F)).thenReturn(Optional.of(new com.cretas.aims.entity.ProductionBatch()));
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, input);
        }
        assertThat(rows.get(0)).containsEntry("unitCost", "已保密");
        assertThat(rows.get(1)).containsEntry("unitPrice", "已保密").containsEntry("totalCost", "已保密");
        assertThat(rows.get(2)).as("普通批次照常").containsEntry("unitCost", 8);
    }
}
