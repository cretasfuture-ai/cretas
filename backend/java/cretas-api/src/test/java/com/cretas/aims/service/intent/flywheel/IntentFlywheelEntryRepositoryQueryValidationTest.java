package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.intent.FlywheelMasterDataRepository;
import com.cretas.aims.repository.intent.IntentFlywheelTurnRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.service.execution.MultiIntentExecutionService;
import com.cretas.aims.service.execution.SseStreamingService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图飞轮在真实 Hibernate 上、经产品真实的普通入口 {@link IntentExecutorServiceImpl#execute} / {@code confirm}:
 * 每轮一条 {@code ai_intent_flywheel_turns}; 保密厂只出脱敏导出 (本厂客户表确定性匹配), 原文只以密文留; 保密关的厂同一句
 * 原样 (阳性对照); 下一轮回填上一轮的反应 (点了第几个候选); 确认入口按 commandDigest 回填 CONFIRMED。
 * 识别层由编排器桩模拟 (在入口打开的作用域里记一次识别结果, 与 AIIntentServiceImpl 的真实挂点同一个方法)。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class IntentFlywheelEntryRepositoryQueryValidationTest {

    static final String SECRET = "F-FW-SECRET";
    static final String PLAIN = "F-FW-PLAIN";
    static final String SENTENCE = "给老张家下五十斤卤鸭腿，按特价走";

    @Autowired TestEntityManager em;
    @Autowired IntentFlywheelTurnRepository turns;
    @Autowired FlywheelMasterDataRepository masterData;
    @Autowired FactoryNameDirectoryRepository people;
    @Autowired FactoryFeatureConfigRepository features;
    @Autowired FactorySecrecySettingRepository secrecySettings;

    IntentExecutionOrchestrator orchestrator;
    IntentExecutorServiceImpl entry;
    IntentFlywheelRecorder recorder;
    FlywheelTextProtector protector;
    final AtomicReference<IntentMatchResult> recognition = new AtomicReference<>();
    final AtomicReference<IntentExecuteResponse> answer = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Long userId = null;
        for (String f : List.of(SECRET, PLAIN)) {
            Factory factory = new Factory();
            factory.setId(f);
            factory.setName("flywheel " + f);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            em.persist(factory);
            User user = new User();
            user.setFactoryId(f);
            user.setUsername("flywheel-" + f);
            user.setPasswordHash("test-only");
            user.setIsActive(true);
            em.persist(user);
            userId = user.getId();
            Customer c = new Customer();
            c.setId(f + "-C1");
            c.setFactoryId(f);
            c.setCode(f + "-C1");
            c.setCustomerCode(f + "-C1");
            c.setName("老张家卤味店");
            c.setIsActive(true);
            c.setCreatedBy(userId);
            em.persist(c);
            ProductType p = new ProductType();
            p.setId(f + "-P1");
            p.setFactoryId(f);
            p.setCode(f + "-P1");
            p.setName("卤鸭腿");
            p.setUnit("斤");
            p.setDailyCapacity(new BigDecimal("100"));
            p.setDailyCapacityUnit("斤");
            em.persist(p);
        }
        // 保密厂: 只开出外脱敏 (c); 保密关的厂没有任何行 (默认全关)
        em.persist(FactorySecrecySetting.builder().factoryId(SECRET).itemCode(SecrecyItem.EGRESS_REDACTION.name())
                .enabled(true).updatedAt(LocalDateTime.now()).updatedBy(1L).build());
        em.flush();

        protector = new FlywheelTextProtector(masterData, people, features, FlywheelTextProtectorRedactionTest.key());
        protector.setFactorySecrecyConfig(new FactorySecrecyConfig(secrecySettings, null));
        recorder = new IntentFlywheelRecorder(turns, protector, new ObjectMapper());
        recorder.setSynchronous(true);

        orchestrator = mock(IntentExecutionOrchestrator.class);
        when(orchestrator.execute(anyString(), any(IntentExecuteRequest.class), any(), any())).thenAnswer(inv -> {
            IntentFlywheelTurnContext.noteStage("PYTHON_MATCH");
            IntentFlywheelTurnContext.noteRecognition(recognition.get());
            return answer.get();
        });
        when(orchestrator.confirm(anyString(), any(ConfirmationProof.class), any(), any()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());
        entry = new IntentExecutorServiceImpl(orchestrator, mock(SseStreamingService.class),
                mock(MultiIntentExecutionService.class), mock(ConversationMemoryService.class));
        ReflectionTestUtils.setField(entry, "flywheelRecorder", recorder);
    }

    @Test
    void secretFactoryTurnIsRecordedRedactedWithRawOnlyEncryptedAndPlainFactoryKeepsTheSentence() {
        recognition.set(clarify());
        answer.set(IntentExecuteResponse.builder().status("NEED_CLARIFICATION").sessionId("sess-1").build());

        entry.execute(SECRET, request(SENTENCE, null, "sess-1"), 7L, "sales");
        entry.execute(PLAIN, request(SENTENCE, null, "sess-1"), 7L, "sales");

        IntentFlywheelTurn secret = only(SECRET);
        assertThat(secret.getSecrecyMode()).isEqualTo(IntentFlywheelTurn.SecrecyMode.REDACTED);
        assertThat(secret.getUserInput()).isNull();
        assertThat(secret.getUserInputExport()).isEqualTo("给客户A下五十斤卤鸭腿，按特价走");
        assertThat(protector.decrypt(secret.getUserInputCipher())).isEqualTo(SENTENCE);
        assertThat(secret.getRedactionJson()).contains("\"customer\":1");
        assertThat(secret.getEntry()).isEqualTo(IntentFlywheelTurn.Entry.NORMAL);
        assertThat(secret.getDecisionSource()).isEqualTo("PYTHON_UNTRACED");
        assertThat(secret.getDecisionAction()).isEqualTo(IntentFlywheelTurn.Action.SHOW);
        assertThat(secret.getCandidatesJson()).contains("ORDER_CREATE").contains("ORDER_QUERY");
        assertThat(secret.getCatalogVersion()).startsWith("fic-sha256:");

        IntentFlywheelTurn plain = only(PLAIN);
        assertThat(plain.getSecrecyMode()).isEqualTo(IntentFlywheelTurn.SecrecyMode.PLAIN);
        assertThat(plain.getUserInput()).isEqualTo(SENTENCE);
        assertThat(plain.getUserInputExport()).isEqualTo(SENTENCE);
        assertThat(plain.getUserInputCipher()).isNull();
        assertThat(recorder.recordedCount()).isEqualTo(2);
    }

    @Test
    void nextTurnButtonPickFillsThePreviousTurnsReactionWithTheOptionRank() {
        recognition.set(clarify());
        answer.set(IntentExecuteResponse.builder().status("NEED_CLARIFICATION").build());
        entry.execute(PLAIN, request("帮我弄一下订单", null, "sess-2"), 7L, "sales");

        recognition.set(null);
        answer.set(IntentExecuteResponse.builder().status("COMPLETED").intentCode("ORDER_QUERY").build());
        entry.execute(PLAIN, request("帮我弄一下订单", "ORDER_QUERY", "sess-2"), 7L, "sales");

        List<IntentFlywheelTurn> rows = turns.findAll().stream()
                .filter(t -> PLAIN.equals(t.getFactoryId())).sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getReaction()).isEqualTo(IntentFlywheelTurn.Reaction.OPTION_SELECTED);
        assertThat(rows.get(0).getReactionOptionRank()).isEqualTo(2);
        assertThat(rows.get(0).getReactionTurnId()).isEqualTo(rows.get(1).getTurnId());
        assertThat(rows.get(1).getInputKind()).isEqualTo(IntentFlywheelTurn.InputKind.BUTTON);
        assertThat(rows.get(1).getDecisionSource()).isEqualTo("BUTTON");
        assertThat(rows.get(1).getReaction()).isNull();   // 还没有下一轮: 不判
    }

    @Test
    void confirmEntryMarksTheTurnThatShowedTheCardAsConfirmed() {
        recognition.set(clarify());
        answer.set(IntentExecuteResponse.builder().status("WRITE_CONFIRM_REQUIRED")
                .confirmableAction(IntentExecuteResponse.ConfirmableAction.builder().commandDigest("dg-42").build()).build());
        entry.execute(SECRET, request(SENTENCE, null, "sess-3"), 7L, "sales");

        entry.confirm(SECRET, new ConfirmationProof("tok", "dg-42", Instant.now().plusSeconds(60)), 7L, "sales");

        IntentFlywheelTurn row = only(SECRET);
        assertThat(row.getConfirmDigest()).isEqualTo("dg-42");
        assertThat(row.getReaction()).isEqualTo(IntentFlywheelTurn.Reaction.CONFIRMED);
        assertThat(row.getReactedAt()).isNotNull();
    }

    @Test
    void recorderFailureNeverChangesTheAnswerButIsCounted() {
        IntentFlywheelTurnRepository broken = mock(IntentFlywheelTurnRepository.class);
        when(broken.save(any())).thenThrow(new IllegalStateException("db down"));
        IntentFlywheelRecorder failing = new IntentFlywheelRecorder(broken, protector, new ObjectMapper());
        failing.setSynchronous(true);
        ReflectionTestUtils.setField(entry, "flywheelRecorder", failing);
        recognition.set(clarify());
        IntentExecuteResponse expected = IntentExecuteResponse.builder().status("NEED_CLARIFICATION").build();
        answer.set(expected);

        IntentExecuteResponse got = entry.execute(PLAIN, request(SENTENCE, null, "sess-4"), 7L, "sales");

        assertThat(got).isSameAs(expected);
        assertThat(failing.failedCount()).isEqualTo(1);
        assertThat(failing.recordedCount()).isZero();
    }

    private IntentFlywheelTurn only(String factoryId) {
        List<IntentFlywheelTurn> rows = turns.findAll().stream().filter(t -> factoryId.equals(t.getFactoryId())).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static IntentExecuteRequest request(String text, String intentCode, String sessionId) {
        IntentExecuteRequest r = new IntentExecuteRequest();
        r.setUserInput(text);
        r.setIntentCode(intentCode);
        r.setSessionId(sessionId);
        return r;
    }

    private static IntentMatchResult clarify() {
        AIIntentConfig best = new AIIntentConfig();
        best.setIntentCode("ORDER_CREATE");
        return IntentMatchResult.builder()
                .bestMatch(best).confidence(0.55).requiresConfirmation(true)
                .topCandidates(List.of(
                        IntentMatchResult.CandidateIntent.builder().intentCode("ORDER_CREATE").intentName("新建订单").confidence(0.55).build(),
                        IntentMatchResult.CandidateIntent.builder().intentCode("ORDER_QUERY").intentName("查订单").confidence(0.30).build()))
                .build();
    }
}
