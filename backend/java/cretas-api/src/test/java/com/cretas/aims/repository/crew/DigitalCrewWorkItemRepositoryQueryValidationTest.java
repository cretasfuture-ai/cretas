package com.cretas.aims.repository.crew;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem.Status;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.crew.DigitalCrewService.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移 P8「数字班组」在真实 JPA 映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑)。
 * AI 识别 / 预览 / 确认链路是桩 (那条路径自己的测试在 #1632), 这里守数字班组自己的四件事:
 *
 * <ul>
 *   <li>额度四态回读: 交办 0 → 确认且凭据回读 CONFIRMED 才已办成扣 1 (真实日期) / 不办记没办成 0 /
 *       确认抛异常记结果不明 0 / 接口说成功但凭据对不上也是结果不明 0。每条都清掉持久化上下文后从库里回读。</li>
 *   <li>服务端权限: 没有事项写权限的人调预览 / 确认 ⇒ 403, 确认链路一次都没被调用, 事项原样。</li>
 *   <li>凭据绑定: 别人预览的, 我拿着同样的摘要也认领不到 (不执行)。</li>
 *   <li>员工能力过滤: 意图模块不归这位员工管 ⇒ 不交办、不返回数据。</li>
 * </ul>
 * 事务不包住测试 (NOT_SUPPORTED): 服务自己按步提交, 回读的就是提交后的库。
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository", includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {DigitalCrewWorkItemRepository.class, DigitalCrewMemberSettingRepository.class,
                DigitalCrewRoleMappingRepository.class, DigitalCrewSettingLogRepository.class}))
@Import(DigitalCrewService.class)
class DigitalCrewWorkItemRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static final String F = "F-CREW-1";
    private static final String DIGEST = "a".repeat(64);
    private static final Actor PLANNER = new Actor(AuthenticatedActorType.FACTORY_USER, 81L, "dispatcher");
    private static final Actor BOSS = new Actor(AuthenticatedActorType.FACTORY_USER, 82L, "factory_super_admin");
    private static final Actor OPERATOR = new Actor(AuthenticatedActorType.FACTORY_USER, 83L, "operator");

    @Autowired private DigitalCrewService service;
    @Autowired private DigitalCrewWorkItemRepository items;
    @Autowired private DigitalCrewMemberSettingRepository memberSettings;
    @Autowired private DigitalCrewSettingLogRepository settingLogs;

    @MockBean private UserRepository users;
    @MockBean private PermissionService permissions;
    @MockBean private IntentExecutorService intents;
    @MockBean private AIIntentService intentConfigs;
    @MockBean private PreviewTokenService previewTokens;
    @MockBean private FactoryCapabilityPackSelector packSelector;
    @MockBean private FactoryBusinessTypeResolver businessTypes;

    @BeforeEach
    void setUp() {
        items.deleteAll();
        memberSettings.deleteAll();
        settingLogs.deleteAll();
        for (Actor a : List.of(PLANNER, BOSS, OPERATOR)) {
            User u = new User();
            u.setId(a.id());
            u.setFactoryId(F);
            u.setUsername("u" + a.id());
            u.setFullName("用户" + a.id());
            when(users.findById(a.id())).thenReturn(Optional.of(u));
        }
        // 计划员、厂长有生产写权限; 操作员没有
        when(permissions.hasPermission(any(User.class), anyString())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            String code = inv.getArgument(1);
            if (u.getId() == 83L) return false;
            return code.startsWith("production:") || u.getId() == 82L;
        });
        when(permissions.resolveModuleLevels(any(User.class))).thenAnswer(inv ->
                ((User) inv.getArgument(0)).getId() == 83L ? Map.of("production", "r") : Map.of("production", "rw", "system", "rw"));
        AIIntentConfig plan = new AIIntentConfig();
        plan.setIntentCode("PRODUCTION_PLAN_CREATE");
        plan.setIntentName("新建生产计划");
        plan.setRequiredPermission("production:read_write");
        when(intentConfigs.getIntentByCode(F, "PRODUCTION_PLAN_CREATE")).thenReturn(Optional.of(plan));
        AIIntentConfig sales = new AIIntentConfig();
        sales.setIntentCode("ORDER_LIST");
        sales.setIntentName("查销售订单");
        sales.setRequiredPermission("sales:read");
        when(intentConfigs.getIntentByCode(F, "ORDER_LIST")).thenReturn(Optional.of(sales));
    }

    private void writeIntentRecognized() {
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenReturn(IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode("PRODUCTION_PLAN_CREATE").intentName("新建生产计划")
                .status("READ_MODE_WRITE_BLOCKED").build());
    }

    private void previewMints(String digest) {
        IntentExecuteResponse.ConfirmableAction action = new IntentExecuteResponse.ConfirmableAction();
        action.setConfirmToken("proof-token-1");
        action.setCommandDigest(digest);
        action.setExpiresAt(Instant.now().plusSeconds(300));
        when(intents.preview(eq(F), any(), anyLong(), anyString())).thenReturn(IntentExecuteResponse.builder()
                .status("PREVIEW").confirmableAction(action).build());
    }

    private void tokenReadsBack(Actor who, TokenStatus status) {
        when(previewTokens.readBoundSnapshot("proof-token-1", F, who.id())).thenReturn(Optional.of(
                new PreviewTokenService.BoundTokenSnapshot("PRODUCTION_PLAN_CREATE", null, null, null, "plan_create",
                        "1", null, DIGEST, LocalDateTime.now().plusMinutes(5), status)));
    }

    private static ConfirmationProof proof() {
        return new ConfirmationProof("proof-token-1", DIGEST, Instant.now().plusSeconds(300));
    }

    private Long delegateOne() {
        writeIntentRecognized();
        DigitalCrewService.AskView v = service.ask(F, PLANNER, "planner", "明天加一个 500kg 的卤牛肉计划", null);
        assertThat(v.kind()).isEqualTo("DELEGATED");
        return v.item().id();
    }

    private DigitalCrewWorkItem reread(Long id) {
        return items.findByIdAndFactoryId(id, F).orElseThrow();
    }

    @Test
    void fourStates_delegatedDoesNotCharge_onlyReadBackConfirmedChargesOneOnTheRealDate() {
        Long id = delegateOne();
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.DELEGATED);
        assertThat(w.getQuotaCost()).isZero();
        assertThat(service.ledger(F, BOSS, null).quotaTotal()).isZero();

        previewMints(DIGEST);
        service.preview(F, BOSS, id);
        assertThat(reread(id).getCommandDigest()).isEqualTo(DIGEST);
        assertThat(reread(id).getPreviewedByUserId()).isEqualTo(BOSS.id());

        when(intents.confirm(eq(F), any(), eq(BOSS.id()), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").message("已创建").build());
        tokenReadsBack(BOSS, TokenStatus.CONFIRMED);
        service.confirm(F, BOSS, id, proof());

        DigitalCrewWorkItem done = reread(id);
        assertThat(done.getStatus()).isEqualTo(Status.DONE);
        assertThat(done.getQuotaCost()).isEqualTo(1);
        assertThat(done.getConfirmedByUserId()).isEqualTo(BOSS.id());
        assertThat(done.getConfirmedByName()).isEqualTo("用户82");
        assertThat(done.getResolvedDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(done.getEvidence()).contains("已确认执行");
        DigitalCrewService.LedgerView ledger = service.ledger(F, BOSS, null);
        assertThat(ledger.quotaToday()).isEqualTo(1);
        assertThat(ledger.done()).isEqualTo(1);
        assertThat(ledger.delegated()).isZero();
    }

    @Test
    void successResponseWithoutConfirmedTokenIsOutcomeUnknownAndChargesNothing() {
        Long id = delegateOne();
        previewMints(DIGEST);
        service.preview(F, BOSS, id);
        when(intents.confirm(eq(F), any(), eq(BOSS.id()), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").build());
        tokenReadsBack(BOSS, TokenStatus.EXECUTING);
        service.confirm(F, BOSS, id, proof());
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(w.getQuotaCost()).isZero();
    }

    @Test
    void injectedFailureDuringConfirmIsRecordedAsOutcomeUnknown() {
        Long id = delegateOne();
        previewMints(DIGEST);
        service.preview(F, BOSS, id);
        when(intents.confirm(eq(F), any(), eq(BOSS.id()), anyString()))
                .thenThrow(new IllegalStateException("连接在提交后断开 (注入)"));
        DigitalCrewService.ItemView v = service.confirm(F, BOSS, id, proof());
        assertThat(v.status()).isEqualTo("OUTCOME_UNKNOWN");
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(w.getQuotaCost()).isZero();
        assertThat(w.getResultNote()).contains("写入可能已经提交");
        assertThat(w.getResolvedDate()).isNotNull();
    }

    @Test
    void definiteFailureGoesBackToDelegatedWithoutCharge() {
        Long id = delegateOne();
        previewMints(DIGEST);
        service.preview(F, BOSS, id);
        when(intents.confirm(eq(F), any(), eq(BOSS.id()), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("PREVIEW_STALE").message("改前值已变化").build());
        tokenReadsBack(BOSS, TokenStatus.FAILED);
        service.confirm(F, BOSS, id, proof());
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.DELEGATED);
        assertThat(w.getQuotaCost()).isZero();
        assertThat(w.getCommandDigest()).isNull();
        assertThat(w.getResultNote()).contains("没有写入");
    }

    @Test
    void rejectIsNotDone_noCharge() {
        Long id = delegateOne();
        assertThatThrownBy(() -> service.reject(F, BOSS, id, " ")).isInstanceOf(BusinessException.class);
        service.reject(F, BOSS, id, "这周排满了");
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.NOT_DONE);
        assertThat(w.getQuotaCost()).isZero();
        assertThat(w.getResultNote()).contains("这周排满了");
        assertThat(service.ledger(F, BOSS, null).perMember().stream()
                .filter(m -> m.memberKey().equals("planner")).findFirst().orElseThrow().notDone()).isEqualTo(1);
    }

    @Test
    void serverRejectsConfirmFromSomeoneWithoutTheItemsWritePermission() {
        Long id = delegateOne();
        assertThatThrownBy(() -> service.preview(F, OPERATOR, id)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.confirm(F, OPERATOR, id, proof())).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(intents, never()).preview(any(), any(), anyLong(), anyString());
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        assertThat(reread(id).getStatus()).isEqualTo(Status.DELEGATED);
        // 等我确认: 操作员看不到这件, 只报件数
        DigitalCrewService.InboxView inbox = service.inbox(F, OPERATOR);
        assertThat(inbox.mine()).isEmpty();
        assertThat(inbox.hiddenCount()).isEqualTo(1);
        assertThat(service.inbox(F, BOSS).mine()).extracting(DigitalCrewService.ItemView::id).containsExactly(id);
    }

    @Test
    void aProofPreviewedBySomeoneElseCannotBeClaimed() {
        Long id = delegateOne();
        previewMints(DIGEST);
        service.preview(F, BOSS, id); // 厂长预览
        assertThatThrownBy(() -> service.confirm(F, PLANNER, id, proof())) // 计划员拿同样摘要来确认
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        assertThat(reread(id).getStatus()).isEqualTo(Status.DELEGATED);
    }

    @Test
    void intentOutsideTheMembersModulesIsNotDelegatedAndReturnsNoData() {
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenReturn(IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode("ORDER_LIST").intentName("查销售订单").status("SUCCESS")
                .formattedText("订单 A 100 元").build());
        DigitalCrewService.AskView v = service.ask(F, PLANNER, "planner", "这周的销售订单", null);
        assertThat(v.kind()).isEqualTo("OUT_OF_SCOPE");
        assertThat(v.ownerKey()).isEqualTo("clerk");
        assertThat(v.text()).doesNotContain("100 元");
        assertThat(items.count()).isZero();
    }

    @Test
    void disabledMemberRefusesAndSettingsChangeIsLoggedWithReason() {
        assertThatThrownBy(() -> service.updateMembers(F, BOSS, Map.of("planner", false), "")).isInstanceOf(BusinessException.class);
        service.updateMembers(F, BOSS, Map.of("planner", false), "本厂暂不用计划员");
        assertThat(settingLogs.findByFactoryIdOrderByCreatedAtDescIdDesc(F, org.springframework.data.domain.PageRequest.of(0, 5)))
                .hasSize(1).first().satisfies(l -> assertThat(l.getReason()).isEqualTo("本厂暂不用计划员"));
        assertThat(service.members(F, BOSS).stream().filter(m -> m.key().equals("planner")).findFirst().orElseThrow().enabled())
                .isFalse();
        writeIntentRecognized();
        assertThatThrownBy(() -> service.ask(F, PLANNER, "planner", "加个计划", null)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThat(items.count()).isZero();
    }
}
