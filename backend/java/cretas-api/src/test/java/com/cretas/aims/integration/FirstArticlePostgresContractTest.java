package com.cretas.aims.integration;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionView;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgePreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgeRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardPreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardView;
import com.cretas.aims.dto.quality.FirstArticleDtos.TaskRow;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Verdict;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy.Mode;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.quality.firstarticle.FirstArticleGate;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.Item;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ItemType;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ResultInput;
import com.cretas.aims.service.quality.firstarticle.FirstArticleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工序首检 —— 真 PostgreSQL + 真 Spring Data/Hibernate 上下文 (迁移 V20261115_51 原文建表, 含 CHECK 与部分唯一索引)。
 *
 * <ol>
 *   <li>迁移可重复执行; 实体映射与迁移列一致 (Hibernate 读写全部列)。</li>
 *   <li>标准: 预览不写库; 同一工序第二条启用标准 → 409 且数据库部分唯一索引兜底; 修订 revision + 1。</li>
 *   <li>工序首检表只列本厂、进行中、有启用标准的任务; 状态随送检 / 判定变化。</li>
 *   <li>判定: 系统算出不合格不能判合格; 不合格 → 作废 → 重新送检 → 合格。</li>
 *   <li>闸: 开关没行 = OFF 放行; BLOCK 首检没合格 → 409; 合格 → 放行; WARN 只给提示。</li>
 *   <li>阴性对照 (跨厂): A 厂读不到 / 判不了 / 作废不了 B 厂的首检单, 也给 B 厂的任务送不了检; 列表互不可见。</li>
 * </ol>
 *
 * <p>开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL}; 本机跑法 {@code scripts/ci/run-with-test-postgres.sh}。
 */
@SpringBootTest(classes = FirstArticlePostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstArticlePostgresContractTest {

    static final String F = "FA-PG";
    static final String OTHER = "FA-PG-OTHER";
    static final Long USER = 30001L;
    static final String MIGRATION = "db/flyway/V20261115_51__process_first_article_inspection.sql";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({FirstArticleService.class, FirstArticleGate.class})
    static class Runtime {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FirstArticleService service;
    @Autowired FirstArticleGate gate;
    @Autowired FirstArticleInspectionRepository inspections;
    @Autowired FirstArticleStandardRepository standards;
    @Autowired FirstArticlePolicyRepository policies;
    @Autowired WorkProcessTaskRepository tasks;
    @Autowired PlatformTransactionManager txManager;

    private <T> T tx(Supplier<T> body) {
        return new TransactionTemplate(txManager).execute(s -> body.get());
    }

    @BeforeAll
    void schema() throws Exception {
        for (String t : new String[]{"first_article_inspections", "first_article_standards", "first_article_policies"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + t + " CASCADE");
        }
        String migration = migration(MIGRATION);
        jdbc.execute(migration);
        jdbc.execute(migration); // 可重复执行
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        workProcess("FA-WP-SEAL", F, "封口");
        workProcess("FA-WP-CUT", F, "切块");
        workProcess("FA-WP-SEAL-O", OTHER, "封口");
        task(9101, F, "FA-WP-SEAL", "IN_PROGRESS");   // 要首检, 进行中
        task(9102, F, "FA-WP-SEAL", "PENDING");       // 要首检, 还没开工
        task(9103, F, "FA-WP-CUT", "IN_PROGRESS");    // 工序没标准
        task(9201, OTHER, "FA-WP-SEAL-O", "IN_PROGRESS");
    }

    private void workProcess(String id, String factory, String name) {
        jdbc.update("insert into work_processes (id, factory_id, process_name, unit, lock_version, "
                + "default_output_material_kind, created_at, updated_at) values (?,?,?,'kg',0,'SEMI_FINISHED',now(),now())",
                id, factory, name);
    }

    private void task(long id, String factory, String wp, String status) {
        jdbc.update("insert into work_process_tasks (id, factory_id, production_batch_id, work_process_id, "
                + "product_type_id, process_order, status, is_outsourced, created_at, updated_at) "
                + "values (?, ?, 88001, ?, 'FA-PROD', 1, ?, false, now(), now())", id, factory, wp, status);
    }

    static List<Item> sealItems() {
        return List.of(
                new Item("weight", "块重", ItemType.NUMERIC, true, "g", new BigDecimal("180"), new BigDecimal("220"),
                        new BigDecimal("200"), null, null),
                new Item("seal", "封口", ItemType.OPTION, true, null, null, null, null,
                        List.of("平整", "轻微褶皱", "漏气"), List.of("平整", "轻微褶皱")));
    }

    static JudgeRequest judge(String weight, String seal, Verdict verdict, String notes) {
        return new JudgeRequest(List.of(new ResultInput("weight", new BigDecimal(weight), null),
                new ResultInput("seal", null, seal)), verdict, notes);
    }

    WorkProcessTask taskEntity(String factory, long id) {
        return tasks.findByFactoryIdAndId(factory, id).orElseThrow();
    }

    // ==================== 1. 迁移 + 默认关 ====================

    @Test
    @Order(1)
    void migrationShapeAndDefaultIsOff() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = current_schema()"
                + " and table_name in ('first_article_policies','first_article_standards','first_article_inspections')",
                Integer.class)).isEqualTo(3);
        // 没有开关行 = OFF: 闸放行, 不给提示
        assertThat(policies.findModeByFactoryId(F)).isNull();
        assertThat(gate.modeOf(F)).isEqualTo(Mode.OFF);
        // CHECK: 开关只能是三档之一
        assertThatThrownBy(() -> jdbc.update("insert into first_article_policies(factory_id, mode) values ('X','MAYBE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== 2. 标准 ====================

    @Test
    @Order(2)
    void standardPreviewSaveReviseAndOneActivePerProcess() {
        StandardRequest req = new StandardRequest(null, "FA-WP-SEAL", "封口首检", sealItems());
        StandardPreview p = tx(() -> service.previewStandard(F, req));
        assertThat(p.action()).isEqualTo("CREATE");
        assertThat(p.processName()).isEqualTo("封口");
        assertThat(p.ruleTexts()).containsExactly("180 ~ 220 g", "合格: 平整 / 轻微褶皱");
        assertThat(jdbc.queryForObject("select count(*) from first_article_standards", Integer.class))
                .as("预览不写库").isZero();

        StandardView saved = tx(() -> service.saveStandard(F, req, USER));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.items()).hasSize(2);
        assertThatThrownBy(() -> tx(() -> service.saveStandard(F, req, USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经有一条启用的首检标准");
        // 数据库兜底: 绕过服务直接插第二条启用标准 → 部分唯一索引拒
        assertThatThrownBy(() -> jdbc.update("insert into first_article_standards(factory_id, work_process_id, name,"
                + " items_json) values (?, 'FA-WP-SEAL', 'dup', '[]')", F))
                .isInstanceOf(DataIntegrityViolationException.class);

        StandardView revised = tx(() -> service.saveStandard(F,
                new StandardRequest(saved.id(), null, "封口首检 v2", sealItems()), USER));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.name()).isEqualTo("封口首检 v2");

        // 跨厂: B 厂改不了 A 厂的标准, 也看不到
        assertThatThrownBy(() -> tx(() -> service.saveStandard(OTHER,
                new StandardRequest(saved.id(), null, "x", sealItems()), USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(tx(() -> service.listStandards(OTHER, true))).isEmpty();
        // B 厂给自己工序建标准互不影响; 选 A 厂的工序 → 工序不存在
        assertThatThrownBy(() -> tx(() -> service.saveStandard(OTHER,
                new StandardRequest(null, "FA-WP-CUT", "x", sealItems()), USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        tx(() -> service.saveStandard(OTHER, new StandardRequest(null, "FA-WP-SEAL-O", "B 厂封口", sealItems()), USER));
    }

    // ==================== 3. 工序首检表 + 送检 + 判定 + 闸 ====================

    @Test
    @Order(3)
    void taskListRequestJudgeAndGate() {
        PageResponse<TaskRow> rows = tx(() -> service.listTasks(F, true, 1, 20));
        assertThat(rows.getContent()).extracting(TaskRow::taskId).containsExactly(9101L);
        assertThat(rows.getContent().get(0).firstArticleStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(rows.getContent().get(0).processName()).isEqualTo("封口");
        assertThat(tx(() -> service.listTasks(F, false, 1, 20)).getContent()).extracting(TaskRow::taskId)
                .containsExactlyInAnyOrder(9101L, 9102L);

        // 闸: OFF → 放行; BLOCK 且没送检 → 409
        tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; });
        assertThat(tx(() -> gate.noticeFor(F, taskEntity(F, 9101)))).isNull();
        tx(() -> service.updatePolicy(F, Mode.BLOCK, USER));
        assertThat(gate.modeOf(F)).isEqualTo(Mode.BLOCK);
        assertThat(gate.modeOf(OTHER)).as("开关按厂").isEqualTo(Mode.OFF);
        assertThatThrownBy(() -> tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; }))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(FirstArticleGate.CODE));
        // 没标准的工序不受影响
        tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9103)); return null; });

        // 送检: 还没开工 / 没标准 → 409
        assertThatThrownBy(() -> tx(() -> service.requestInspection(F, new InspectionRequest(9102L, null, null, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有进行中的工序");
        assertThatThrownBy(() -> tx(() -> service.requestInspection(F, new InspectionRequest(9103L, null, null, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有启用的首检标准");
        InspectionView first = tx(() -> service.requestInspection(F,
                new InspectionRequest(9101L, new BigDecimal("3"), "盒", "换品种首件"), USER));
        assertThat(first.status()).isEqualTo(FirstArticleInspection.Status.PENDING);
        assertThat(first.standardRevision()).isEqualTo(2);
        assertThatThrownBy(() -> tx(() -> service.requestInspection(F, new InspectionRequest(9101L, null, null, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经送检");
        // 数据库兜底: 同一任务第二张 PENDING → 部分唯一索引拒
        assertThatThrownBy(() -> jdbc.update("insert into first_article_inspections(factory_id, work_process_task_id,"
                + " production_batch_id, work_process_id, standard_id, standard_revision, standard_name,"
                + " standard_items_json, status, requested_at) values (?, 9101, 88001, 'FA-WP-SEAL', 1, 1, 'x', '[]',"
                + " 'PENDING', now())", F))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tx(() -> service.listTasks(F, true, 1, 20)).getContent().get(0).firstArticleStatus()).isEqualTo("PENDING");

        // 判定预览: 块重 230 超上限 → 系统判不合格, 不能判合格, 不写库
        JudgePreview bad = tx(() -> service.previewJudge(F, first.id(), judge("230", "平整", Verdict.PASSED, null)));
        assertThat(bad.autoVerdict()).isEqualTo(Verdict.FAILED);
        assertThat(bad.failedItems()).containsExactly("块重");
        assertThat(bad.canSubmit()).isFalse();
        assertThatThrownBy(() -> tx(() -> service.judge(F, first.id(), judge("230", "平整", Verdict.PASSED, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能判首检合格");
        assertThat(jdbc.queryForObject("select status from first_article_inspections where id = ?", String.class, first.id()))
                .isEqualTo("PENDING");
        // 必检项缺值 → 400
        assertThatThrownBy(() -> tx(() -> service.judge(F, first.id(),
                new JudgeRequest(List.of(new ResultInput("weight", new BigDecimal("200"), null)), Verdict.PASSED, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("封口");

        InspectionView failed = tx(() -> service.judge(F, first.id(), judge("230", "平整", Verdict.FAILED, null), USER));
        assertThat(failed.status()).isEqualTo(FirstArticleInspection.Status.FAILED);
        assertThat(failed.results()).hasSize(2);
        assertThat(failed.results().get(0).pass()).isFalse();
        assertThat(failed.results().get(1).pass()).isTrue();
        assertThatThrownBy(() -> tx(() -> service.judge(F, first.id(), judge("200", "平整", Verdict.PASSED, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能再判定");
        assertThatThrownBy(() -> tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; }))
                .hasMessageContaining("首检不合格");

        // 返工后再送检 → 合格 → 闸放行
        InspectionView second = tx(() -> service.requestInspection(F, new InspectionRequest(9101L, null, null, null), USER));
        InspectionView passed = tx(() -> service.judge(F, second.id(), judge("205", "轻微褶皱", Verdict.PASSED, null), USER));
        assertThat(passed.status()).isEqualTo(FirstArticleInspection.Status.PASSED);
        assertThat(passed.autoVerdict()).isEqualTo(Verdict.PASSED);
        tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; });
        assertThatThrownBy(() -> tx(() -> service.requestInspection(F, new InspectionRequest(9101L, null, null, null), USER)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经合格");

        // 作废合格单 → 首检状态回到上一张 (不合格) → BLOCK 又拦; WARN 只提示
        tx(() -> service.voidInspection(F, passed.id(), "录错块重", USER));
        assertThat(tx(() -> service.listTasks(F, true, 1, 20)).getContent().get(0).firstArticleStatus()).isEqualTo("FAILED");
        assertThatThrownBy(() -> tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; }))
                .isInstanceOf(BusinessException.class);
        tx(() -> service.updatePolicy(F, Mode.WARN, USER));
        tx(() -> { gate.assertCanComplete(F, taskEntity(F, 9101)); return null; });
        assertThat(tx(() -> gate.noticeFor(F, taskEntity(F, 9101)))).contains("首检不合格").contains("只提示");

        // 服务端过滤 (CAST 空参数在 PG 上能跑): 全部 / 按状态 / 按任务
        assertThat(inspections.search(F, null, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        assertThat(inspections.search(F, "VOID", null, PageRequest.of(0, 20)).getContent())
                .extracting(FirstArticleInspection::getId).containsExactly(passed.id());
        assertThat(inspections.search(F, null, 9101L, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    // ==================== 4. 阴性对照: 跨厂 ====================

    @Test
    @Order(4)
    void otherFactoryCannotReadJudgeOrVoidAndListsAreIsolated() {
        Long aId = inspections.search(F, "FAILED", null, PageRequest.of(0, 1)).getContent().get(0).getId();
        // B 厂给自己的任务送检
        InspectionView bInspection = tx(() -> service.requestInspection(OTHER,
                new InspectionRequest(9201L, null, null, null), USER));

        assertThatThrownBy(() -> tx(() -> service.getInspection(OTHER, aId))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx(() -> service.judge(OTHER, aId, judge("200", "平整", Verdict.PASSED, null), USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx(() -> service.voidInspection(OTHER, aId, "x", USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx(() -> service.requestInspection(OTHER, new InspectionRequest(9101L, null, null, null), USER)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx(() -> service.previewJudge(F, bInspection.id(), judge("200", "平整", Verdict.PASSED, null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(inspections.findByFactoryIdAndId(OTHER, aId)).isEmpty();
        assertThat(inspections.findEffectiveForTasks(OTHER, List.of(9101L))).isEmpty();

        assertThat(tx(() -> service.listInspections(OTHER, null, null, 1, 50)).getContent())
                .extracting(InspectionView::id).containsExactly(bInspection.id());
        assertThat(tx(() -> service.listInspections(F, null, null, 1, 50)).getContent())
                .extracting(InspectionView::id).doesNotContain(bInspection.id()).isNotEmpty();
        assertThat(tx(() -> service.listTasks(OTHER, true, 1, 20)).getContent())
                .extracting(TaskRow::taskId).containsExactly(9201L);
        // A 厂原单没被 B 厂的尝试改动
        assertThat(jdbc.queryForObject("select status from first_article_inspections where id = ?", String.class, aId))
                .isEqualTo("FAILED");
    }

    private static String migration(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
