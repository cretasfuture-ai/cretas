package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessCatalogTool;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.ai.*;
import com.cretas.aims.repository.bom.*;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.WorkProcessServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Public chat -> actual gateway/tool -> protocol apply -> actual service -> repository IO. */
class WorkProcessPreviewApplyContractTest {
    final ObjectMapper mapper = new ObjectMapper();
    final WorkProcessRepository repository = mock(WorkProcessRepository.class);
    final DashScopeClient llm = mock(DashScopeClient.class);
    final PreviewTokenService previewTokens = mock(PreviewTokenService.class);
    final ToolExecutionAuditEventRepository auditRepository = mock(ToolExecutionAuditEventRepository.class);
    final ToolExecutionIdempotencyRepository idempotencyRepository = mock(ToolExecutionIdempotencyRepository.class);
    final AtomicReference<ToolExecutionIdempotencyRecord> ledgerRecord = new AtomicReference<>();
    final AtomicReference<PreviewTokenService.BoundTokenRequest> issuedRequest = new AtomicReference<>();
    final AtomicReference<String> issuedDigest = new AtomicReference<>();
    CanvasAIController controller;

    @BeforeEach void setUp() {
        var service = new WorkProcessServiceImpl(repository,
                mock(WorkProcessGovernanceAuditRepository.class), mock(ProductWorkProcessRepository.class),
                mock(ProductProcessWorkflowRepository.class), mock(WorkProcessTaskRepository.class), mapper,
                mock(BomSeasoningItemRepository.class), mock(BomProcessInjectionConfigRepository.class));
        when(repository.findByFactoryId(eq("F006"), any())).thenReturn(new PageImpl<>(List.of()));
        when(repository.findDistinctProcessCategories("F006")).thenReturn(List.of("加工"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var tool = new WorkProcessCatalogTool(service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        var registry = new ToolRegistry();
        registry.registerExternal(tool.getToolName(), tool);

        var users = mock(UserRepository.class);
        var factories = mock(FactoryRepository.class);
        var user = new User(); user.setId(42L); user.setFactoryId("F006");
        user.setRoleCode("factory_super_admin"); user.setIsActive(true);
        var factory = new Factory(); factory.setId("F006");
        factory.setType(FactoryType.FACTORY); factory.setIsActive(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(factories.findById("F006")).thenReturn(Optional.of(factory));
        var permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", mock(PlatformRolePermissionRepository.class));
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        when(auditRepository.completeStarted(anyString(), any(), any(), any(), anyString(), any())).thenReturn(1);
        when(auditRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditRepository.bindSecurityEvidence(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(idempotencyRepository.findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(ledgerRecord.get()));
        when(idempotencyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ToolExecutionIdempotencyRecord record = invocation.getArgument(0);
            if (record.getId() == null) ReflectionTestUtils.setField(record, "id", 1L);
            ledgerRecord.set(record);
            return record;
        });
        when(idempotencyRepository.completeFromState(anyString(), any(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ToolExecutionIdempotencyRecord record = ledgerRecord.get();
                    if (record != null) {
                        ReflectionTestUtils.setField(record, "state", invocation.getArgument(2));
                        ReflectionTestUtils.setField(record, "outcomeStatus", invocation.getArgument(3));
                    }
                    return 1;
                });
        var gateway = new DefaultToolExecutionGateway(new ToolPrincipalPolicy(users, factories, permissions),
                new ToolRuntimeRegistry(registry, mock(FactoryToolConfigRepository.class)),
                new ToolConfirmationLease(previewTokens, mapper),
                new ToolExecutionLedgerService(auditRepository, idempotencyRepository), mapper);
        var mobile = mock(MobileService.class);
        when(mobile.getUserFromToken("local-token")).thenReturn(UserDTO.builder().id(42L)
                .factoryId("F006").factoryType("FACTORY").roleCode(FactoryUserRole.factory_super_admin).build());
        controller = new CanvasAIController(registry, mapper, llm, mobile, gateway);
        ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
        when(previewTokens.resolveClaim(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(true);
        when(previewTokens.createBoundToken(any())).thenAnswer(invocation -> {
            PreviewTokenService.BoundTokenRequest request = invocation.getArgument(0);
            Map<String, Object> frozenParameters = deepCopy(request.parameters());
            Map<String, Object> frozenCurrentValues = deepCopy(request.currentValues());
            Map<String, Object> frozenNewValues = deepCopy(request.newValues());
            PreviewTokenService.BoundTokenRequest frozen = new PreviewTokenService.BoundTokenRequest(
                    request.factoryId(), request.userId(), request.username(), request.intentCode(),
                    request.intentName(), request.toolName(), request.descriptorVersion(), request.executionMode(),
                    request.entityType(), request.entityId(), request.operation(), frozenParameters,
                    frozenCurrentValues, frozenNewValues, request.expiresInSeconds());
            String digest = ToolCommandDigest.commandDigest(
                    frozen.factoryId(), frozen.userId(), frozen.toolName(), frozen.descriptorVersion(),
                    frozen.executionMode(), mapper.valueToTree(frozen.parameters()));
            issuedRequest.set(frozen);
            issuedDigest.set(digest);
            return IntentPreviewToken.builder()
                    .token("canvas-proof")
                    .factoryId(frozen.factoryId()).tenantId(frozen.factoryId()).userId(frozen.userId())
                    .toolName(frozen.toolName()).descriptorVersion(frozen.descriptorVersion())
                    .commandDigest(digest)
                    .parametersHash(ToolCommandDigest.parametersHash(mapper.valueToTree(frozen.parameters())))
                    .expiresAt(LocalDateTime.now().plusMinutes(5))
                    .status(IntentPreviewToken.TokenStatus.PENDING)
                    .build();
        });
        when(previewTokens.readBoundSnapshot(eq("canvas-proof"), eq("F006"), eq(42L)))
                .thenAnswer(invocation -> Optional.of(boundSnapshot(issuedRequest.get(), issuedDigest.get())));
        when(previewTokens.claimToken(anyString(), eq("F006"), eq(42L), anyString()))
                .thenAnswer(invocation -> {
                    String expectedDigest = invocation.getArgument(3);
                    PreviewTokenService.BoundTokenRequest request = issuedRequest.get();
                    if (request == null || !Objects.equals(expectedDigest, issuedDigest.get())) {
                        return PreviewTokenService.ClaimResult.failure("issued proof mismatch");
                    }
                    IntentPreviewToken claimed = IntentPreviewToken.builder()
                            .token("canvas-proof").factoryId(request.factoryId()).tenantId(request.factoryId()).userId(request.userId())
                            .intentCode(request.intentCode()).entityType(request.entityType())
                            .entityId(request.entityId()).operation(request.operation())
                            .executionMode(request.executionMode())
                            .toolName(request.toolName()).descriptorVersion(request.descriptorVersion())
                            .commandDigest(issuedDigest.get())
                            .parametersHash(ToolCommandDigest.parametersHash(mapper.valueToTree(request.parameters())))
                            .expiresAt(LocalDateTime.now().plusMinutes(5))
                            .status(IntentPreviewToken.TokenStatus.EXECUTING)
                            .build();
                    return PreviewTokenService.ClaimResult.success(claimed, "canvas-claim", deepCopy(request.parameters()));
                });
    }

    CanvasAIController.AIResponse preview(String extraction, String sentence) {
        when(llm.chatLowTemp(anyString(), anyString())).thenReturn(extraction);
        var request = new CanvasAIController.AIRequest();
        request.setModuleCode("work_process_catalog"); request.setMode("plan"); request.setMessage(sentence);
        var response = controller.chat("F006", "Bearer local-token", request).getData();
        assertEquals(1, response.getDiffs().size(), response.getReply());
        verify(repository, never()).save(any());
        return response;
    }

    @SuppressWarnings("unchecked")
    Map<String,Object> params(CanvasAIController.AIResponse preview) {
        return (Map<String,Object>) preview.getDiffs().get(0).get("params");
    }

    WorkProcess apply(CanvasAIController.AIResponse preview) {
        return apply(preview, null);
    }

    WorkProcess apply(CanvasAIController.AIResponse preview, Map<String, Object> submittedParams) {
        List<Map<String, Object>> diffs = preview.getDiffs();
        if (submittedParams != null) {
            Map<String, Object> diff = new LinkedHashMap<>(diffs.get(0));
            diff.put("params", submittedParams);
            diffs = List.of(diff);
        }
        var receipt = controller.applyDiffsWithProtocol("F006", "Bearer local-token", "3", diffs);
        assertTrue(receipt.getSuccess(), receipt.getMessage());
        assertEquals(1, receipt.getData().applied());
        var saved = ArgumentCaptor.forClass(WorkProcess.class);
        verify(repository).save(saved.capture());
        verify(previewTokens, times(1)).claimToken(eq("canvas-proof"), eq("F006"), eq(42L), anyString());
        verify(previewTokens, times(1)).resolveClaim(eq("canvas-proof"), eq("canvas-claim"), eq(true), anyString());
        return saved.getValue();
    }

    private PreviewTokenService.BoundTokenSnapshot boundSnapshot(
            PreviewTokenService.BoundTokenRequest request, String digest) {
        return new PreviewTokenService.BoundTokenSnapshot(
                request.intentCode(), request.entityType(), request.entityId(), request.operation(),
                request.toolName(), request.descriptorVersion(), request.executionMode(), digest,
                LocalDateTime.now().plusMinutes(5),
                IntentPreviewToken.TokenStatus.PENDING);
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        try {
            return mapper.readValue(mapper.writeValueAsString(source), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new AssertionError("fixture JSON snapshot failed", exception);
        }
    }

    @Test void allDeclaredFieldsAreVisibleAndPersistFromTheExactPreview() {
        var response = preview("""
                {"action":"create","processName":"验收工序","processCategory":"加工",
                 "estimatedMinutes":5,"standardHourlyRate":25,"standardYieldMin":0.3,
                 "standardYieldMax":0.6,"needsInput":false,"customFieldSchema":[
                 {"key":"oilTemp","label":"油温","type":"number","enabled":true,
                  "unit":"℃","standardValue":155,"min":150,"max":160}]}
                """, "新建验收工序，加工类，预估工时5分钟，时薪25元，出成率30%至60%，不需录投入量；油温标准155℃，范围150到160℃");
        var params = params(response);
        assertEquals(5, params.get("estimatedMinutes"));
        assertNotNull(params.get("standardHourlyRate"), "Preview lost the declared hourly rate");
        assertEquals(0, new BigDecimal(params.get("standardHourlyRate").toString()).compareTo(new BigDecimal("25.00")));
        for (var label : List.of("加工", "预估工时：5 分钟", "标准时薪：25.00 元/小时", "出成率下限：0.3", "出成率上限：0.6", "需录投入量：否",
                "油温", "单位=℃", "标准=155", "下限=150", "上限=160"))
            assertTrue(response.getReply().contains(label), response.getReply());
        assertEquals("1.1.0", assertInstanceOf(Map.class, response.getDiffs().get(0).get("proof"))
                .get("descriptorVersion"));
        // Simulate the browser JSON round-trip: an integral rate is sent as JS number 25,
        // while the issued server snapshot remains the exact frozen preview parameters.
        Map<String, Object> httpParams = new LinkedHashMap<>(params);
        httpParams.put("standardHourlyRate", Integer.valueOf(25));
        var saved = apply(response, httpParams);
        assertEquals(params.get("estimatedMinutes"), saved.getEstimatedMinutes());
        assertEquals(0, new BigDecimal(params.get("standardHourlyRate").toString()).compareTo(saved.getStandardHourlyRate()));
        assertEquals(new BigDecimal(params.get("standardYieldMin").toString()), saved.getStandardYieldMin());
        assertEquals(new BigDecimal(params.get("standardYieldMax").toString()), saved.getStandardYieldMax());
        assertEquals(params.get("needsInput"), saved.getNeedsInput());
        assertEquals(params.get("customFieldSchema"), saved.getCustomFieldSchema());
    }

    @Test void omittedOptionalValuesStayAbsentAndNullAfterARealCreate() {
        var response = preview("{\"action\":\"create\",\"processName\":\"仅名称类别\",\"processCategory\":\"加工\"}",
                "新建仅名称类别工序，加工类");
        for(var key : List.of("estimatedMinutes", "standardHourlyRate", "standardYieldMin", "standardYieldMax", "needsInput"))
            assertFalse(params(response).containsKey(key), key);
        var saved = apply(response);
        assertEquals("仅名称类别", saved.getProcessName());
        assertNull(saved.getEstimatedMinutes()); assertNull(saved.getStandardHourlyRate());
        assertNull(saved.getStandardYieldMin()); assertNull(saved.getStandardYieldMax());
    }

    @Test void updateKeepsUnspecifiedNameAndAppliesShownTimeAndRate() {
        var existing = WorkProcess.builder().id("wp-existing").factoryId("F006").processName("原名")
                .processCategory("加工").estimatedMinutes(10).standardHourlyRate(new BigDecimal("10.00")).build();
        when(repository.findByFactoryIdAndId("F006", "wp-existing")).thenReturn(Optional.of(existing));
        var response = preview("{\"action\":\"update\",\"id\":\"wp-existing\",\"estimatedMinutes\":5,\"standardHourlyRate\":25}",
                "修改wp-existing，预估工时5分钟，标准时薪25元");
        assertFalse(params(response).containsKey("processName"));
        assertTrue(response.getReply().contains("预估工时：5 分钟"));
        assertTrue(response.getReply().contains("标准时薪：25.00 元/小时"));
        var saved = apply(response);
        assertEquals("原名", saved.getProcessName()); assertEquals(5, saved.getEstimatedMinutes());
        assertEquals(new BigDecimal("25.00"), saved.getStandardHourlyRate());
    }
}
