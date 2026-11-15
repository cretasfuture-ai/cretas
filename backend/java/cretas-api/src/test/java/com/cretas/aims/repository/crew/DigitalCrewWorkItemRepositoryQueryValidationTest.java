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
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.crew.DigitalCrewService.Actor;
import com.cretas.aims.service.execution.RealPermissionOrderOrchestratorFixture;
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
@EntityScan(basePackages = "com.cretas.aims.entity.crew")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository", includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {DigitalCrewWorkItemRepository.class, DigitalCrewMemberSettingRepository.class,
                DigitalCrewRoleMappingRepository.class, DigitalCrewSettingLogRepository.class,
                DigitalCrewDelegationLockRepository.class}))
@Import(DigitalCrewService.class)
class DigitalCrewWorkItemRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean hasUrl = url != null && !url.isBlank();
        boolean verify = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
        if (hasUrl != verify) {
            throw new IllegalStateException("CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        if (hasUrl) {
            String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
            String bound = safe.contains("stringtype=") ? safe : safe + (safe.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            // PG 上用迁移脚本本身建表 (带 CHECK / 部分唯一索引), 不用 ddl-auto: 守的是「服务写入在迁移建出来的表上过得去」
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
            registry.add("spring.sql.init.mode", () -> "always");
            registry.add("spring.sql.init.schema-locations", () ->
                    "classpath:db/crew-test/reset-digital-crew.sql,classpath:db/flyway/V20261115_61__digital_crew.sql");
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
        // 铸出来的凭据都能读回行 id (读不回来的情形单独测: fail closed)
        when(previewTokens.readBoundTokenId(anyString(), eq(F), anyLong())).thenReturn(Optional.of(501L));
        when(previewTokens.executingStaleMinutes()).thenReturn(10L);
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

    // ==================== #1771 审查修复 ====================

    /**
     * 中-1: 没写权限的人 (操作员) 说写操作 ⇒ 真编排器先查权限、回 NO_PERMISSION (不打桩先后顺序) ⇒
     * 数字班组照样交办进等我确认; 确认仍只限有权限的人。读意图被拒则不交办。
     */
    @Test
    void operatorWithoutWritePermissionStillDelegatesThroughTheRealPermissionOrder() {
        RealPermissionOrderOrchestratorFixture real = new RealPermissionOrderOrchestratorFixture(
                (userId, codes) -> userId != 83L);
        AIIntentConfig plan = intentConfigs.getIntentByCode(F, "PRODUCTION_PLAN_CREATE").orElseThrow();
        real.register(plan);
        AIIntentConfig planList = new AIIntentConfig();
        planList.setIntentCode("PRODUCTION_PLAN_LIST");
        planList.setIntentName("查生产计划");
        planList.setRequiredPermission("production:read");
        planList.setSensitivityLevel("LOW");
        real.register(planList);
        when(intentConfigs.getIntentByCode(F, "PRODUCTION_PLAN_LIST")).thenReturn(Optional.of(planList));
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenAnswer(inv -> real.execute(
                inv.getArgument(1), "PRODUCTION_PLAN_CREATE", inv.getArgument(2), inv.getArgument(3)));

        DigitalCrewService.AskView v = service.ask(F, OPERATOR, "planner", "明天加一个 500kg 的卤牛肉计划", null);
        assertThat(v.status()).as("真编排器对没写权限的人先回权限拒绝").isEqualTo("NO_PERMISSION");
        assertThat(v.kind()).isEqualTo("DELEGATED");
        DigitalCrewWorkItem w = reread(v.item().id());
        assertThat(w.getStatus()).isEqualTo(Status.DELEGATED);
        assertThat(w.getQuotaCost()).isZero();
        assertThat(w.getCreatedByUserId()).isEqualTo(OPERATOR.id());
        assertThatThrownBy(() -> service.preview(F, OPERATOR, w.getId())).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThat(service.inbox(F, BOSS).mine()).extracting(DigitalCrewService.ItemView::id).containsExactly(w.getId());

        // 有写权限的人说同一类话: 真编排器在 READ 模式拦写 ⇒ 也交办
        DigitalCrewService.AskView mine = service.ask(F, PLANNER, "planner", "后天加一个 300kg 的卤牛肉计划", null);
        assertThat(mine.status()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(mine.kind()).isEqualTo("DELEGATED");

        // 阴性对照: 读意图被权限拒绝 (操作员没有任何码) ⇒ 不交办, 原样回答
        RealPermissionOrderOrchestratorFixture denyAll = new RealPermissionOrderOrchestratorFixture((u, c) -> false);
        denyAll.register(planList);
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenAnswer(inv -> denyAll.execute(
                inv.getArgument(1), "PRODUCTION_PLAN_LIST", inv.getArgument(2), inv.getArgument(3)));
        long before = items.count();
        DigitalCrewService.AskView read = service.ask(F, OPERATOR, "planner", "看看这周的生产计划", null);
        assertThat(read.status()).isEqualTo("NO_PERMISSION");
        assertThat(read.kind()).isEqualTo("ANSWER");
        assertThat(items.count()).isEqualTo(before);
    }

    /** 低-2: 写操作登记的是读级权限码 ⇒ 不交办 (否则有读权限的人就能确认)。 */
    @Test
    void writeIntentWithReadLevelPermissionIsRefusedNotDelegated() {
        AIIntentConfig odd = new AIIntentConfig();
        odd.setIntentCode("PRODUCTION_PLAN_UPDATE");
        odd.setIntentName("改生产计划");
        odd.setRequiredPermission("production:read");
        when(intentConfigs.getIntentByCode(F, "PRODUCTION_PLAN_UPDATE")).thenReturn(Optional.of(odd));
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenReturn(IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode("PRODUCTION_PLAN_UPDATE").status("READ_MODE_WRITE_BLOCKED").build());
        DigitalCrewService.AskView v = service.ask(F, PLANNER, "planner", "把明天的计划改成 400kg", null);
        assertThat(v.kind()).isEqualTo("REFUSED");
        assertThat(items.count()).isZero();
    }

    private Long stuckConfirming(long minutesAgo) {
        DigitalCrewWorkItem w = new DigitalCrewWorkItem();
        w.setFactoryId(F);
        w.setMemberKey("planner");
        w.setIntentCode("PRODUCTION_PLAN_CREATE");
        w.setTitle("新建生产计划 · 卡住的");
        w.setUserInput("卡住的 " + minutesAgo);
        w.setRequiredPermission("production:read_write");
        w.setModule("production");
        w.setStatus(Status.CONFIRMING);
        w.setCreatedByUserId(PLANNER.id());
        w.setCreatedByName("用户81");
        w.setPreviewedByUserId(BOSS.id());
        w.setCommandDigest(DIGEST);
        w.setPreviewTokenId(501L);
        LocalDateTime at = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(minutesAgo);
        w.setPreviewedAt(at);
        w.setConfirmedByUserId(BOSS.id());
        w.setConfirmedByName("用户82");
        w.setConfirmedAt(at);
        return items.save(w).getId();
    }

    /** 中-2: 「办理中」卡住 (确认请求断了) ⇒ 读的时候对账: 凭据已确认 ⇒ 已办成扣 1。 */
    @Test
    void stuckConfirmingWithConfirmedTokenIsReconciledToDone() {
        Long id = stuckConfirming(20);
        Long fresh = stuckConfirming(1);
        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.of(TokenStatus.CONFIRMED));
        DigitalCrewService.LedgerView ledger = service.ledger(F, BOSS, null);
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.DONE);
        assertThat(w.getQuotaCost()).isEqualTo(1);
        assertThat(w.getResolvedDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(ledger.quotaTotal()).isEqualTo(1);
        assertThat(reread(fresh).getStatus()).as("没超时的不动").isEqualTo(Status.CONFIRMING);
    }

    /** 中-2: 卡住且凭据确定没写入 ⇒ 回到已交办 (清预览, 可重新预览); 读不到凭据 ⇒ 结果不明, 不扣。 */
    @Test
    void stuckConfirmingWithoutWriteGoesBackAndUnreadableIsOutcomeUnknown() {
        Long expired = stuckConfirming(30);
        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.of(TokenStatus.EXPIRED));
        service.inbox(F, BOSS);
        DigitalCrewWorkItem back = reread(expired);
        assertThat(back.getStatus()).isEqualTo(Status.DELEGATED);
        assertThat(back.getCommandDigest()).isNull();
        assertThat(back.getPreviewTokenId()).isNull();
        assertThat(back.getConfirmedByUserId()).isNull();
        assertThat(service.inbox(F, BOSS).mine()).extracting(DigitalCrewService.ItemView::id).contains(expired);

        Long unreadable = stuckConfirming(40);
        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.empty());
        service.ledger(F, BOSS, null);
        DigitalCrewWorkItem unknown = reread(unreadable);
        assertThat(unknown.getStatus()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(unknown.getQuotaCost()).isZero();
        assertThat(unknown.getResolvedDate()).isNotNull();
        assertThat(unknown.getResultNote()).contains("写入可能已经提交");
    }

    private Long delegatedAndPreviewedByBoss() {
        Long id = delegateOne();
        previewMints(DIGEST);
        when(previewTokens.readBoundTokenId("proof-token-1", F, BOSS.id())).thenReturn(Optional.of(501L));
        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.of(TokenStatus.PENDING));
        service.preview(F, BOSS, id);
        assertThat(reread(id).getPreviewTokenId()).isEqualTo(501L);
        return id;
    }

    /**
     * 中-3: 在数字班组预览后, 拿同一张凭据去通用 AI 写入确认接口执行了 ⇒ 再点「不办」被拒 (409),
     * 事项按回读结果回写为已办成、扣 1 —— 不会被记成「没办成」。
     */
    @Test
    void tokenConfirmedThroughTheGenericEndpointCannotBeRejectedAndIsWrittenBack() {
        Long id = delegatedAndPreviewedByBoss();
        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.of(TokenStatus.CONFIRMED));
        assertThatThrownBy(() -> service.reject(F, BOSS, id, "这周排满了")).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.DONE);
        assertThat(w.getQuotaCost()).isEqualTo(1);
        assertThat(w.getResultStatus()).isEqualTo("CONFIRMED_ELSEWHERE");
        assertThat(w.getConfirmedByUserId()).isEqualTo(BOSS.id());
        assertThat(service.ledger(F, BOSS, null).quotaTotal()).isEqualTo(1);
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
    }

    /** 中-3: 凭据在别处被拿去确认、结果还在途 ⇒ 读的时候回写结果不明, 等我确认里不再出现; 换一张别的凭据来确认 ⇒ 409。 */
    @Test
    void tokenInFlightElsewhereBecomesOutcomeUnknownAndForeignProofIsRefused() {
        Long id = delegatedAndPreviewedByBoss();
        when(previewTokens.readBoundTokenId("someone-elses-token", F, BOSS.id())).thenReturn(Optional.of(777L));
        assertThatThrownBy(() -> service.confirm(F, BOSS, id,
                new ConfirmationProof("someone-elses-token", DIGEST, Instant.now().plusSeconds(300))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        assertThat(reread(id).getStatus()).isEqualTo(Status.DELEGATED);

        when(previewTokens.readBoundStatusById(501L, F, BOSS.id())).thenReturn(Optional.of(TokenStatus.EXECUTING));
        assertThat(service.inbox(F, BOSS).mine()).isEmpty();
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(w.getQuotaCost()).isZero();
    }

    /** 中-4: 额度在库里按 factory_id 聚合 —— 超过 2000 件不截断, 不同查看人 (模块读权限不同) 看到同一个数。 */
    @Test
    void quotaIsAggregatedInTheDatabaseAndSameForEveryViewer() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<DigitalCrewWorkItem> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 2003; i++) {
            boolean sales = i < 2;
            DigitalCrewWorkItem w = new DigitalCrewWorkItem();
            w.setFactoryId(F);
            w.setMemberKey(sales ? "clerk" : "planner");
            w.setIntentCode(sales ? "ORDER_CREATE" : "PRODUCTION_PLAN_CREATE");
            w.setTitle("t" + i);
            w.setUserInput("u" + i);
            w.setRequiredPermission(sales ? "sales:read_write" : "production:read_write");
            w.setModule(sales ? "sales" : "production");
            w.setStatus(Status.DONE);
            w.setQuotaCost(1);
            w.setCreatedByUserId(BOSS.id());
            w.setCreatedByName("用户82");
            w.setConfirmedByUserId(BOSS.id());
            w.setConfirmedAt(now);
            w.setResolvedAt(now);
            w.setResolvedDate(now.toLocalDate());
            rows.add(w);
        }
        items.saveAll(rows);
        DigitalCrewService.LedgerView boss = service.ledger(F, BOSS, null);
        DigitalCrewService.LedgerView operator = service.ledger(F, OPERATOR, null);
        assertThat(boss.quotaTotal()).isEqualTo(2003);
        assertThat(boss.quotaToday()).isEqualTo(2003);
        assertThat(boss.quotaThisMonth()).isEqualTo(2003);
        assertThat(operator.quotaTotal()).as("只有生产读权限的人看到的额度和厂长一样").isEqualTo(2003);
        assertThat(operator.quotaThisMonth()).isEqualTo(2003);
        assertThat(boss.done()).isEqualTo(2003);
        assertThat(service.ledger(F, OPERATOR, "clerk").quotaTotal()).isEqualTo(2);
        assertThat(service.ledger(F, BOSS, "clerk").quotaToday()).isEqualTo(2);
        // 明细仍按查看人的读权限过滤 (原话里可能有别的模块的业务内容)
        assertThat(service.ledger(F, OPERATOR, "clerk").records()).isEmpty();
    }

    // ==================== #1771 复审修复 ====================

    /** 每个人预览拿到自己的凭据: tok-<userId>, 行 id = 500 + (userId - 80)。 */
    private void previewMintsPerUser() {
        when(intents.preview(eq(F), any(), anyLong(), anyString())).thenAnswer(inv -> {
            Long uid = inv.getArgument(2);
            IntentExecuteResponse.ConfirmableAction action = new IntentExecuteResponse.ConfirmableAction();
            action.setConfirmToken("tok-" + uid);
            action.setCommandDigest(DIGEST);
            action.setExpiresAt(Instant.now().plusSeconds(300));
            return IntentExecuteResponse.builder().status("PREVIEW").confirmableAction(action).build();
        });
        for (Actor a : List.of(PLANNER, BOSS)) {
            when(previewTokens.readBoundTokenId("tok-" + a.id(), F, a.id())).thenReturn(Optional.of(500L + (a.id() - 80)));
        }
    }

    /**
     * 中-A: A (计划员) 预览后 B (厂长) 再预览 ⇒ 先按行 id 作废 A 那张凭据 (属主 = A), 再给 B 铸新的;
     * 事项改记 B 的凭据。A 那张被作废后在任何入口都认领不到 (真查询见 IntentPreviewTokenCancelByIdRepositoryQueryValidationTest (H2 + PG))。
     */
    @Test
    void secondPreviewerRetiresTheFirstPreviewersTokenBeforeMintingANewOne() {
        Long id = delegateOne();
        previewMintsPerUser();
        service.preview(F, PLANNER, id);
        assertThat(reread(id).getPreviewTokenId()).isEqualTo(501L);
        when(previewTokens.cancelPendingById(eq(501L), eq(F), eq(PLANNER.id()), anyString())).thenReturn(true);

        service.preview(F, BOSS, id);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(previewTokens, intents);
        order.verify(intents).preview(eq(F), any(), eq(PLANNER.id()), anyString());
        order.verify(previewTokens).cancelPendingById(eq(501L), eq(F), eq(PLANNER.id()), anyString());
        order.verify(intents).preview(eq(F), any(), eq(BOSS.id()), anyString());
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getPreviewTokenId()).isEqualTo(502L);
        assertThat(w.getPreviewedByUserId()).isEqualTo(BOSS.id());
        assertThat(w.getStatus()).isEqualTo(Status.DELEGATED);
    }

    /**
     * 中-A: A 预览后, 抢在 B 之前拿自己的凭据去通用确认入口执行了 (作废不掉, 回读已确认) ⇒ B 的预览被拒 409,
     * 事项回写已办成扣 1, 不给 B 铸新凭据 —— 不会出现第二次写入。
     */
    @Test
    void firstPreviewersTokenUsedElsewhereBlocksTheSecondPreviewAndIsWrittenBack() {
        Long id = delegateOne();
        previewMintsPerUser();
        service.preview(F, PLANNER, id);
        when(previewTokens.cancelPendingById(eq(501L), eq(F), eq(PLANNER.id()), anyString())).thenReturn(false);
        when(previewTokens.readBoundStatusById(501L, F, PLANNER.id())).thenReturn(Optional.of(TokenStatus.CONFIRMED));

        assertThatThrownBy(() -> service.preview(F, BOSS, id)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));

        verify(intents, never()).preview(eq(F), any(), eq(BOSS.id()), anyString());
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.DONE);
        assertThat(w.getQuotaCost()).isEqualTo(1);
        assertThat(w.getConfirmedByUserId()).isEqualTo(PLANNER.id());
        assertThatThrownBy(() -> service.confirm(F, BOSS, id, proof())).isInstanceOf(BusinessException.class);
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
    }

    /** 低-C: 刚铸的凭据读不回行 id ⇒ 作废它、502, 事项不记这次预览 (不 fail-open)。 */
    @Test
    void previewWhoseTokenCannotBeRegisteredFailsClosed() {
        Long id = delegateOne();
        previewMints(DIGEST);
        when(previewTokens.readBoundTokenId("proof-token-1", F, BOSS.id())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(F, BOSS, id)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(502));
        verify(previewTokens).cancelToken(eq("proof-token-1"), eq(F), eq(BOSS.id()), anyString());
        assertThat(reread(id).getCommandDigest()).isNull();
        assertThat(reread(id).getPreviewTokenId()).isNull();
    }

    private static final String OTHER_FACTORY = "F-CREW-OTHER";

    private DigitalCrewWorkItem openItem(Actor creator, String input, String permission, String module) {
        return openItem(F, creator, input, permission, module);
    }

    private DigitalCrewWorkItem openItem(String factoryId, Actor creator, String input, String permission, String module) {
        DigitalCrewWorkItem w = new DigitalCrewWorkItem();
        w.setFactoryId(factoryId);
        w.setMemberKey("production".equals(module) ? "planner" : "clerk");
        w.setIntentCode("X_CREATE");
        w.setTitle("t · " + input);
        w.setUserInput(input);
        w.setRequiredPermission(permission);
        w.setModule(module);
        w.setStatus(Status.DELEGATED);
        w.setCreatedByUserId(creator.id());
        w.setCreatedByName("用户" + creator.id());
        return w;
    }

    /** 中-B: 每人挂着的「已交办」到上限 ⇒ 不再交办 (409 + 提示); 同一句话的重复交办仍返回先建的那件。 */
    @Test
    void openDelegationsArePerUserCapped() {
        List<DigitalCrewWorkItem> rows = new java.util.ArrayList<>();
        for (int i = 0; i < DigitalCrewService.OPEN_DELEGATIONS_PER_USER; i++) {
            rows.add(openItem(PLANNER, "第 " + i + " 件", "production:read_write", "production"));
        }
        rows.get(0).setUserInput("明天加一个 500kg 的卤牛肉计划");
        items.saveAll(rows);
        // 跨厂对照: 同一个人在别的厂也挂满了 —— 那边的件数不能算进本厂, 本厂的也不能算进那边
        List<DigitalCrewWorkItem> elsewhere = new java.util.ArrayList<>();
        for (int i = 0; i < DigitalCrewService.OPEN_DELEGATIONS_PER_USER; i++) {
            elsewhere.add(openItem(OTHER_FACTORY, BOSS, "别厂 " + i, "production:read_write", "production"));
        }
        items.saveAll(elsewhere);
        writeIntentRecognized();

        DigitalCrewService.AskView again = service.ask(F, PLANNER, "planner", "明天加一个 500kg 的卤牛肉计划", null);
        assertThat(again.kind()).isEqualTo("DELEGATED");
        assertThat(again.item().id()).isEqualTo(rows.get(0).getId());

        assertThatThrownBy(() -> service.ask(F, PLANNER, "planner", "后天再加一个 800kg", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getActionHint()).contains("不办");
                });
        assertThat(items.count()).isEqualTo(2L * DigitalCrewService.OPEN_DELEGATIONS_PER_USER);
        // 别人不受我的上限影响; 厂长在别的厂挂满了 20 件, 本厂照样能交办 (上限按厂按人)
        assertThat(service.ask(F, BOSS, "planner", "后天再加一个 800kg", null).kind()).isEqualTo("DELEGATED");
    }

    /** 中-B: 等我确认在库里按「我能确认的码」分页和计数 —— 超过 200 件也不丢, 隐藏件数是总数。 */
    @Test
    void inboxIsPagedInTheDatabaseAndCountsEverything() {
        List<DigitalCrewWorkItem> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 230; i++) {
            rows.add(openItem(i % 2 == 0 ? OPERATOR : BOSS, "生产 " + i, "production:read_write", "production"));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(openItem(OPERATOR, "销售 " + i, "sales:read_write", "sales"));
        }
        // 跨厂对照: 别的厂的已交办 (我能确认的码 + 我不能确认的码各一批) 不能出现在本厂的列表、总数或隐藏件数里
        for (int i = 0; i < 40; i++) {
            rows.add(openItem(OTHER_FACTORY, OPERATOR, "别厂生产 " + i, "production:read_write", "production"));
        }
        for (int i = 0; i < 7; i++) {
            rows.add(openItem(OTHER_FACTORY, OPERATOR, "别厂质检 " + i, "quality:read_write", "quality"));
        }
        items.saveAll(rows);

        DigitalCrewService.InboxView first = service.inbox(F, PLANNER, 0, 100);
        assertThat(first.mine()).hasSize(100);
        assertThat(first.totalMine()).isEqualTo(230);
        assertThat(first.hiddenCount()).isEqualTo(3);
        assertThat(first.hiddenModules()).containsExactly("sales");
        DigitalCrewService.InboxView last = service.inbox(F, PLANNER, 2, 100);
        assertThat(last.mine()).hasSize(30);
        assertThat(last.mine()).allSatisfy(v -> assertThat(v.requiredPermission()).isEqualTo("production:read_write"));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int p = 0; p < 3; p++) {
            service.inbox(F, PLANNER, p, 100).mine().forEach(v -> seen.add(v.id()));
        }
        assertThat(seen).as("三页拼起来恰好是全部 230 件, 不重不漏").hasSize(230);
        assertThat(service.inbox(F, PLANNER, 0, 1000).size()).as("页大小有上限").isEqualTo(DigitalCrewService.INBOX_PAGE_SIZE_MAX);
        // 厂长什么都能确认: 没有隐藏
        assertThat(service.inbox(F, BOSS).hiddenCount()).isZero();
        assertThat(service.inbox(F, BOSS).totalMine()).isEqualTo(233);
    }

    /**
     * 低-A: 确认请求在途时, 对账已经把这件事定了结果 (这里模拟: 确认接口还没返回, 事项已被改成结果不明);
     * 迟到的确认结果不覆盖对账结论。
     */
    @Test
    void lateConfirmResultDoesNotOverwriteAReconciledOutcome() {
        Long id = delegateOne();
        previewMints(DIGEST);
        service.preview(F, BOSS, id);
        when(intents.confirm(eq(F), any(), eq(BOSS.id()), anyString())).thenAnswer(inv -> {
            DigitalCrewWorkItem w = reread(id);
            w.setStatus(Status.OUTCOME_UNKNOWN);
            w.setResultStatus("RECONCILED_UNREADABLE");
            w.setResolvedAt(LocalDateTime.now());
            w.setResolvedDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
            items.saveAndFlush(w);
            return IntentExecuteResponse.builder().status("SUCCESS").message("已创建").build();
        });
        tokenReadsBack(BOSS, TokenStatus.CONFIRMED);
        DigitalCrewService.ItemView v = service.confirm(F, BOSS, id, proof());
        assertThat(v.status()).isEqualTo("OUTCOME_UNKNOWN");
        DigitalCrewWorkItem w = reread(id);
        assertThat(w.getStatus()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(w.getResultStatus()).isEqualTo("RECONCILED_UNREADABLE");
        assertThat(w.getQuotaCost()).isZero();
    }

    /**
     * 中-C: 两个人几乎同时预览 (两边都还在等 LLM 预览时, 事项上都还没有凭据) ⇒ 先落库的登记上, 后落库的影响 0 行:
     * 当场作废自己刚铸的凭据、返回 409 —— 最终只有一张凭据是有效的, 不会有一张脱离追踪的 PENDING 凭据。
     */
    @Test
    void concurrentPreviewsLeaveExactlyOneLiveToken() throws Exception {
        Long id = delegateOne();
        previewMintsPerUser();
        java.util.concurrent.CyclicBarrier bothInPreview = new java.util.concurrent.CyclicBarrier(2);
        IntentExecuteResponse.ConfirmableAction[] minted = new IntentExecuteResponse.ConfirmableAction[2];
        when(intents.preview(eq(F), any(), anyLong(), anyString())).thenAnswer(inv -> {
            Long uid = inv.getArgument(2);
            bothInPreview.await(10, java.util.concurrent.TimeUnit.SECONDS); // 两边都已经过了「作废旧凭据」, 都在等预览
            IntentExecuteResponse.ConfirmableAction action = new IntentExecuteResponse.ConfirmableAction();
            action.setConfirmToken("tok-" + uid);
            action.setCommandDigest(DIGEST);
            action.setExpiresAt(Instant.now().plusSeconds(300));
            return IntentExecuteResponse.builder().status("PREVIEW").confirmableAction(action).build();
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (Actor a : List.of(PLANNER, BOSS)) {
                results.add(pool.submit(() -> {
                    try {
                        return service.preview(F, a, id);
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            int ok = 0;
            int conflicts = 0;
            for (java.util.concurrent.Future<Object> f : results) {
                Object r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof BusinessException be) {
                    assertThat(be.getCode()).isEqualTo(409);
                    conflicts++;
                } else {
                    ok++;
                }
            }
            assertThat(ok).as("恰好一个人的预览登记上").isEqualTo(1);
            assertThat(conflicts).as("另一个人 409").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        DigitalCrewWorkItem w = reread(id);
        Long winner = w.getPreviewedByUserId();
        Long loser = winner.equals(PLANNER.id()) ? BOSS.id() : PLANNER.id();
        assertThat(w.getPreviewTokenId()).isEqualTo(500L + (winner - 80));
        // 输的那一方: 自己刚铸的凭据被当场作废 (属主 = 自己), 不留一张没人追踪的 PENDING
        verify(previewTokens).cancelPendingById(eq(500L + (loser - 80)), eq(F), eq(loser), anyString());
        verify(previewTokens, never()).cancelPendingById(eq(500L + (winner - 80)), anyString(), anyLong(), anyString());
    }

    /** 低-1: 同一个人并发交办 (都已过识别、同时插入) ⇒ 交办锁让「数件数 → 插入」串行, 上限不会被冲破。 */
    @Test
    void concurrentAsksCannotBreakThePerUserCap() throws Exception {
        int cap = DigitalCrewService.OPEN_DELEGATIONS_PER_USER;
        List<DigitalCrewWorkItem> rows = new java.util.ArrayList<>();
        for (int i = 0; i < cap - 1; i++) {
            rows.add(openItem(PLANNER, "已有 " + i, "production:read_write", "production"));
        }
        items.saveAll(rows);
        int threads = 6;
        java.util.concurrent.CyclicBarrier allRecognized = new java.util.concurrent.CyclicBarrier(threads);
        when(intents.execute(eq(F), any(), anyLong(), anyString())).thenAnswer(inv -> {
            allRecognized.await(10, java.util.concurrent.TimeUnit.SECONDS); // 都过了识别, 一起去插入
            return IntentExecuteResponse.builder().intentRecognized(true).intentCode("PRODUCTION_PLAN_CREATE")
                    .intentName("新建生产计划").status("READ_MODE_WRITE_BLOCKED").build();
        });
        // 把「数件数 → 插入」之间的窗口放宽到 300ms: 有交办锁时这段等待在锁里, 各线程依次通过;
        // 没有锁时 6 个线程都会数到 19、都去插入 —— 变异 (去掉锁) 因此一定看得见 (第一版没有这段, 去掉锁也是绿的)。
        service.afterOpenCountHook = () -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        int delegated = 0;
        int refused = 0;
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String text = "并发第 " + i + " 件";
                results.add(pool.submit(() -> {
                    try {
                        return service.ask(F, PLANNER, "planner", text, null);
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            for (java.util.concurrent.Future<Object> f : results) {
                Object r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof BusinessException be) {
                    assertThat(be.getCode()).isEqualTo(409);
                    refused++;
                } else {
                    assertThat(((DigitalCrewService.AskView) r).kind()).isEqualTo("DELEGATED");
                    delegated++;
                }
            }
        } finally {
            pool.shutdownNow();
            service.afterOpenCountHook = () -> { };
        }
        assertThat(delegated).as("只剩 1 个名额, 只有 1 件交办成功").isEqualTo(1);
        assertThat(refused).isEqualTo(threads - 1);
        assertThat(items.countOpenByUser(F, PLANNER.id(), Status.DELEGATED)).isEqualTo(cap);
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
