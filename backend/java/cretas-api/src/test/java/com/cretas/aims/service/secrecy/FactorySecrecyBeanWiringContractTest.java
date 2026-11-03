package com.cretas.aims.service.secrecy;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.controller.FeatureConfigController;
import com.cretas.aims.controller.ProcessSecretController;
import com.cretas.aims.controller.platform.PlatformFactorySecrecyController;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalResultRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingAuditRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.execution.WorkdeskOutputSummarizer;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretExternalService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretPolicyService;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 新 bean 在 Spring 容器里起得来 (一次漏了构造器让 prod 起不来), 且每个接线点拿到的是容器里那一个
 * {@link FactorySecrecyConfig} —— 不是单测默认的「全开」实例 (setter 是 required=false, 漏注入不会报错, 只能在这里抓)。
 */
class FactorySecrecyBeanWiringContractTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FactorySecrecySettingRepository.class, () -> mock(FactorySecrecySettingRepository.class))
            .withBean(FactorySecrecySettingAuditRepository.class, () -> mock(FactorySecrecySettingAuditRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ProcessSecretPolicyRepository.class, () -> mock(ProcessSecretPolicyRepository.class))
            .withBean(ProcessSecretGrantRepository.class, () -> mock(ProcessSecretGrantRepository.class))
            .withBean(ProcessSecretAuditRecorder.class, () -> mock(ProcessSecretAuditRecorder.class))
            .withBean(ProductTypeRepository.class, () -> mock(ProductTypeRepository.class))
            // #1377: 训练名单 (保密产品 / 别名 / 人员 / 客户) 的原生查询
            .withBean(com.cretas.aims.repository.learning.FactoryNameDirectoryRepository.class,
                    () -> mock(com.cretas.aims.repository.learning.FactoryNameDirectoryRepository.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ProcessSecretLlmCostScope.class, ProcessSecretLlmCostScope::standalone)
            .withBean(ProcessSecretExternalPackageRepository.class, () -> mock(ProcessSecretExternalPackageRepository.class))
            .withBean(ProcessSecretExternalResultRepository.class, () -> mock(ProcessSecretExternalResultRepository.class))
            .withBean(ProductionBatchRepository.class, () -> mock(ProductionBatchRepository.class))
            .withBean(BomRecipeRepository.class, () -> mock(BomRecipeRepository.class))
            .withBean(ProcessSecretPolicyService.class, () -> mock(ProcessSecretPolicyService.class))
            .withBean(UnitContractService.class, () -> mock(UnitContractService.class))
            .withBean(FactoryRepository.class, () -> mock(FactoryRepository.class))
            .withBean(PlatformAdminRepository.class, () -> mock(PlatformAdminRepository.class))
            .withBean(FactoryFeatureMenuWriteLock.class, () -> mock(FactoryFeatureMenuWriteLock.class))
            // 写锁类里有 @PersistenceContext 字段, mock 的子类同样被处理, 要有一个 EntityManagerFactory
            .withBean(jakarta.persistence.EntityManagerFactory.class, () -> mock(jakarta.persistence.EntityManagerFactory.class))
            .withBean(FactoryFeatureConfigRepository.class, () -> mock(FactoryFeatureConfigRepository.class))
            .withBean(DashScopeClient.class, () -> mock(DashScopeClient.class))
            .withUserConfiguration(FactorySecrecyConfig.class, ProcessSecretAccessService.class,
                    ProcessSecretSampleFilter.class, LlmEgressGuard.class, ProcessSecretAiAccessAudit.class,
                    ProcessSecretExternalService.class, PlatformFactoryControlAuthority.class,
                    FactorySecrecySettingsService.class, PlatformFactorySecrecyController.class,
                    FeatureConfigController.class, ProcessSecretController.class, WorkdeskOutputSummarizer.class);

    @Test
    void everyWiringPointGetsTheContainersConfig() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FactorySecrecyConfig config = context.getBean(FactorySecrecyConfig.class);
            assertThat(config).isNotSameAs(FactorySecrecyConfig.allOn());
            assertThat(ReflectionTestUtils.getField(config, "readTx")).as("读配置有自己的只读事务").isNotNull();
            for (Class<?> wired : new Class<?>[]{ProcessSecretAccessService.class, ProcessSecretSampleFilter.class,
                    LlmEgressGuard.class, ProcessSecretAiAccessAudit.class, ProcessSecretExternalService.class}) {
                assertThat(ReflectionTestUtils.getField(context.getBean(wired), "secrecy"))
                        .as(wired.getSimpleName()).isSameAs(config);
            }
            assertThat(ReflectionTestUtils.getField(context.getBean(ProcessSecretController.class), "secrecyConfig"))
                    .isSameAs(config);
            assertThat(ReflectionTestUtils.getField(context.getBean(FeatureConfigController.class), "secrecyConfig"))
                    .isSameAs(config);
            assertThat(ReflectionTestUtils.getField(context.getBean(WorkdeskOutputSummarizer.class), "egressGuard"))
                    .isSameAs(context.getBean(LlmEgressGuard.class));
            assertThat(context).hasSingleBean(PlatformFactorySecrecyController.class);
            assertThat(context).hasSingleBean(FactorySecrecySettingsService.class);
        });
    }
}
