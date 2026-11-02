package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.execution.ToolDispatchService;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-10: 对话里说的批次状态没生效。
 *
 * <p>F006 验收: 「查询已用完状态的原料批次」「查询已过期状态的原料批次」都识别成 MATERIAL_BATCH_QUERY,
 * 却返回全部 114 条, queryConditions 恒为空 —— 对话入口对「只有可选参数」的读工具不做参数抽取,
 * 句子里的状态到不了工具。
 *
 * <p>走对话真实使用的那一段: {@link ToolDispatchService#executeWithTool} (/ai-intents/execute 的识别分支与
 * 短语直达分支都在这里汇合) → 真 {@link MaterialBatchQueryTool} → 真 {@link MaterialBatchServiceImpl}
 * → 真 {@link MaterialBatchRepository} (H2)。请求里只有用户原话, 不带 status 参数, 大模型桩不给任何参数。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("对话里说的批次状态 → material_batch_query 按它过滤 (ERP-S6-10)")
class MaterialBatchChatStatusEntryTest {

    private static final String F = "F-MBC-1";
    private static final Map<MaterialBatchStatus, Integer> SEEDED = Map.of(
            MaterialBatchStatus.AVAILABLE, 6,
            MaterialBatchStatus.USED_UP, 3,
            MaterialBatchStatus.EXPIRED, 2,
            MaterialBatchStatus.INSPECTING, 1,
            MaterialBatchStatus.RESERVED, 1);
    private static final long TOTAL = 13L;

    @Autowired private MaterialBatchRepository repo;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ToolDispatchService dispatch;
    private MaterialBatchQueryTool tool;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        int seq = 0;
        for (Map.Entry<MaterialBatchStatus, Integer> e : SEEDED.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                repo.saveAndFlush(batch(F, "C" + (seq++), e.getKey()));
            }
        }
        repo.saveAndFlush(batch("F-MBC-OTHER", "X1", MaterialBatchStatus.USED_UP));

        MaterialBatchMapper batchMapper = mock(MaterialBatchMapper.class);
        when(batchMapper.toDTO(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            return MaterialBatchDTO.builder().id(b.getId()).status(b.getStatus()).build();
        });
        tool = new MaterialBatchQueryTool();
        ReflectionTestUtils.setField(tool, "materialBatchService",
                new MaterialBatchServiceImpl(repo, null, null, batchMapper, null, null, null, null));
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        llm = mock(DashScopeClient.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, llm, mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());

        DataScopeContext.push(DataScopeContext.builder().userId(1L).factoryId(F).roleCode("factory_super_admin")
                .scope(DataScope.ALL).column("created_by").build());
    }

    @AfterEach
    void clearScope() {
        DataScopeContext.pop();
    }

    @ParameterizedTest(name = "「{0}」→ 只回 {1}")
    @CsvSource({
            "查询可用状态的原料批次, AVAILABLE",
            "查询已用完状态的原料批次, USED_UP",
            "查询已过期状态的原料批次, EXPIRED",
            "查询质检中的原料批次, INSPECTING",
            "检验中的批次有哪些, INSPECTING",
            "看看预留的原料批次, RESERVED",
            "原料批次状态为USED_UP, USED_UP",
            "查询 expired 的批次, EXPIRED",
    })
    void theStatusSaidInTheSentenceFiltersTheBatches(String sentence, MaterialBatchStatus expected) throws Exception {
        Map<String, Object> data = ask(sentence);

        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(SEEDED.get(expected).longValue());
        assertThat(statuses(data)).isNotEmpty().containsOnly(expected.name());
        assertThat(data.get("queryConditions")).isEqualTo(Map.of("status", expected.name()));
        // 状态是在工具里确定性认出来的, 没有为此多调一次大模型
        verify(llm, never()).chatWithTools(anyString(), anyString(), anyList());
    }

    @Test
    void noStatusInTheSentenceMeansAllStatuses() throws Exception {
        Map<String, Object> data = ask("查询原料批次");

        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(TOTAL);
        assertThat(data.get("queryConditions")).isEqualTo(Map.of());
    }

    @Test
    void aStatusTheUserNamedButWeCannotMapIsSaidNotDropped() throws Exception {
        IntentExecuteResponse response = run("查询冻结状态的原料批次");

        assertThat(response.getStatus()).isNotEqualTo("SUCCESS");
        assertThat(String.valueOf(response.getMessage())).contains("冻结").contains("没有按状态查询");
    }

    @Test
    void expiringSoonIsNotReadAsExpired() throws Exception {
        IntentExecuteResponse response = run("查询即将过期的原料批次");

        assertThat(response.getStatus()).isNotEqualTo("SUCCESS");
        assertThat(String.valueOf(response.getMessage())).contains("即将过期");
    }

    private Map<String, Object> ask(String sentence) throws Exception {
        IntentExecuteResponse response = run(sentence);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.convertValue(response.getResultData(), Map.class);
        return data;
    }

    private IntentExecuteResponse run(String sentence) throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY")
                .intentCategory("MATERIAL").intentName("原料批次查询").toolName("material_batch_query").build();
        return dispatch.executeWithTool(tool, F, IntentExecuteRequest.builder().userInput(sentence).build(),
                intent, 1L, "factory_super_admin", null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> statuses(Map<String, Object> data) {
        return ((List<Map<String, Object>>) data.get("content")).stream()
                .map(row -> String.valueOf(row.get("status"))).toList();
    }

    private static MaterialBatch batch(String factoryId, String id, MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId("MT-MBC-1");
        b.setWarehouseId("WH-MBC");
        b.setReceiptDate(LocalDate.now());
        b.setReceiptQuantity(new BigDecimal("10"));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(status);
        b.setCreatedBy(7L);
        return b;
    }
}
