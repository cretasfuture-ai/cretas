package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesException;
import com.cretas.aims.service.inventory.OverReceiveLimit;
import com.cretas.aims.service.inventory.SalesExceptionService;
import com.cretas.aims.service.inventory.impl.SalesExceptionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SalesExceptionRepository} 的启动校验 + <b>事务边界的运行时证明</b>。
 *
 * <h1>🔴 这个类补的正是 Mockito 证明不了的那一格</h1>
 * {@code SalesExceptionServiceImplTest} 只能断言 {@code REQUIRES_NEW}
 * <b>标</b>在一个可代理的 public 方法上（结构断言）。
 * 那排除不掉「事务管理器没配对」「代理没生效」这类运行时问题。
 *
 * <p>本类用<b>真实 H2 + 真实事务管理器 + 真实 Spring 代理</b>把它跑一遍:
 * <ol>
 *   <li>开一个外层事务 → 在里面调留痕 → 外层<b>抛异常回滚</b></li>
 *   <li>另开一个事务去查 —— 那一行<b>还在</b> ⇒ 独立事务真的提交了</li>
 *   <li><b>阴性对照</b>：同一个外层事务里<b>直接</b> {@code saveAndFlush}（不过 REQUIRES_NEW），
 *       回滚后<b>查不到</b> ⇒ 证明「查得到」不是因为回滚根本没发生，
 *       也不是因为这个读法总能看见未提交的数据</li>
 * </ol>
 *
 * <p>⚠️ 没有第 3 条，第 2 条就是恒真的 —— 「回滚后还在」和「回滚压根没生效」
 * 在读数上长得一模一样。
 *
 * <h2>native SQL 也只有在这里才被真的执行过一次</h2>
 * {@code findHighestNumberForPrefix} 是 native SQL，Hibernate 启动时<b>不校验</b>它，
 * 单测里它又是被 mock 掉的 ⇒ 语法错要到生产才炸。{@link Numbering} 真跑一次。
 *
 * <h2>⚠️ 本类会往共享 H2 里留下真数据</h2>
 * {@code @DataJpaTest} 的测试事务会回滚，但 {@code REQUIRES_NEW} 提交的那一行不会 ——
 * <b>那正是被测的性质</b>。所以每个用例用<b>随机 factoryId</b>互不干扰，
 * 并在 {@link #cleanUp()} 里另开事务物理删除。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(SalesExceptionServiceImpl.class)
@DisplayName("SalesExceptionRepository 启动校验 + REQUIRES_NEW 运行时证明")
class SalesExceptionRepositoryQueryValidationTest {

    @Autowired private SalesExceptionRepository repository;
    @Autowired private SalesExceptionService salesExceptionService;
    @Autowired private PlatformTransactionManager txManager;

    /** 每个用例一个独立工厂号 —— 本类留下的是真数据，⛔ 不能互相看见。 */
    private final String factoryId = "FT-" + UUID.randomUUID().toString().substring(0, 8);

    /** 独立事务里跑一段读/写 —— 测试自身的事务会回滚，读不能跟着回滚走。 */
    private <T> T inNewTx(Supplier<T> body) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> body.get());
    }

    @AfterEach
    void cleanUp() {
        inNewTx(() -> {
            List<SalesException> rows = repository.findByFactoryIdOrderByAttemptedAtDesc(factoryId);
            repository.deleteAll(rows);   // 软删即可，本类的断言都按 factoryId 隔离
            return null;
        });
    }

    private SalesDeliveryRecord record() {
        SalesDeliveryRecord r = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(r, "id", "DLV-" + factoryId);
        r.setDeliveryNumber("DN-" + factoryId);
        r.setSalesOrderId("SO-" + factoryId);
        return r;
    }

    private SalesDeliveryItem item() {
        SalesDeliveryItem it = new SalesDeliveryItem();
        ReflectionTestUtils.setField(it, "id", 9L);
        it.setProductTypeId("PT-1");
        it.setProductName("test-product");
        it.setUnit("box");
        it.setDeliveredQuantity(new BigDecimal("2"));
        return it;
    }

    private void recordOnce() {
        salesExceptionService.recordBlockedOverShip(factoryId, record(), item(),
                new BigDecimal("5"), new BigDecimal("0.10"),
                OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB, new BigDecimal("2.20"), 77L);
    }

    private long rowsForThisFactory() {
        return inNewTx(() -> (long) repository.findByFactoryIdOrderByAttemptedAtDesc(factoryId).size());
    }

    // ==================================================================
    @Test
    @DisplayName("repository bean 起得来, 所有派生查询在启动时校验通过")
    void repositoryBoots() {
        assertThat(repository).isNotNull();
        assertThat(salesExceptionService).isNotNull();
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 事务边界的运行时证明")
    class TransactionBoundaryRuntime {

        @Test
        @DisplayName("🔴 外层事务回滚之后, 异常单【仍然在库里】—— 这是本次改动的承重命题")
        void survivesOuterRollback() {
            TransactionTemplate outer = new TransactionTemplate(txManager);
            outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            assertThatThrownBy(() -> outer.execute(status -> {
                recordOnce();
                // 模拟 assertWithinOverShipCap 抛的那个 409 —— 外层整体回滚
                throw new IllegalStateException("模拟: 超发上限 409, 本次发货确认整体回滚");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(rowsForThisFactory())
                    .as("🔴 REQUIRES_NEW 独立提交 ⇒ 外层回滚带不走它。"
                            + "若这里是 0, 说明生产上「谁想超发多少」这件事一个字都没留下, "
                            + "而且【不会有任何报错】告诉你")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("🔴 阴性对照: 同事务里直接写 → 回滚后【查不到】(证明上一条不是恒真)")
        void sameTransactionWriteDoesNotSurvive() {
            TransactionTemplate outer = new TransactionTemplate(txManager);
            outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            assertThatThrownBy(() -> outer.execute(status -> {
                SalesException se = new SalesException();
                se.setFactoryId(factoryId);
                se.setExceptionNumber("SE-SAME-TX-" + factoryId);
                se.setExceptionType(com.cretas.aims.entity.enums.SalesExceptionType.OVER_SHIP_BLOCKED);
                se.setDeliveryRecordId("DLV-X");
                se.setPlannedQuantity(new BigDecimal("2"));
                se.setAttemptedQuantity(new BigDecimal("5"));
                se.setLimitSource(OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
                se.setAttemptedAt(LocalDateTime.now());
                repository.saveAndFlush(se);           // ⛔ 没过 REQUIRES_NEW
                throw new IllegalStateException("模拟: 超发上限 409");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(rowsForThisFactory())
                    .as("🔴 没有这一条, 上一条「回滚后还在」可能只是因为回滚压根没生效 —— "
                            + "两种情况在读数上一模一样")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("阴性对照: 外层【不】回滚时当然也在 (排除「只有回滚才写得进去」这种荒谬解释)")
        void alsoPresentWhenOuterCommits() {
            TransactionTemplate outer = new TransactionTemplate(txManager);
            outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outer.execute(status -> {
                recordOnce();
                return null;
            });
            assertThat(rowsForThisFactory()).isEqualTo(1L);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 native 取号查询真跑一次 (Hibernate 启动【不】校验 native SQL)")
    class Numbering {

        @Test
        @DisplayName("空表时返回空; 写一条之后能读回它的号 —— 语法/LIMIT/LENGTH 在 H2 上都成立")
        void nativeQueryExecutes() {
            String prefix = "SE-" + factoryId.substring(0, Math.min(12, factoryId.length())) + "-";

            assertThat(inNewTx(() -> repository.findHighestNumberForPrefix(factoryId, prefix + "%")))
                    .as("空表 ⇒ 空结果")
                    .isEmpty();

            recordOnce();

            List<String> found = inNewTx(() ->
                    repository.findHighestNumberForPrefix(factoryId, prefix + "%"));
            assertThat(found)
                    .as("🔴 这条 native SQL 在单测里全程被 mock, 只有这里真的执行过 —— "
                            + "语法错会一路绿到生产")
                    .hasSize(1);
            assertThat(found.get(0)).startsWith(prefix).endsWith("0001");
        }

        @Test
        @DisplayName("连写两条 → 号递增到 0002 (真库 + 真唯一索引下的取号)")
        void secondRecordGetsNextNumber() {
            recordOnce();
            recordOnce();

            List<SalesException> rows =
                    inNewTx(() -> repository.findByFactoryIdOrderByAttemptedAtDesc(factoryId));
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(SalesException::getExceptionNumber)
                    .as("⛔ 两条同号会撞唯一索引 —— 那正是本仓 20 个坏发号器的症状")
                    .doesNotHaveDuplicates();
            assertThat(rows).extracting(SalesException::getExceptionNumber)
                    .anySatisfy(n -> assertThat(n).endsWith("0002"));
        }
    }
}
