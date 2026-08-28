package com.cretas.aims.service.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VoucherSubjectResolver} —— 让客户能把生成器里写死的会计科目换成自己账套的。
 *
 * <h2>这道闸最要紧的一条是「空表 = 行为不变」</h2>
 *
 * <p>迁移刻意不预置任何行。所以接进 8 个生成器之后，在没人配置的工厂上产出的科目
 * 必须与改动前<b>逐字一致</b> —— 否则这就不是"加了个可配置项"，而是"悄悄改了所有人的账"。
 *
 * <h2>fail-open，不是 fail-closed</h2>
 *
 * <p>查不到 / 查询炸了 / 配了一行但代码为空 —— 一律回落默认值。
 * ⛔ 不做「没配就拒绝开凭证」：配错科目是<b>数字记错地方</b>，
 * 开不出凭证是<b>业务停摆</b>，后者贵得多。
 */
class VoucherSubjectResolverContractTest {

    private static final String DEFAULT_CODE = "1122";
    private static final String DEFAULT_NAME = "应收账款";

    private VoucherSubjectResolver resolverReturning(List<Map<String, Object>> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
        return new VoucherSubjectResolver(jdbc);
    }

    @Test
    @DisplayName("🔴 空表 -> 与改动前逐字一致的默认科目")
    void emptyTableKeepsDefaults() {
        VoucherSubjectResolver.Subject s = resolverReturning(List.of())
                .resolve("F006", "SALES_RECEIPT", "DEBIT", DEFAULT_CODE, DEFAULT_NAME);

        assertThat(s.code()).isEqualTo(DEFAULT_CODE);
        assertThat(s.name()).isEqualTo(DEFAULT_NAME);
    }

    @Test
    @DisplayName("配了覆盖 -> 用客户账套的科目")
    void overrideWins() {
        VoucherSubjectResolver.Subject s = resolverReturning(List.of(
                Map.of("subject_code", "1131", "subject_name", "客户往来")))
                .resolve("F006", "SALES_RECEIPT", "DEBIT", DEFAULT_CODE, DEFAULT_NAME);

        assertThat(s.code()).isEqualTo("1131");
        assertThat(s.name()).isEqualTo("客户往来");
    }

    @Test
    @DisplayName("只配了代码没配名称 -> 用默认名称, 不产出空名字")
    void blankNameFallsBackToDefaultName() {
        VoucherSubjectResolver.Subject s = resolverReturning(List.of(
                java.util.Collections.singletonMap("subject_code", "1131")))
                .resolve("F006", "SALES_RECEIPT", "DEBIT", DEFAULT_CODE, DEFAULT_NAME);

        assertThat(s.code()).isEqualTo("1131");
        assertThat(s.name()).as("名称留空不该变成 null 科目名").isEqualTo(DEFAULT_NAME);
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 配了一行却没有代码 = 脏数据, 回落默认值而不是产出空科目")
    void blankCodeIsNotAnOverride() {
        VoucherSubjectResolver.Subject s = resolverReturning(List.of(
                Map.of("subject_code", "  ", "subject_name", "乱配的")))
                .resolve("F006", "SALES_RECEIPT", "DEBIT", DEFAULT_CODE, DEFAULT_NAME);

        assertThat(s.code()).isEqualTo(DEFAULT_CODE);
    }

    @Test
    @DisplayName("🔴 查询炸了 -> 回落默认值, ⛔ 一张可选的覆盖表不该让凭证开不出来")
    void queryFailureIsFailOpen() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("库不可达"));

        VoucherSubjectResolver.Subject s = new VoucherSubjectResolver(jdbc)
                .resolve("F006", "SALES_RECEIPT", "DEBIT", DEFAULT_CODE, DEFAULT_NAME);

        assertThat(s.code())
                .as("fail-closed 会把「没配科目」变成「开不出凭证」—— 那比记错科目贵得多")
                .isEqualTo(DEFAULT_CODE);
    }

    @Test
    @DisplayName("没有 factoryId(比如折旧那种 Map 输入) -> 直接走默认值, 不去查库")
    void missingFactoryUsesDefaultsWithoutQuerying() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);   // 未打桩: 一旦被调用就会返回 null 并 NPE

        VoucherSubjectResolver.Subject s = new VoucherSubjectResolver(jdbc)
                .resolve(null, "DEPRECATION", "DEBIT", "6602.02", "管理费用-折旧");

        assertThat(s.code()).isEqualTo("6602.02");
    }
}
