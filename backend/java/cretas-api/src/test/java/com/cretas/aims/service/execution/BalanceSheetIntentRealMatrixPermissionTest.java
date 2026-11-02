package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workdesk.BalanceSheetQueryTool;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.finance.BalanceSheetService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S6 acceptance (ERP-S6-02-SKILL-ROLE-BYPASS-001), end to end on the intent execution path: the
 * intent 「查看本月资产负债表」 ({@code BALANCE_SHEET_QUERY → balance_sheet_query}) runs through
 * {@link ToolDispatchService#executeWithTool} — the dispatcher behind the AI execute endpoints — with the
 * real tool, the real {@link ToolRbacEnforcer} / {@link ToolRbacGuard} and the real
 * {@link PermissionServiceImpl} matrix (no factory overrides). Only the balance-sheet computation and the
 * user lookup are stubbed.
 *
 * <p>production_manager is denied, as {@code GET finance/report/balance-sheet} denies them, and the
 * balance sheet is never computed; finance_manager passes the gate and the balance sheet is computed.</p>
 */
@DisplayName("资产负债表意图: 真实权限矩阵下, 生产经理拒绝、财务经理放行 (与直接接口一致)")
class BalanceSheetIntentRealMatrixPermissionTest {

    private static final String FACTORY = "F006";
    private static final long PRODUCTION_MANAGER = 9601L;
    private static final long FINANCE_MANAGER = 9602L;

    private BalanceSheetService balanceSheets;
    private ToolDispatchService dispatch;
    private BalanceSheetQueryTool tool;

    @BeforeEach
    void setUp() {
        balanceSheets = mock(BalanceSheetService.class);
        when(balanceSheets.generate(anyString(), anyInt(), anyInt())).thenReturn(new BalanceSheetDTO());
        tool = new BalanceSheetQueryTool();
        ReflectionTestUtils.setField(tool, "balanceSheetService", balanceSheets);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());

        PermissionServiceImpl permissions = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(permissions);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(PRODUCTION_MANAGER)).thenReturn(Optional.of(user(PRODUCTION_MANAGER, "production_manager")));
        when(users.findById(FINANCE_MANAGER)).thenReturn(Optional.of(user(FINANCE_MANAGER, "finance_manager")));
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        DashScopeClient llm = mock(DashScopeClient.class);
        dispatch = new ToolDispatchService(registry, new ObjectMapper(), llm, mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
    }

    @Test
    void productionManagerIsDeniedAndTheBalanceSheetIsNeverComputed() {
        IntentExecuteResponse response = run(PRODUCTION_MANAGER, "production_manager");
        assertThat(response.getStatus()).isEqualTo("PERMISSION_DENIED");
        verify(balanceSheets, never()).generate(any(), any(), any());
    }

    @Test
    void financeManagerPassesTheGateAndGetsTheBalanceSheet() {
        IntentExecuteResponse response = run(FINANCE_MANAGER, "finance_manager");
        assertThat(response.getStatus()).isNotEqualTo("PERMISSION_DENIED");
        verify(balanceSheets).generate(eq(FACTORY), anyInt(), anyInt());
    }

    private IntentExecuteResponse run(long userId, String role) {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("BALANCE_SHEET_QUERY").intentCategory("FINANCE")
                .intentName("资产负债表").toolName("balance_sheet_query").build();
        return dispatch.executeWithTool(tool, FACTORY,
                IntentExecuteRequest.builder().userInput("查看本月资产负债表").build(), intent, userId, role, null);
    }

    private static User user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(FACTORY);
        u.setRoleCode(role);
        u.setIsActive(true);
        return u;
    }
}
