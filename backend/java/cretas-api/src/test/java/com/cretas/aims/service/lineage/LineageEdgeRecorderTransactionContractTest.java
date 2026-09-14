package com.cretas.aims.service.lineage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 血缘边写入的<b>事务语义</b>契约 —— 真 H2 + 真事务管理器，⛔ 不是 Mockito。
 *
 * <h2>为什么必须用真事务，Mockito 顶不上</h2>
 *
 * <p>{@link BatchLineageEdge} 的主键是<b>应用侧赋值</b>的 UUID（{@code @PrePersist}），
 * 没有 {@code @GeneratedValue}。Hibernate 对 assigned 主键的 persist 不需要立刻回表拿键，
 * 于是 INSERT 被放进 action queue、推迟到下一个 flush 边界。
 *
 * <p>后果：{@code save()} 外面那圈 {@code try/catch} <b>结构性地捕不到真正的 DB 失败</b>。
 * 而 {@code when(repo.save(...)).thenThrow(...)} 这种 mock 让 save 同步抛异常 ——
 * 与真实 Hibernate 行为相反，它让那个 catch 看起来是有效的。
 * <b>那个绿色的单测证明的只是「如果 save 同步抛，catch 能接住」，
 * 恰恰不是「真实数据库错误会被吞掉」。</b>
 *
 * <p>所以这两条断言只能在真 DB + 真事务上写：
 * <ol>
 *   <li><b>调用方回滚 ⇒ 边表 0 行</b>（边是 append-only forensic，
 *       写错了删不掉，绝不能在调用方回滚后留下一条假边）；</li>
 *   <li><b>边插入必然失败 ⇒ 调用方照常提交成功</b>（fail-soft 真的兑现）。</li>
 * </ol>
 *
 * <p>⚠️ 每条阴性断言（「边表 0 行」）都配了一条<b>必发生</b>的读数（硬约束 9）——
 * 否则「0 行」在「recorder 根本没被调到」时同样成立，是个恒真式。
 */
@DataJpaTest(properties = {
        // 独立的内存库: ① 与共享 testdb 隔开(本类会 deleteAll)；
        // ② JSONB 映成 VARCHAR —— H2 的原生 JSON 类型读回来是**双重编码**的字符串,
        //    hypersistence 会在反序列化时炸 ("cannot be transformed to Json object")。
        //    这不是被测行为，是 H2 与 PG 的类型差异，别让它污染事务语义的读数。
        "spring.datasource.url=jdbc:h2:mem:lineage_tx_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                + ";DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// 只扫血缘那两张表 + BaseEntity(@MappedSuperclass) —— 扫全量 entity 包会让 ddl-auto=create
// 在几十个实体的重名索引上刷一屏错误，把真正的失败埋掉。
@EntityScan(basePackageClasses = {com.cretas.aims.entity.lineage.BatchLineageEdge.class,
        com.cretas.aims.entity.BaseEntity.class})
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.lineage")
@Import({LineageEdgeRecorder.class, LineageEdgeWriter.class, FinishedBatchProductionAligner.class,
        LineageEdgeRecorderTransactionContractTest.Stubs.class})
// 每个用例自己控事务边界 —— 测试方法本身绝不能开事务，否则就测不到「调用方提交/回滚」这件事。
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("血缘边事务语义 — 真 H2 + 真事务")
class LineageEdgeRecorderTransactionContractTest {

    private static final String FACTORY = "F-TX-1";

    /** 本测试不走 by-plan 对齐，给 aligner 一个桩仓库即可（⛔ 不为它拉起整个 repository 包）。 */
    @TestConfiguration
    static class Stubs {
        @Bean
        ProductionBatchRepository productionBatchRepository() {
            return Mockito.mock(ProductionBatchRepository.class);
        }
    }

    @Autowired private LineageEdgeRecorder recorder;
    @Autowired private BatchLineageEdgeRepository edgeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    private TransactionTemplate tx;
    private ListAppender<ILoggingEvent> appender;
    private Logger writerLogger;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // ⚠️ ⛔ 不能用 repository.deleteAll(): BatchLineageEdge 的 @PreRemove 显式禁止物理删除
        //    (append-only forensic)。用 JPQL 批量删除绕过实体生命周期回调 —— 这是测试清场，
        //    不是产品路径；产品里那道禁删守卫必须保持有效。
        tx.executeWithoutResult(status ->
                entityManager.createQuery("DELETE FROM BatchLineageEdge").executeUpdate());
        writerLogger = (Logger) LoggerFactory.getLogger(LineageEdgeWriter.class);
        appender = new ListAppender<>();
        appender.start();
        writerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        writerLogger.detachAppender(appender);
    }

    // ── ① 调用方提交 / 回滚 ──────────────────────────────────────────────────

    @Test
    @DisplayName("调用方事务提交 ⇒ 边落库（这是下一条断言的「必发生」对照）")
    void committedCallerTransactionPersistsTheEdge() {
        LineageRecordOutcome outcome = tx.execute(status -> recorder.recordRawToProduction(
                FACTORY, "mb-commit", 8967L, new BigDecimal("3"), "kg", 7L, "consumption:1", null));

        // 有活动事务 ⇒ 登记到 afterCommit，此刻还不知道结果。⛔ 不许折叠成 INSERTED。
        assertThat(outcome).isEqualTo(LineageRecordOutcome.SCHEDULED);
        // 而 execute() 返回时 afterCommit 已经跑完 —— 边真的在库里。
        assertThat(edgeRepository.findAll()).hasSize(1);
        assertThat(edgeRepository.findAll().get(0).getSourceId()).isEqualTo("mb-commit");
    }

    @Test
    @DisplayName("🔴 调用方事务回滚 ⇒ 边表 0 行（append-only 表里绝不能留假边）")
    void rolledBackCallerTransactionLeavesNoEdge() {
        tx.execute(status -> {
            recorder.recordRawToProduction(
                    FACTORY, "mb-rollback", 8967L, new BigDecimal("3"), "kg", 7L, "consumption:2", null);
            status.setRollbackOnly();
            return null;
        });

        assertThat(edgeRepository.findAll())
                .as("调用方回滚了, 这条边描述的事实从未发生 —— 它一旦落库就再也删不掉")
                .isEmpty();
    }

    // ── ② 边写失败不拖垮调用方 ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 边插入必然失败（factoryId 撞列宽）⇒ 调用方照常提交、0 行、有 warn")
    void failingEdgeInsertDoesNotBreakTheCallerTransaction() {
        // factory_id 是 VARCHAR(50)，给它 200 个字符 ⇒ 这条 INSERT 在任何输入下都不可能成功。
        String tooLongFactoryId = "F".repeat(200);

        assertThatCode(() -> tx.execute(status -> recorder.recordRawToProduction(
                tooLongFactoryId, "mb-boom", 8967L, new BigDecimal("3"), "kg", 7L, "consumption:3", null)))
                .as("血缘边是审计旁路, 写不进去绝不能把确认发货/报工整单打挂")
                .doesNotThrowAnyException();

        assertThat(edgeRepository.findAll()).isEmpty();
        // 「必发生」的那半：fail-soft ≠ 静默。失败必须在日志里留下痕迹，
        // 否则「0 行」和「压根没跑」分不开。
        assertThat(warnMessages())
                .as("fail-soft 吞掉异常, 但必须 warn —— 不然这条边是怎么没的谁也不知道")
                .anyMatch(m -> m.contains("[lineage]"));
    }

    @Test
    @DisplayName("无活动事务（回填路径）⇒ 同步写并返回确定结果, 不是 SCHEDULED")
    void withoutActiveTransactionTheWriteIsSynchronous() {
        LineageRecordOutcome first = recorder.recordRawToProduction(
                FACTORY, "mb-sync", 8967L, new BigDecimal("3"), "kg", 7L, "consumption:4", null);

        // 回填靠这个返回值报「新增 N / 已存在 M」—— 拿到 SCHEDULED 就等于读数作废。
        assertThat(first).isEqualTo(LineageRecordOutcome.INSERTED);
        assertThat(first.decided()).isTrue();
        assertThat(edgeRepository.findAll()).hasSize(1);

        // 同一 refKey 再来一次 ⇒ 幂等（真库上验，不是 mock 的 in-memory list）
        LineageRecordOutcome second = recorder.recordRawToProduction(
                FACTORY, "mb-sync", 8967L, new BigDecimal("3"), "kg", 7L, "consumption:4", null);
        assertThat(second).isEqualTo(LineageRecordOutcome.ALREADY_EXISTS);
        assertThat(edgeRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("dry-run preview 在真库上只读：不写行，且判据与真跑一致")
    void previewReadsWithoutWriting() {
        assertThat(recorder.previewRawToProduction(FACTORY, "mb-preview", 8967L,
                new BigDecimal("3"), "consumption:5"))
                .isEqualTo(LineageRecordOutcome.INSERTED);
        assertThat(edgeRepository.findAll()).as("preview 必须零写").isEmpty();

        recorder.recordRawToProduction(FACTORY, "mb-preview", 8967L,
                new BigDecimal("3"), "kg", 7L, "consumption:5", null);

        // 真跑之后，同一条 preview 必须翻成 ALREADY_EXISTS —— 这是「dry-run 与真跑同口径」的判据。
        assertThat(recorder.previewRawToProduction(FACTORY, "mb-preview", 8967L,
                new BigDecimal("3"), "consumption:5"))
                .isEqualTo(LineageRecordOutcome.ALREADY_EXISTS);
    }

    private List<String> warnMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
