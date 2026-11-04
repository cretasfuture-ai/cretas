package com.cretas.aims.service.datacenter.impl;

import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阴性对照 (导出): 两家工厂每一项保密配置都关着, 规则导出仍然只按规则所属工厂查 —— B 厂的规则读不到 A 厂的行。
 */
class ExportExecutorSecrecyNegativeContractTest {

    @Test
    void anExportRuleOnlyEverQueriesItsOwnFactoryWithEveryItemOff() {
        List<FactorySecrecySetting> rows = new ArrayList<>();
        for (String factory : new String[]{"F-EX-A", "F-EX-B"}) {
            for (SecrecyItem item : SecrecyItem.values()) {
                rows.add(FactorySecrecySetting.builder().factoryId(factory).itemCode(item.name()).enabled(false)
                        .updatedBy(1L).build());
            }
        }
        FactorySecrecySettingRepository repository = mock(FactorySecrecySettingRepository.class);
        when(repository.findByFactoryId(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getFactoryId().equals(inv.getArgument(0))).toList());
        FactorySecrecyConfig config = new FactorySecrecyConfig(repository,
                (org.springframework.transaction.PlatformTransactionManager) null);
        ProcessSecretAccessService access = new ProcessSecretAccessService(mock(ProcessSecretPolicyRepository.class),
                mock(ProcessSecretGrantRepository.class), mock(ProcessSecretAuditRecorder.class));
        access.setFactorySecrecyConfig(config);
        assertThat(config.isOn("F-EX-B", SecrecyItem.PROCESS_SECRET)).isFalse();

        ExportExecutor executor = new ExportExecutor(new ObjectMapper(),
                ProcessSecretTestHooks.scrubber(access, mock(BomRecipeRepository.class),
                        mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class)),
                new ProcessSecretCostMask(access, mock(ProductionPlanRepository.class), mock(ProductionBatchRepository.class),
                        mock(BomRecipeRepository.class), mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                        mock(com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository.class)));
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createQuery(anyString())).thenReturn(query);
        when(query.setParameter("fid", "F-EX-B")).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        ReflectionTestUtils.setField(executor, "em", em);

        ExportRule rule = new ExportRule();
        rule.setId(9L);
        rule.setFactoryId("F-EX-B");
        rule.setTargetEntity("ProductionBatch");
        List<Map<String, Object>> out = executor.queryAndFilter(rule, Map.of("factoryId", "F-EX-A"));

        assertThat(out).isEmpty();
        verify(em).createQuery("SELECT e FROM ProductionBatch e WHERE e.factoryId = :fid");
        verify(query).setParameter("fid", "F-EX-B");   // 运行时参数里塞 A 也改不了
    }
}
