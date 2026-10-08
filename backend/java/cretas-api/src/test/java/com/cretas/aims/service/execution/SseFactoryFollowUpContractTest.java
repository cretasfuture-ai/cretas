package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.*;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.material.*;
import com.cretas.aims.ai.tool.impl.quality.*;
import com.cretas.aims.ai.tool.impl.workdesk.*;
import com.cretas.aims.ai.tool.impl.processing.ProcessTaskQueryTool;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.calibration.*;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.calibration.impl.ToolCallRedundancyServiceImpl;
import com.cretas.aims.service.impl.*;
import com.cretas.aims.service.intent.*;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public SSE entry, real dispatch/tools/services/gates; mocks are DB and LLM IO only. */
class SseFactoryFollowUpContractTest {
    @Test
    void warehouseInventoryResultOffersThreeActuallyAvailableReadIntents() throws Exception {
        try (Harness h = new Harness("warehouse_worker")) {
            JsonNode event = h.stream("MATERIAL_BATCH_QUERY", "查询原材料库存", Map.of());
            h.assertSuccess(event);
            assertThat(codes(event.path("followUps"))).containsExactly(
                    "MATERIAL_TODAY_RECEIVING_QUERY", "MATERIAL_LOW_STOCK_ALERT", "MATERIAL_EXPIRING_ALERT");
            h.assertExecutableReads(event.path("followUps"));
            verify(h.materials).findByFactoryIdAndCreatedByIn(eq("F006"), eq(List.of(7L)), any(Pageable.class));
        }
    }

    @Test
    void qualityResultOffersQualityReadsWithoutWarehouseFunctions() throws Exception {
        try (Harness h = new Harness("quality_inspector")) {
            JsonNode event = h.stream("QUALITY_CHECK_QUERY", "查询质检记录", Map.of());
            h.assertSuccess(event);
            assertThat(codes(event.path("followUps"))).containsExactly("RECEIVE_QUALITY_CHECK_TODAY", "QUALITY_STATS");
            assertThat(codes(event.path("followUps"))).doesNotContain("MATERIAL_TODAY_RECEIVING_QUERY", "MATERIAL_BATCH_QUERY");
            h.assertExecutableReads(event.path("followUps"));
            verify(h.inspections).findByFactoryId(eq("F006"), any(Pageable.class));
        }
    }

    @Test
    void confirmedWriteCompletesThroughRealToolAndOffersOnlyReads() throws Exception {
        try (Harness h = new Harness("quality_inspector")) {
            Map<String, Object> confirmed = h.guard.withServerConfirmation(Map.of(
                    "qualityInspectionId", "Q-D", "defectType", "APPEARANCE", "quantity", 2));
            JsonNode event = h.stream("QUALITY_DEFECT_RECORD", "登记外观不良2个", confirmed);
            h.assertSuccess(event);
            assertThat(codes(event.path("followUps"))).containsExactly(
                    "QUALITY_CHECK_QUERY", "RECEIVE_QUALITY_CHECK_TODAY", "QUALITY_STATS");
            h.assertExecutableReads(event.path("followUps"));
            verify(h.defects).save(any(QualityDefect.class));
        }
    }

    @Test
    void unavailableTargetsProduceExplicitEmptyArrayWhileAnswerStillCompletes() throws Exception {
        try (Harness h = new Harness("warehouse_worker")) {
            h.configs.keySet().removeAll(List.of("MATERIAL_TODAY_RECEIVING_QUERY", "MATERIAL_LOW_STOCK_ALERT", "MATERIAL_EXPIRING_ALERT"));
            JsonNode event = h.stream("MATERIAL_BATCH_QUERY", "查询原材料库存", Map.of());
            h.assertSuccess(event);
            assertThat(event.path("followUps").isArray()).isTrue();
            assertThat(event.path("followUps")).isEmpty();
        }
    }

    @Test
    void writeAwaitingConfirmationHasNoSuggestionsAndNoBusinessWrite() throws Exception {
        try (Harness h = new Harness("quality_inspector")) {
            JsonNode event = h.stream("QUALITY_DEFECT_RECORD", "登记不良", Map.of());
            assertThat(event.path("status").asText()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            assertThat(event.path("followUps")).isEmpty();
            verify(h.defects, never()).save(any());
        }
    }

    static List<String> codes(JsonNode followUps) {
        List<String> codes = new ArrayList<>();
        followUps.forEach(item -> codes.add(item.path("intentCode").asText()));
        return codes;
    }

    static class Harness implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Map<String, AIIntentConfig> configs = new LinkedHashMap<>();
        final ToolRegistry registry = new ToolRegistry();
        final MaterialBatchRepository materials = mock(MaterialBatchRepository.class);
        final QualityInspectionRepository inspections = mock(QualityInspectionRepository.class);
        final QualityDefectRepository defects = mock(QualityDefectRepository.class);
        final FactoryToolConfigRepository switches = mock(FactoryToolConfigRepository.class);
        final AIIntentConfigRepository intentRows = mock(AIIntentConfigRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final WriteGuardService guard = new WriteGuardService();
        final IntentConfigManagementServiceImpl configService = new IntentConfigManagementServiceImpl(intentRows);
        final AIIntentService intents = new AIIntentServiceImpl(null, configService, null);
        final FrontlineAiWriteGrantService grants;
        final ToolPermissionChain toolPermissions;
        final IntentPermissionGate intentPermissions = new IntentPermissionGate();
        final BusinessTypeGate domain = new BusinessTypeGate(configService);
        final SseStreamingService sse;
        final Object suggestions;
        final String role;
        final AtomicReference<JsonNode> result = new AtomicReference<>();
        CountDownLatch complete;

        Harness(String role) throws Exception {
            this.role = role;
            User user = new User(); user.setId(7L); user.setFactoryId("F006"); user.setRoleCode(role);
            when(users.findById(7L)).thenReturn(Optional.of(user));
            FactoryRepository factories = mock(FactoryRepository.class);
            Factory factory = new Factory(); factory.setId("F006"); factory.setType(FactoryType.FACTORY);
            when(factories.findById("F006")).thenReturn(Optional.of(factory));
            configService.setFactoryRepository(factories);
            when(intentRows.findByIntentCodeAndFactoryIdOrPlatform(anyString(), eq("F006")))
                    .thenAnswer(call -> Optional.ofNullable(configs.get(call.getArgument(0))).map(List::of).orElse(List.of()));
            when(intentRows.findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(anyString()))
                    .thenAnswer(call -> Optional.ofNullable(configs.get(call.getArgument(0))));
            ReflectionTestUtils.setField(registry, "factoryToolConfigRepository", switches);
            PermissionServiceImpl permissions = new PermissionServiceImpl();
            ReflectionTestUtils.setField(permissions, "platformRepo", mock(com.cretas.aims.repository.permission.PlatformRolePermissionRepository.class));
            ReflectionTestUtils.setField(permissions, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
            ToolRbacGuard rbac = new ToolRbacGuard();
            ReflectionTestUtils.setField(rbac, "userRepository", users);
            ReflectionTestUtils.setField(rbac, "permissionService", permissions);
            ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
            ReflectionTestUtils.setField(enforcer, "rbacGuard", rbac);
            ReflectionTestUtils.setField(intentPermissions, "rbacGuard", rbac);
            grants = new FrontlineAiWriteGrantService(mock(AiFrontlineIntentGrantRepository.class), guard, configService, registry);
            toolPermissions = new ToolPermissionChain(enforcer, grants);

            when(materials.findByFactoryIdAndCreatedByIn(eq("F006"), anyList(), any(Pageable.class)))
                    .thenReturn(Page.empty());
            MaterialBatchServiceImpl materialService = new MaterialBatchServiceImpl(materials, null, null,
                    new MaterialBatchMapper(), null, null, null, null);
            ReflectionTestUtils.setField(materialService, "dataScopeResolver",
                    new DataScopeResolver(users, mock(RoleDefinitionRepository.class)));
            MaterialBatchQueryTool inventory = new MaterialBatchQueryTool();
            ReflectionTestUtils.setField(inventory, "materialBatchService", materialService);
            when(inspections.findByFactoryId(eq("F006"), any(Pageable.class))).thenReturn(Page.empty());
            QualityInspection own = new QualityInspection(); own.setId("Q-D"); own.setFactoryId("F006"); own.setInspectorId(7L);
            when(inspections.findById("Q-D")).thenReturn(Optional.of(own));
            when(defects.save(any(QualityDefect.class))).thenAnswer(call -> {
                QualityDefect saved = call.getArgument(0); saved.setId("DEF-D"); return saved;
            });
            QualityInspectionServiceImpl qualityService = new QualityInspectionServiceImpl(inspections, null, null);
            QualityCheckQueryTool quality = new QualityCheckQueryTool();
            ReflectionTestUtils.setField(quality, "qualityInspectionService", qualityService);
            QualityDefectRecordTool write = new QualityDefectRecordTool();
            ReflectionTestUtils.setField(write, "qualityInspectionService", qualityService);
            ReflectionTestUtils.setField(write, "qualityInspectionRepository", inspections);
            ReflectionTestUtils.setField(write, "qualityDefectService", new QualityDefectServiceImpl(defects, inspections));
            register("MATERIAL_BATCH_QUERY", inventory, "warehouse_worker");
            register("MATERIAL_TODAY_RECEIVING_QUERY", new MaterialTodayReceivingQueryTool(), null);
            register("MATERIAL_LOW_STOCK_ALERT", new MaterialLowStockAlertTool(), "warehouse_worker");
            register("MATERIAL_EXPIRING_ALERT", new MaterialExpiringAlertTool(), "warehouse_worker");
            register("QUALITY_CHECK_QUERY", quality, "quality_inspector");
            register("RECEIVE_QUALITY_CHECK_TODAY", new ReceiveQualityCheckTodayTool(), null);
            register("QUALITY_STATS", new QualityStatsQueryTool(), "quality_inspector");
            register("QUALITY_DEFECT_RECORD", write, "quality_inspector");
            register("QUALITY_CHECK_CREATE", new QualityCheckCreateTool(), "quality_inspector");
            register("PROCESS_TASK_QUERY", new ProcessTaskQueryTool(null), null);
            configs.put("MATERIAL_BATCH_USE", AIIntentConfig.builder().intentCode("MATERIAL_BATCH_USE").intentName("领料")
                    .intentCategory("DATA_OP").isActive(true).build());
            Object candidateService;
            try {
                candidateService = Class.forName("com.cretas.aims.service.execution.FactoryFollowUpService")
                        .getConstructor(AIIntentService.class, FrontlineAiWriteGrantService.class, ToolRegistry.class,
                                IntentPermissionGate.class, BusinessTypeGate.class, ToolPermissionChain.class)
                        .newInstance(intents, grants, registry, intentPermissions, domain, toolPermissions);
            } catch (ClassNotFoundException absentOnMain) { candidateService = null; }
            suggestions = candidateService;
            ToolCallRecordRepository calls = mock(ToolCallRecordRepository.class);
            when(calls.save(any(com.cretas.aims.entity.calibration.ToolCallRecord.class))).thenAnswer(call -> call.getArgument(0));
            var redundancy = new ToolCallRedundancyServiceImpl(calls, mock(ToolCallCacheRepository.class), mapper);
            ToolDispatchService dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                    redundancy, null, null, null, null, null);
            ReflectionTestUtils.setField(dispatch, "writeGuardService", guard);
            ReflectionTestUtils.setField(dispatch, "frontlineWriteGrants", grants);
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
            sse = spy(new SseStreamingService(intents, null, new IntentKnowledgeBase(), null,
                    mapper, registry, dispatch, null, mock(DashScopeClient.class), new DashScopeConfig()));
            ReflectionTestUtils.setField(sse, "businessTypeGate", domain);
            ReflectionTestUtils.setField(sse, "intentPermissionGate", intentPermissions);
            ReflectionTestUtils.setField(sse, "writeGuardService", guard);
            ReflectionTestUtils.setField(sse, "frontlineWriteGrants", grants);
            ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                    factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            if (suggestions != null) ReflectionTestUtils.setField(sse, "factoryFollowUpService", suggestions);
            doAnswer(call -> {
                String event = call.getArgument(1);
                if ("result".equals(event)) result.set(mapper.valueToTree(call.getArgument(2)));
                if ("complete".equals(event)) complete.countDown();
                return null;
            }).when(sse).sendSseEvent(any(), anyString(), any());
        }

        void register(String code, ToolExecutor tool, String roles) {
            ReflectionTestUtils.setField(tool, "objectMapper", mapper);
            @SuppressWarnings("unchecked") Map<String, ToolExecutor> tools =
                    (Map<String, ToolExecutor>) ReflectionTestUtils.getField(registry, "toolMap");
            tools.put(tool.getToolName(), tool);
            configs.put(code, AIIntentConfig.builder().intentCode(code).intentName(code)
                    .toolName(tool.getToolName()).intentCategory(guard.isWriteTool(tool) ? "DATA_OP" : "QUERY").sensitivityLevel("LOW")
                    .requiredRoles(roles).isActive(true).build());
        }

        JsonNode stream(String code, String question, Map<String, Object> context) throws Exception {
            result.set(null); complete = new CountDownLatch(1);
            sse.executeStream("F006", IntentExecuteRequest.builder().intentCode(code).userInput(question).context(context).build(), 7L, role);
            assertThat(complete.await(8, TimeUnit.SECONDS)).as("public SSE completed").isTrue();
            assertThat(result.get()).as("SSE result event").isNotNull();
            System.out.println("D_SSE_RESULT=" + result.get());
            return result.get();
        }

        JsonNode suggest(String completedCode, String status) {
            Object list = suggestions == null ? List.of() : ReflectionTestUtils.invokeMethod(suggestions,
                    "suggest", "F006", 7L, role, configs.get(completedCode), status);
            JsonNode node = mapper.valueToTree(list);
            System.out.println("D_SUGGESTIONS=" + role + ":" + node);
            return node;
        }

        void assertSuccess(JsonNode event) {
            assertThat(event.path("status").asText()).isIn("SUCCESS", "COMPLETED");
        }

        void assertExecutableReads(JsonNode followUps) {
            assertThat(followUps.size()).isGreaterThan(0);
            for (JsonNode item : followUps) {
                String code = item.path("intentCode").asText();
                AIIntentConfig config = configs.get(code);
                ToolExecutor tool = registry.getExecutor(config.getToolName()).orElseThrow();
                assertThat(item.path("label").asText()).isNotBlank();
                assertThat(item.path("question").asText()).isNotBlank().isNotEqualTo(item.path("label").asText());
                assertThat(grants.isWrite(tool, config)).as(code + " is read-only").isFalse();
                assertThat(FrontlineReadAudience.offeredTo(code, role)).isTrue();
                assertThat(intents.hasPermission("F006", code, role)).isTrue();
                assertThat(toolPermissions.denyReason(tool, "F006", role, config,
                        Map.of("userId", 7L, "factoryId", "F006", "userRole", role))).isEmpty();
            }
        }

        @Override public void close() { sse.destroy(); }
    }
}
