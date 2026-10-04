package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClarificationCandidateServiceContractTest {

    private static final String FACTORY = "F006";
    private IntentConfigManagementService configService;
    private IntentPermissionGate permissionGate;
    private IntentAccessModeFilter accessModeFilter;
    private ClarificationCandidateService service;

    @BeforeEach
    void setUp() {
        configService = mock(IntentConfigManagementService.class);
        permissionGate = mock(IntentPermissionGate.class);
        accessModeFilter = mock(IntentAccessModeFilter.class);
        service = new ClarificationCandidateService(
                configService, permissionGate, accessModeFilter, new WriteGuardService());
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(permissionGate.check(any(), eq(7L), eq("manager")))
                .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        when(configService.hasPermission(eq(FACTORY), anyString(), eq("manager"))).thenReturn(true);
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
    }

    @Test
    void returnsCanonicalActiveAuthorizedReadChoicesAtMostThree() {
        AIIntentConfig first = config("MATERIAL_BATCH_QUERY", "当前库存名称", "inventory:read", "FACTORY");
        AIIntentConfig second = config("PROCESSING_BATCH_LIST", "当前生产批次名称", null, "FACTORY");
        AIIntentConfig third = config("QUALITY_CHECK_LIST", "当前质检名称", null, "COMMON");
        AIIntentConfig fourth = config("EXTRA_QUERY", "不应出现", null, "COMMON");
        whenConfig(first, second, third, fourth);

        List<IntentExecuteResponse.SuggestedAction> actions = service.buildCandidateActions(
                candidates("MATERIAL_BATCH_QUERY", "PROCESSING_BATCH_LIST", "QUALITY_CHECK_LIST", "EXTRA_QUERY"),
                FACTORY, 7L, "manager", "READ");

        assertThat(actions).filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode())).hasSize(3);
        assertThat(actions).filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionName)
                .containsExactly("当前库存名称", "当前生产批次名称", "当前质检名称");
        IntentExecuteResponse.SuggestedAction firstAction = actions.get(0);
        assertThat(firstAction.getEndpoint()).isEqualTo("/api/mobile/F006/ai-intents/execute");
        assertThat(firstAction.getParameters()).containsEntry("intentCode", "MATERIAL_BATCH_QUERY");
    }

    @Test
    void dropsInactiveMissingUnauthorizedWriteAndDoesNotPadBusinessChoices() {
        AIIntentConfig inactive = config("INACTIVE_QUERY", "停用", null, "COMMON");
        inactive.setIsActive(false);
        AIIntentConfig denied = config("DENIED_QUERY", "无权限", "inventory:read", "COMMON");
        AIIntentConfig write = config("MATERIAL_BATCH_UPDATE", "写入", null, "FACTORY");
        whenConfig(inactive, denied, write);
        when(permissionGate.check(eq(denied), eq(7L), eq("manager")))
                .thenReturn(IntentPermissionGate.PermissionCheck.denied("inventory:read"));
        when(accessModeFilter.isWriteIntent(eq(write))).thenReturn(true);

        List<IntentExecuteResponse.SuggestedAction> actions = service.buildCandidateActions(
                candidates("INACTIVE_QUERY", "UNKNOWN", "DENIED_QUERY", "MATERIAL_BATCH_UPDATE"),
                FACTORY, 7L, "manager", "READ");

        assertThat(actions).extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
    }

    @Test
    void unknownDomainFailsClosedForExclusiveIntentButKeepsCommonIntent() {
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn(null);
        AIIntentConfig factoryOnly = config("FACTORY_ONLY_QUERY", "工厂专属", null, "FACTORY");
        AIIntentConfig common = config("COMMON_QUERY", "通用查询", null, "COMMON");
        whenConfig(factoryOnly, common);

        List<IntentExecuteResponse.SuggestedAction> actions = service.buildCandidateActions(
                candidates("FACTORY_ONLY_QUERY", "COMMON_QUERY"), FACTORY, 7L, "manager", "OPERATE");

        assertThat(actions).filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionName)
                .containsExactly("通用查询");
    }

    @Test
    void missingIdentityOrLookupErrorShowsOnlyRephrase() {
        AIIntentConfig config = config("COMMON_QUERY", "通用查询", null, "COMMON");
        whenConfig(config);
        when(configService.getIntentByCode(FACTORY, "BROKEN_QUERY"))
                .thenThrow(new IllegalStateException("lookup failed"));

        assertThat(service.buildCandidateActions(candidates("COMMON_QUERY"), FACTORY, null, "manager", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
        assertThat(service.buildCandidateActions(candidates("BROKEN_QUERY"), FACTORY, 7L, "manager", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
        when(configService.resolveBusinessDomain(FACTORY))
                .thenThrow(new IllegalStateException("domain lookup failed"));
        assertThat(service.buildCandidateActions(candidates("COMMON_QUERY"), FACTORY, 7L, "manager", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
    }

    @Test
    void skipsNullCandidateContinuesAndRejectsNonPositiveOrForeignIdentity() {
        AIIntentConfig common = config("COMMON_QUERY", "通用查询", null, "COMMON");
        AIIntentConfig foreign = config("FOREIGN_QUERY", "外部工厂查询", null, "COMMON");
        foreign.setFactoryId("F999");
        whenConfig(common, foreign);

        IntentMatchResult withNull = IntentMatchResult.builder().topCandidates(java.util.Arrays.asList(
                null,
                IntentMatchResult.CandidateIntent.builder()
                        .intentCode("COMMON_QUERY").intentName("untrusted").confidence(0.8).build()))
                .build();
        assertThat(service.buildCandidateActions(withNull, FACTORY, 7L, "manager", "READ"))
                .filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionName)
                .containsExactly("通用查询");
        assertThat(service.buildCandidateActions(candidates("COMMON_QUERY"), FACTORY, 0L, "manager", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
        assertThat(service.buildCandidateActions(candidates("FOREIGN_QUERY"), FACTORY, 7L, "manager", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
    }

    private AIIntentConfig config(String code, String name, String permission, String businessType) {
        AIIntentConfig config = AIIntentConfig.builder()
                .intentCode(code).intentName(name).businessType(businessType)
                .requiredPermission(permission).sensitivityLevel("LOW").build();
        config.setIsActive(true);
        return config;
    }

    private void whenConfig(AIIntentConfig... configs) {
        for (AIIntentConfig config : configs) {
            when(configService.getIntentByCode(FACTORY, config.getIntentCode()))
                    .thenReturn(Optional.of(config));
        }
    }

    private IntentMatchResult candidates(String... codes) {
        return IntentMatchResult.builder().topCandidates(java.util.Arrays.stream(codes)
                .map(code -> IntentMatchResult.CandidateIntent.builder()
                        .intentCode(code).intentName("untrusted " + code).confidence(0.7).build())
                .toList()).build();
    }
}
