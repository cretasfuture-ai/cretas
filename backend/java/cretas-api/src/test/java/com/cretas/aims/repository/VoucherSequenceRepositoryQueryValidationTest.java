package com.cretas.aims.repository;

import com.cretas.aims.entity.finance.Voucher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭证号发号器的原生 SQL。
 *
 * <p><b>为什么有这个测试</b>: 2026-08-25 prod 实测, 应用盘点差异时返回
 * 500「数据服务暂时不可用 (追踪码 7E651E80)」, 过账事务整体回滚。服务器日志根因是
 * {@code ERROR: could not determine data type of parameter $2} ——
 * {@code CONCAT('V-', ?2, '-')} 里的参数没有类型上下文, PostgreSQL 在 Parse 阶段
 * 推不出类型。pgjdbc 默认 {@code prepareThreshold=5}: 前几次走 simple 模式(值和类型
 * 一起发)能过, 第 5 次升级成服务端 prepared statement 之后**每次都炸**。
 *
 * <p><b>⚠️ 这个测试证明什么、不证明什么</b> —— 必须说清, 否则它就是一道假闸:
 * <ul>
 *   <li>✅ 证明: 加了显式 CAST 之后, 这条原生 SQL 在 <b>H2(PostgreSQL 模式)</b> 上
 *       仍然能解析并执行 —— 也就是本次修改没有反过来把测试库打坏。</li>
 *   <li>⛔ <b>不</b>证明: PostgreSQL 上那个类型推断缺陷被修好了。
 *       H2 的参数类型推断与 PG 不同, <b>H2 上原来那版也不会红</b> ——
 *       在 H2 上写「防回退」断言等于写一道恒绿的闸。</li>
 * </ul>
 *
 * <p>PG 侧的证据是在<b>生产库</b>上用 {@code PREPARE} 直接取的, 双向:
 * <pre>
 *   现状式  → ERROR: could not determine data type of parameter $2
 *   CAST 式 → PREPARE 成功, 返回 17
 * </pre>
 * 而该库 {@code max(voucher_number)} 恰好是 {@code V-2026-0017}(总 17 / 未删 13)——
 * ⛔ 返回 17 而不是 13, 说明「软删的凭证仍占着它的号」那条修复没有被带回去。
 * 真正钉住 CAST 不被删掉的是同目录的源码闸 {@code VoucherSequenceSqlCastContractTest}。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class VoucherSequenceRepositoryQueryValidationTest {

    @Autowired private VoucherRepository voucherRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void voucherSequenceNativeQueryStillParsesAndExecutes() {
        // 实体映射还在 (阳性对照: 若 EntityScan 没扫到, 下面的查询也没意义)
        assertThat(entityManager.getMetamodel().entity(Voucher.class).getAttribute("voucherNumber")).isNotNull();

        // 空工厂 → 0。⛔ 这里断的是「SQL 跑得起来」, 不是「PG 类型推断修好了」。
        assertThat(voucherRepository.maxVoucherSequenceByFactoryIdAndYear("F-JPA-VOUCHER", "2026")).isZero();

        // 连续多跑几次: 真实 PG 上正是第 5 次起 pgjdbc 升级为服务端 prepared statement
        // 才现形的。H2 上不会红, 但把这个调用形态留在测试里 ——
        // 将来若测试库换成真 PG(pg-test profile), 这条立刻变成有效的回归钉。
        for (int i = 0; i < 8; i++) {
            assertThat(voucherRepository.maxVoucherSequenceByFactoryIdAndYear("F-JPA-VOUCHER", "2026")).isZero();
        }
    }
}
