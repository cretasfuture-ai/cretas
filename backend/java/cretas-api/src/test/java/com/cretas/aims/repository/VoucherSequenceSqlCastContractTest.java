package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住凭证号发号器原生 SQL 里的显式 CAST。
 *
 * <p><b>为什么必须是源码闸而不是 JPA 测试</b>: 单测库是 H2(PostgreSQL 模式),
 * 它的参数类型推断与 PostgreSQL 不同 —— <b>把 CAST 删掉, H2 上照样绿</b>。
 * 在 H2 上写这条断言等于写一道恒绿的闸(本仓踩过多次)。所以这里直接读注解上的 SQL 文本。
 *
 * <p><b>它守的是什么行为</b>: 2026-08-25 prod 实测, 应用盘点差异 500
 * 「数据服务暂时不可用 (追踪码 7E651E80)」, 过账整体回滚, 服务器日志:
 * {@code ERROR: could not determine data type of parameter $2}。
 * pgjdbc 默认 {@code prepareThreshold=5} —— 前几次 simple 模式能过, 第 5 次升级成
 * 服务端 prepared statement 后每次必炸。这解释了「库里有 17 张凭证却今天必失败」。
 */
class VoucherSequenceSqlCastContractTest {

    private static String sequenceSql() throws NoSuchMethodException {
        Method method = VoucherRepository.class
                .getMethod("maxVoucherSequenceByFactoryIdAndYear", String.class, String.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("发号器方法上的 @Query 不见了").isNotNull();
        assertThat(query.nativeQuery())
                .as("必须保持 nativeQuery: @Where 由 Hibernate 在 HQL 层拼装, 原生 SQL 才绕得开软删过滤")
                .isTrue();
        return query.value();
    }

    @Test
    void everyBindParameterInsideConcatIsExplicitlyTyped() throws Exception {
        String sql = sequenceSql();
        // 阳性对照: 确实读到了那条 SQL, 而不是空串让下面的断言假绿
        assertThat(sql).contains("vouchers").contains("voucher_number").contains("MAX(");

        // ⛔ 裸 `?2` 不许再出现在 CONCAT 里 —— 那正是 PG 推不出类型的形态
        assertThat(sql)
                .as("CONCAT 里的裸参数会让 PostgreSQL 在 Parse 阶段推不出类型 (prod 追踪码 7E651E80)")
                .doesNotContain("CONCAT('V-', ?2")
                .contains("CAST(?2 AS text)");
    }

    @Test
    void stillTakesTheMaxOccupiedNumberRatherThanCountingAliveRows() throws Exception {
        String sql = sequenceSql();
        // 这是**上一次**修复(软删的凭证仍占着它的号)的钉子, 本次改动不许把它带回去。
        // prod 实证: 总 17 / 未删 13, 正确答案是 17。
        assertThat(sql).contains("MAX(");
        assertThat(sql)
                .as("不许退回「按未删条数 +1」——那会生成一个已被软删凭证占用的号, 且每次都撞同一个")
                .doesNotContain("COUNT(");
        assertThat(sql)
                .as("原生 SQL 不该自己再加 deleted_at 过滤: 号段发出就不回收")
                .doesNotContain("deleted_at");
    }
}
