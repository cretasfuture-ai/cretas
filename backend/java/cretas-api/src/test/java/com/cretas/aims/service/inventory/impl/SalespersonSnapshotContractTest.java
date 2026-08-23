package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 业务员姓名快照：<b>FK 指着谁，姓名栏就必须有名字</b>。
 *
 * <h2>被守的行为</h2>
 *
 * <p>{@code resolveSalespersonField} 收到数字串（User.id）时会同时写两个字段：
 * {@code salesperson_id}（FK，佣金归属读它）和 {@code salesperson}（姓名快照，单据上显示它）。
 * 原来姓名那一半直接取 {@code user.getFullName()}，而 {@code users.full_name} 是
 * <b>nullable</b> 的（{@code User.java:60}）。
 *
 * <p>⇒ 没填全名的账号建单，FK 写上了、姓名写成 <b>null</b>：
 * 单据上「业务员」一栏空着，可 FK 明明指着一个真实的人。<b>两个字段自相矛盾，且不报错。</b>
 *
 * <h2>不变式</h2>
 *
 * <p><b>{@code salesperson_id} 非空 ⇒ {@code salesperson} 非空。</b>
 * 这一条比「姓名一定等于 full_name」更弱、也更该守：
 * 姓名可以退到一个更弱但为真的标识（登录名），不能退成一个为假的空值。
 *
 * <h2>实测口径（2026-08-19，cretas_prod_db / public schema，只读）</h2>
 *
 * <pre>
 * users          79 行，full_name 非空 78 行     ⇒ 1 个账号属于这一类
 * sales_orders   38 行，salesperson_id 非空 4 行，
 *                其中 salesperson 为 null 的 0 行 ⇒ 尚未在生产上发生，是潜在缺陷
 * </pre>
 *
 * <p>App 端走的正是数字分支：RN {@code SalesOrderCreateScreen} 发 {@code String(user.id)}，
 * 而 {@code BaseUser.fullName} 在 RN 的类型定义里就是可选的。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("业务员姓名快照：FK 有值时姓名不许为空")
class SalespersonSnapshotContractTest {

    @Mock
    private UserRepository userRepository;

    private SalesServiceImpl salesService;

    @BeforeEach
    void setUp() {
        // 与 SalesServiceImplSalespersonTest 同法：8 参构造器传 null，
        // 再反射塞 userRepository（它是 @Autowired 字段，不走构造器）。
        // resolveSalespersonField 只碰这一个依赖。
        salesService = new SalesServiceImpl(null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    private static User user(Long id, String fullName, String username) {
        User u = new User();
        u.setId(id);
        u.setFullName(fullName);
        u.setUsername(username);
        return u;
    }

    @Test
    @DisplayName("阳性对照：有全名时照常用全名（不许因为『防空』把正常路径也改了）")
    void usesFullNameWhenPresent() {
        assertThat(SalesServiceImpl.salespersonSnapshotOf(user(7L, "张三", "zhangsan")))
                .isEqualTo("张三");
    }

    @Test
    @DisplayName("🔴 没有全名 → 退到登录名，⛔ 不是 null")
    void fallsBackToUsernameInsteadOfWritingNull() {
        // 这一类在 prod 上真实存在: 79 个账号里有 1 个没有 full_name。
        assertThat(SalesServiceImpl.salespersonSnapshotOf(user(8L, null, "zhaosi")))
                .as("写 null 会让 salesperson_id 指着一个人而姓名栏说没有人")
                .isEqualTo("zhaosi");
    }

    @Test
    @DisplayName("🔴 全名是空白串也算没有（\"\" / \"   \" 与 null 同类）")
    void blankFullNameIsTreatedAsAbsent() {
        assertThat(SalesServiceImpl.salespersonSnapshotOf(user(9L, "", "wangwu"))).isEqualTo("wangwu");
        assertThat(SalesServiceImpl.salespersonSnapshotOf(user(9L, "   ", "wangwu"))).isEqualTo("wangwu");
    }

    @Test
    @DisplayName("两个标识都没有 → 显式失败，不许静默落一个空值")
    void throwsWhenNoIdentifierAtAll() {
        // 「真的不知道」时不许兜底 —— 那才是形态 A¹⁰ 说的那种坏兜底。
        assertThatThrownBy(() -> SalesServiceImpl.salespersonSnapshotOf(user(10L, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法记录业务员");
    }

    // ------------------------------------------------------------- 接线（不是 helper）

    /**
     * 🔴 <b>这条守的是「接上没有」，不是 helper 本身。</b>
     *
     * <p>上面几条都直接调 {@code salespersonSnapshotOf}。把 {@code resolveSalespersonField}
     * 里那一行改回 {@code user.getFullName()}，它们<b>一条都不会红</b> ——
     * 那正是本仓反复踩的「测了 helper 不是接线」（形态 B）。
     * ⇒ 这条从产品真实入口 {@code resolveSalespersonField} 进去，只桩掉 userRepository。
     */
    @Test
    @DisplayName("🔴 接线：真实入口 resolveSalespersonField 也不许把姓名写成 null")
    void realEntryPointNeverWritesNullNameWhenFkIsSet() {
        User noFullName = user(1309L, null, "zhaosi");
        noFullName.setFactoryId("F006");
        when(userRepository.findById(1309L)).thenReturn(Optional.of(noFullName));

        SalesOrder order = new SalesOrder();
        salesService.resolveSalespersonField(order, "1309", "F006");

        // FK 写上了 —— 这是阳性对照: 数字分支确实走到了。
        assertThat(order.getSalespersonId())
                .as("FK 都没写上, 说明根本没走数字分支, 后面那条断言没有意义")
                .isEqualTo(1309L);

        // 不变式: FK 非空 ⇒ 姓名非空。改回 user.getFullName() 这里就是 null。
        assertThat(order.getSalesperson())
                .as("salesperson_id 指着 1309, 而姓名栏是空的 —— 两个字段自相矛盾")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @DisplayName("接线：有全名时真实入口照常写全名（防空没有伤到正常路径）")
    void realEntryPointStillSnapshotsFullName() {
        User withFullName = user(1310L, "张六膳", "zhangliushan");
        withFullName.setFactoryId("F006");
        when(userRepository.findById(1310L)).thenReturn(Optional.of(withFullName));

        SalesOrder order = new SalesOrder();
        salesService.resolveSalespersonField(order, "1310", "F006");

        assertThat(order.getSalespersonId()).isEqualTo(1310L);
        assertThat(order.getSalesperson()).isEqualTo("张六膳");
    }

    @Test
    @DisplayName("不变式：任何一种能成功返回的输入，返回值都非空白")
    void invariantResultIsNeverBlank() {
        // 逐个覆盖「能返回」的输入形状, 断言的是不变式本身而不是某一个字面量,
        // 这样以后改实现(比如改成退到手机号)这条仍然守得住。
        String[][] cases = {
                {"张三", "zhangsan"},
                {null, "zhaosi"},
                {"", "wangwu"},
                {"  ", "wangwu"},
                {"李四", null},
        };
        for (String[] c : cases) {
            String snapshot = SalesServiceImpl.salespersonSnapshotOf(user(11L, c[0], c[1]));
            assertThat(snapshot)
                    .as("fullName=%s username=%s 时快照为空白 —— 不变式破了", c[0], c[1])
                    .isNotNull()
                    .isNotBlank();
        }
    }
}
