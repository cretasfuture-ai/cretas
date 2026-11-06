package com.cretas.aims.service.shortage;

import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.ProcessParamDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProcessParamDeviationCaseRolloutContractTest {

    private ApplicationContextRunner serviceContext() {
        return new ApplicationContextRunner()
                .withBean(ShortageCaseRepository.class, () -> mock(ShortageCaseRepository.class))
                .withBean(ProcessParamDeviationCaseDetailRepository.class,
                        () -> mock(ProcessParamDeviationCaseDetailRepository.class))
                .withBean(ShortageCaseEventRepository.class, () -> mock(ShortageCaseEventRepository.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(ProductionBatchRepository.class, () -> mock(ProductionBatchRepository.class))
                .withBean(ProcessSecretAggregateExclusions.class, () -> mock(ProcessSecretAggregateExclusions.class))
                .withBean(PermissionService.class, () -> mock(PermissionService.class))
                .withUserConfiguration(ProcessParamDeviationCaseService.class);
    }

    @Test
    void unconfiguredWriterIsDisabled() {
        serviceContext().run(context -> {
            assertThat(context.getBean(ProcessParamDeviationCaseService.class).recordReport(null))
                    .containsExactly(ProcessParamDeviationCaseService.Applied.DISABLED);
            verifyNoInteractions(context.getBean(ShortageCaseRepository.class),
                    context.getBean(ProcessParamDeviationCaseDetailRepository.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "pg-prod"})
    void publishedProductionProfilesEnableTheRealWriter(String profile) {
        serviceContext().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context.getBean(ProcessParamDeviationCaseService.class).recordReport(null))
                        .containsExactly(ProcessParamDeviationCaseService.Applied.IGNORED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "pg-prod"})
    void explicitFalseOverrideClosesBothProductionProfiles(String profile) {
        serviceContext().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile,
                        ProcessParamDeviationCaseService.FEATURE_PROPERTY + "=false")
                .run(context -> assertThat(context.getBean(ProcessParamDeviationCaseService.class).recordReport(null))
                        .containsExactly(ProcessParamDeviationCaseService.Applied.DISABLED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "pg-prod"})
    void explicitTrueIsBoundToTheRealWriterWithoutRequiringAReport(String profile) {
        serviceContext().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile,
                        ProcessParamDeviationCaseService.FEATURE_PROPERTY + "=true")
                .run(context -> assertThat(context.getBean(ProcessParamDeviationCaseService.class).recordReport(null))
                        .containsExactly(ProcessParamDeviationCaseService.Applied.IGNORED));
    }
}
