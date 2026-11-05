package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IntentExecutionAdmissionContractTest {
    @ParameterizedTest
    @CsvSource({"normal,NULL", "normal,UNKNOWN", "normal,THROW", "explicit,NULL",
            "explicit,UNKNOWN", "explicit,THROW", "confirm,NULL", "confirm,UNKNOWN", "confirm,THROW"})
    void unverifiedMetadataCannotReachRecognitionSessionProviderOrClaim(String entry, String metadata) {
        for (String role : List.of("finance_manager", "hr_admin", "sales_manager")) {
            Harness h = new Harness();
            if (metadata.equals("THROW")) {
                when(h.domains.resolveBusinessDomain("UNVERIFIED")).thenThrow(new IllegalStateException("metadata down"));
            } else {
                when(h.domains.resolveBusinessDomain("UNVERIFIED"))
                        .thenReturn(metadata.equals("NULL") ? null : "UNKNOWN");
            }
            IntentExecuteResponse response = h.execute(entry, "UNVERIFIED", role);
            assertThat(response.getStatus()).isEqualTo("TENANT_TYPE_UNAVAILABLE");
            assertThat(response.getMetadata()).containsEntry("httpStatus", 503);
            verify(h.domains, times(1)).resolveBusinessDomain("UNVERIFIED");
            verifyNoInteractions(h.dependencies.toArray());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOCK_REST", "DEMO_REST", "R_DONGMENKOU_REAL", "R_HONGDEJI_REAL",
            "R_HUOGUO_GENERIC_REAL", "R_ILTEATRO_REAL", "R_JINCHUAN_HG_REAL", "R_JINRINIUSHI_REAL",
            "R_LINJIAYAN_REAL", "R_QINGHUAJIAO_REAL", "R_SHANGMA_HG_REAL", "R_XIMAXIANG_REAL",
            "R_XINBASHU_REAL", "R_YONGHE_REAL", "R_YOUZIYOUWEI_REAL", "R_YUJIUJING_REAL"})
    void historicalIdentityCannotBeReclassifiedByFactoryMetadata(String id) {
        for (String entry : List.of("normal", "explicit", "confirm")) {
            Harness h = new Harness();
            when(h.domains.resolveBusinessDomain(id)).thenReturn("FACTORY");
            assertThat(h.execute(entry, id, "finance_manager").getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            verifyNoInteractions(h.dependencies.toArray());
            verifyNoInteractions(h.domains);
        }
    }

    private static final class Harness {
        final List<Object> dependencies = new ArrayList<>();
        final IntentConfigManagementService domains = mock(IntentConfigManagementService.class);
        final IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                dependency(AIIntentService.class), dependency(IntentSemanticsParser.class),
                dependency(SemanticCacheService.class), dependency(RuleEngineService.class),
                dependency(ConversationService.class), dependency(ConversationMemoryService.class),
                new ObjectMapper(), dependency(DashScopeClient.class), mock(DashScopeConfig.class),
                dependency(IntentKnowledgeBase.class), dependency(AIAnalysisResultRepository.class),
                dependency(ToolRegistry.class), dependency(AnalysisRouterService.class),
                dependency(ComplexityRouter.class), dependency(AgentOrchestrator.class),
                dependency(AgenticRAGRouterService.class), dependency(ResultValidatorService.class),
                dependency(ToolDispatchService.class), dependency(DynamicToolSelectionService.class),
                dependency(QueryPreprocessorService.class));

        Harness() {
            ReflectionTestUtils.setField(orchestrator, "configService", domains);
            ReflectionTestUtils.setField(orchestrator, "previewTokenService", dependency(PreviewTokenService.class));
        }
        <T> T dependency(Class<T> type) { T value = mock(type); dependencies.add(value); return value; }
        IntentExecuteResponse execute(String entry, String factory, String role) {
            var request = IntentExecuteRequest.builder().userInput("查看本厂库存").intentCode("MATERIAL_BATCH_QUERY").build();
            return switch (entry) {
                case "normal" -> orchestrator.execute(factory, request, 7L, role);
                case "explicit" -> orchestrator.executeWithExplicitIntent(factory, request, 7L, role);
                case "confirm" -> orchestrator.confirm(factory, null, 7L, role);
                default -> throw new IllegalArgumentException(entry);
            };
        }
    }
}
