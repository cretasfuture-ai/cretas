package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.SalesExceptionType;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesException;
import com.cretas.aims.repository.inventory.SalesExceptionRepository;
import com.cretas.aims.service.inventory.OverReceiveLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售异常单落库 —— 取号、字段、失败吞掉、以及事务边界的<b>结构</b>断言。
 *
 * <h1>🔴 先说清楚这个类能证明什么</h1>
 * <table border="1">
 *   <tr><th>命题</th><th>能证明吗</th><th>凭什么 / 为什么不能</th></tr>
 *   <tr><td>取号看的是「已发出的最大号」而不是「还剩几条」</td><td>✅</td>
 *       <td>{@link Numbering} 直接驱动取号，且 repository 桩返回被软删的号</td></tr>
 *   <tr><td>取号撞车会重试并拿到下一个号</td><td>✅</td><td>桩第一次抛约束冲突</td></tr>
 *   <tr><td>任何失败都被吞掉、返回 null、不抛给调用方</td><td>✅</td><td>桩抛异常</td></tr>
 *   <tr><td>{@code REQUIRES_NEW} <b>标</b>在一个跨 bean 可代理的 public 方法上</td><td>✅</td>
 *       <td>反射读注解 + Modifier。这是<b>结构</b>断言</td></tr>
 *   <tr><td>🔴 <b>外层事务回滚之后这一行真的还在库里</b></td><td>❌ <b>不能</b></td>
 *       <td>本类<b>没有数据库、没有事务管理器、没有 Spring 代理</b>。
 *           Mockito 桩<b>证明不了任何关于事务的事</b>。<br>
 *           结构断言能排除最常见的两种坏法（注解漏了 / 写成 private 自调用导致代理够不着），
 *           但排除不掉「事务管理器配置不对」「连接池只有一条连接」这类运行时问题。<br>
 *           要真证明它，需要一个带真实 DataSource + 事务管理器的集成测试
 *           （外层事务里调一次 → 外层抛异常回滚 → 另开事务查这一行还在不在）。
 *           <b>本轮没有写那个测试。</b></td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesExceptionServiceImplTest {

    @Mock SalesExceptionRepository repository;

    private SalesExceptionServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String TODAY =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String PREFIX = "SE-" + FACTORY_ID + "-" + TODAY + "-";

    @BeforeEach
    void setUp() {
        // ⚠️ 直接 new ⇒ self == null ⇒ proxy() 退回 this ⇒ **没有独立事务**。
        //    本类只测取号/字段/兜底逻辑；事务由下面的结构断言 + 真实 Spring 上下文负责。
        service = new SalesExceptionServiceImpl(repository);
        when(repository.saveAndFlush(any(SalesException.class))).thenAnswer(i -> i.getArgument(0));
    }

    private SalesDeliveryRecord record() {
        SalesDeliveryRecord r = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(r, "id", "DLV-1");
        r.setDeliveryNumber("DN-0001");
        r.setSalesOrderId("SO-1");
        return r;
    }

    private SalesDeliveryItem item() {
        SalesDeliveryItem it = new SalesDeliveryItem();
        ReflectionTestUtils.setField(it, "id", 9L);
        it.setProductTypeId("PT-1");
        it.setProductName("冷冻带鱼段");
        it.setUnit("盒");
        it.setDeliveredQuantity(new BigDecimal("2"));
        return it;
    }

    private SalesException record5At10Percent() {
        return service.recordBlockedOverShip(FACTORY_ID, record(), item(),
                new BigDecimal("5"), new BigDecimal("0.10"),
                OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB, new BigDecimal("2.20"), 77L);
    }

    // ==================================================================
    @Nested
    @DisplayName("落库的字段")
    class Fields {

        @Test
        @DisplayName("每一格都从入参落下来, 且类型是 OVER_SHIP_BLOCKED / 状态是 BLOCKED")
        void allFieldsPersisted() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString())).thenReturn(List.of());

            record5At10Percent();

            ArgumentCaptor<SalesException> cap = ArgumentCaptor.forClass(SalesException.class);
            verify(repository).saveAndFlush(cap.capture());
            SalesException se = cap.getValue();

            assertThat(se.getFactoryId()).isEqualTo(FACTORY_ID);
            assertThat(se.getExceptionType()).isEqualTo(SalesExceptionType.OVER_SHIP_BLOCKED);
            assertThat(se.getStatus()).isEqualTo(SalesException.STATUS_BLOCKED);
            assertThat(se.getDeliveryRecordId()).isEqualTo("DLV-1");
            assertThat(se.getDeliveryNumber())
                    .as("⛔ 不能只存 UUID —— 采购异常屏就是这么把 UUID 显示给用户的")
                    .isEqualTo("DN-0001");
            assertThat(se.getSalesOrderId()).isEqualTo("SO-1");
            assertThat(se.getDeliveryItemId()).isEqualTo(9L);
            assertThat(se.getProductName()).isEqualTo("冷冻带鱼段");
            assertThat(se.getUnit()).isEqualTo("盒");
            assertThat(se.getPlannedQuantity()).isEqualByComparingTo("2");
            assertThat(se.getAttemptedQuantity())
                    .as("这是【想发的】量, ⛔ 不是发出去的量 —— 这一单被拒绝了")
                    .isEqualByComparingTo("5");
            assertThat(se.getMaxAllowedQuantity()).isEqualByComparingTo("2.20");
            assertThat(se.getLimitRate()).isEqualByComparingTo("0.10");
            assertThat(se.getLimitSource()).isEqualTo(OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
            assertThat(se.getAttemptedBy()).isEqualTo(77L);
            assertThat(se.getAttemptedAt()).isNotNull();
        }

        @Test
        @DisplayName("阴性对照: 操作人为 null 时如实记 null, ⛔ 不兜底成 0 顶一个假用户")
        void nullUserStaysNull() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString())).thenReturn(List.of());

            service.recordBlockedOverShip(FACTORY_ID, record(), item(), new BigDecimal("5"),
                    new BigDecimal("0.10"), OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB,
                    new BigDecimal("2.20"), null);

            ArgumentCaptor<SalesException> cap = ArgumentCaptor.forClass(SalesException.class);
            verify(repository).saveAndFlush(cap.capture());
            assertThat(cap.getValue().getAttemptedBy()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 取号: 怎么绕开本仓的软删发号器陷阱")
    class Numbering {

        @Test
        @DisplayName("当天没有历史 → 0001")
        void firstOfTheDay() {
            when(repository.findHighestNumberForPrefix(eq(FACTORY_ID), eq(PREFIX + "%")))
                    .thenReturn(List.of());
            assertThat(service.nextExceptionNumber(FACTORY_ID)).isEqualTo(PREFIX + "0001");
        }

        @Test
        @DisplayName("有历史最大号 → 在它之上 +1")
        void incrementsFromHighest() {
            when(repository.findHighestNumberForPrefix(eq(FACTORY_ID), eq(PREFIX + "%")))
                    .thenReturn(List.of(PREFIX + "0007"));
            assertThat(service.nextExceptionNumber(FACTORY_ID)).isEqualTo(PREFIX + "0008");
        }

        /**
         * 🔴 本仓 57 个发号器里 20 个坏在这一条上。
         *
         * <p>坏法: 「这个号被占用了吗」被软删答成「有没有一行<b>活着的</b>记录用了它」。
         * 采购侧 {@code countPendingByFactoryId() + 1} 更进一步: 只要有一张异常单被决策成
         * RESOLVED, 计数就下降, 下一个号退回去撞上已存在的号。
         *
         * <p>本条的形状: 库里唯一那条 {@code -0009} <b>已被软删</b>。
         * 正确行为是仍然取 0010（号永不回收）。
         * 判据落在 repository 契约上 —— 那条查询是 native SQL（{@code @Where} 不作用于 native）,
         * 且唯一索引是全量的，两者一起保证「软删的行还占着号」。
         */
        @Test
        @DisplayName("🔴 历史最大号那一行【已被软删】→ 仍然 +1, 号永不回收")
        void softDeletedNumberStaysOccupied() {
            when(repository.findHighestNumberForPrefix(eq(FACTORY_ID), eq(PREFIX + "%")))
                    .thenReturn(List.of(PREFIX + "0009"));   // 这条在库里 deleted_at IS NOT NULL

            assertThat(service.nextExceptionNumber(FACTORY_ID))
                    .as("软删的号仍然占着 —— ⛔ 回收它会让历史行与新行同号, 追溯时指向两条记录")
                    .isEqualTo(PREFIX + "0010");
        }

        @Test
        @DisplayName("🔴 取号只调 findHighestNumberForPrefix, ⛔ 一次 count 都不许调")
        void neverCountsRows() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenReturn(List.of(PREFIX + "0003"));

            service.nextExceptionNumber(FACTORY_ID);

            verify(repository).findHighestNumberForPrefix(eq(FACTORY_ID), eq(PREFIX + "%"));
            verify(repository, times(0)).count();
            // ⚠️ 这条不是形式主义: 「有几条」在软删/状态变化下会【下降】,
            //    而单号只能单调递增。用计数取号 = 迟早撞号。
        }

        @Test
        @DisplayName("序号超过 9999 变五位 → 仍然 +1 (⛔ 字典序会把 10000 排在 9999 之前)")
        void fiveDigitSequence() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenReturn(List.of(PREFIX + "10000"));
            assertThat(service.nextExceptionNumber(FACTORY_ID)).isEqualTo(PREFIX + "10001");
        }

        @Test
        @DisplayName("历史号解析不出来 → 退回 0001 而不是崩 (唯一索引是最终裁判)")
        void unparseableHistory_fallsBack() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenReturn(List.of("SE-F006-脏数据"));
            assertThat(service.nextExceptionNumber(FACTORY_ID)).isEqualTo(PREFIX + "0001");
        }

        @Test
        @DisplayName("factoryId 过长时工厂段截断到 12 位 (单号列是 VARCHAR(50))")
        void longFactoryIdIsTruncated() {
            String longId = "F".repeat(60);
            when(repository.findHighestNumberForPrefix(anyString(), anyString())).thenReturn(List.of());
            String number = service.nextExceptionNumber(longId);
            assertThat(number).startsWith("SE-" + "F".repeat(12) + "-");
            assertThat(number.length())
                    .as("⛔ 超过 50 会被数据库截断或写失败")
                    .isLessThanOrEqualTo(50);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 撞车重试 与 「失败一律吞掉」")
    class FailureHandling {

        @Test
        @DisplayName("并发撞号 (第一次约束冲突) → 重试一次拿到下一个号")
        void collisionIsRetried() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenReturn(List.of(PREFIX + "0001"))    // 第一次: 别人也算出 0002
                    .thenReturn(List.of(PREFIX + "0002"));   // 重试: 别人已写进去, max 变了
            when(repository.saveAndFlush(any(SalesException.class)))
                    .thenThrow(new DataIntegrityViolationException("uidx_sales_exceptions_number"))
                    .thenAnswer(i -> i.getArgument(0));

            SalesException saved = record5At10Percent();

            assertThat(saved).isNotNull();
            assertThat(saved.getExceptionNumber()).isEqualTo(PREFIX + "0003");
            verify(repository, times(2)).saveAndFlush(any(SalesException.class));
        }

        @Test
        @DisplayName("🔴 连续撞车用尽重试 → 返回 null, ⛔ 不抛 (调用方那 409 不能变 500)")
        void exhaustedRetries_returnNullNotThrow() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenReturn(List.of(PREFIX + "0001"));
            when(repository.saveAndFlush(any(SalesException.class)))
                    .thenThrow(new DataIntegrityViolationException("uidx_sales_exceptions_number"));

            SalesException saved = record5At10Percent();

            assertThat(saved).isNull();
            verify(repository, times(3)).saveAndFlush(any(SalesException.class));
        }

        @Test
        @DisplayName("🔴 任何其它异常 (库挂了) → 吞掉返回 null, 不重试, 不抛")
        void arbitraryFailure_isSwallowed() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("模拟: 连不上库"));

            assertThatCode(() -> assertThat(record5At10Percent()).isNull())
                    .as("🔴 留痕失败绝不能改变「这次发货被拒绝」这个结果")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("阴性对照: 一切正常时【不】返回 null —— 否则上面三条「返回 null」是恒真的")
        void happyPathReturnsTheEntity() {
            when(repository.findHighestNumberForPrefix(anyString(), anyString())).thenReturn(List.of());
            assertThat(record5At10Percent())
                    .as("没有这条对照, 「失败返回 null」可能只是因为它永远返回 null")
                    .isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 事务边界 (结构断言 —— 读这个 Nested 之前先读类注释那张表)")
    class TransactionBoundaryStructure {

        private Method persistAttempt() throws NoSuchMethodException {
            return SalesExceptionServiceImpl.class.getDeclaredMethod("persistAttempt",
                    String.class, SalesDeliveryRecord.class, SalesDeliveryItem.class,
                    BigDecimal.class, BigDecimal.class, String.class, BigDecimal.class, Long.class);
        }

        @Test
        @DisplayName("落库方法标的是 REQUIRES_NEW —— 外层注定回滚, 同事务写什么都留不下来")
        void persistAttemptIsRequiresNew() throws Exception {
            Transactional tx = persistAttempt().getAnnotation(Transactional.class);
            assertThat(tx)
                    .as("没有 @Transactional ⇒ 会 join 外层那个注定回滚的事务, 留痕永远丢失")
                    .isNotNull();
            assertThat(tx.propagation())
                    .as("🔴 必须 REQUIRES_NEW: REQUIRED/SUPPORTS 都会跟着外层一起回滚, "
                            + "而且【不会有任何报错】告诉你它没留下")
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }

        @Test
        @DisplayName("🔴 落库方法必须是 public —— private 方法代理拦不到, 注解被静默忽略")
        void persistAttemptIsProxyable() throws Exception {
            assertThat(Modifier.isPublic(persistAttempt().getModifiers()))
                    .as("Spring 的 @Transactional 靠代理生效; private / final 方法上的 propagation "
                            + "一个字都不起作用, 而且不报错")
                    .isTrue();
            assertThat(Modifier.isFinal(persistAttempt().getModifiers())).isFalse();
        }

        @Test
        @DisplayName("🔴 外壳方法【不】标 @Transactional —— 每轮重试都要是一个新事务")
        void retryLoopIsNotItselfTransactional() throws Exception {
            Method outer = SalesExceptionServiceImpl.class.getMethod("recordBlockedOverShip",
                    String.class, SalesDeliveryRecord.class, SalesDeliveryItem.class,
                    BigDecimal.class, BigDecimal.class, String.class, BigDecimal.class, Long.class);
            assertThat(outer.getAnnotation(Transactional.class))
                    .as("外壳若自己开事务, 撞过唯一约束之后那个事务已经 aborted, "
                            + "在里面重试只会拿到 'current transaction is aborted'")
                    .isNull();
        }

        @Test
        @DisplayName("有 self 代理字段 —— 类内 this 自调用过不了代理, 那是本坑最常见的踩法")
        void hasSelfProxyField() throws Exception {
            assertThat(SalesExceptionServiceImpl.class.getDeclaredField("self").getType())
                    .isEqualTo(SalesExceptionServiceImpl.class);
        }
    }
}
