package com.cretas.aims.service.datacenter.impl;

import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 规则导出 (任意实体) 走与 JSON 出口相同的工艺保密裁剪 (Steve 2026-10-28 选 A: 成本合计也隐藏)。 */
class ExportExecutorProcessSecretTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    ExportExecutor executor;
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
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants,
                mock(ProcessSecretAuditRecorder.class));
        executor = new ExportExecutor(new ObjectMapper(), ProcessSecretTestHooks.scrubber(access,
                mock(BomRecipeRepository.class), mock(ProductionWorkflowInstanceRepository.class),
                mock(ProductionPlanRepository.class)));
        rule = new ExportRule();
        rule.setId(7L);
        rule.setFactoryId(F);
    }

    static List<Map<String, Object>> batches() {
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("productTypeId", SECRET);
        secret.put("batchNumber", "B1");
        secret.put("quantity", 100);
        secret.put("materialCost", 812);
        secret.put("totalCost", 900);
        secret.put("unitCost", 9);
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("productTypeId", "PLAIN");
        plain.put("batchNumber", "B2");
        plain.put("quantity", 50);
        plain.put("totalCost", 800);
        return List.of(secret, plain);
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    @Test
    void protectedBatchCostsAreWrittenAsSecretForAnAccountWithoutAGrant() {
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            rows = executor.maskProcessSecret(rule, batches());
        }
        assertThat(rows.get(0)).containsEntry("totalCost", "已保密").containsEntry("materialCost", "已保密")
                .containsEntry("unitCost", "已保密").containsEntry("quantity", 100)
                .doesNotContainKey("processSecret");
        // 阴性对照: 同一份导出里的普通产品照常
        assertThat(rows.get(1)).containsEntry("totalCost", 800);
    }

    @Test
    void theOwnerExportsTheRealCosts() {
        List<Map<String, Object>> rows;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            rows = executor.maskProcessSecret(rule, batches());
        }
        assertThat(rows.get(0)).containsEntry("totalCost", 900).containsEntry("unitCost", 9);
    }

    @Test
    void aScheduledExportWithNoCallerGetsNeitherCostsNorQuantitiesOfAProtectedProduct() {
        List<Map<String, Object>> rows = executor.maskProcessSecret(rule, batches());
        assertThat(rows.get(0)).containsEntry("totalCost", "已保密").containsEntry("quantity", "已保密");
        assertThat(rows.get(1)).containsEntry("totalCost", 800);
    }
}
