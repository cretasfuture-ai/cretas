package com.cretas.aims.service.asyncops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账写入的行为契约 + 与迁移文件的一致性。
 *
 * <p>三件必须钉住的事:
 * <ol>
 *   <li><b>台账自己坏了不能抛</b> —— 抛出去会把「一次丢失的失败」变成
 *       「一次丢失的失败 + 一条噪音」。</li>
 *   <li><b>{@code countOpen()} 必须会抛</b> —— 它是定时跑批第三态 (没量到) 的唯一来源。
 *       把它吞掉, 三态从物理上就退化成两态了。这两条方向<b>相反</b>, 都要有。</li>
 *   <li><b>SQL 与迁移文件对得上</b> —— 表名/列名/唯一约束漂了, 上线当天才炸。</li>
 * </ol>
 */
class AsyncTaskFailureLedgerContractTest {

    private static final Path MIGRATION = Path.of("src", "main", "resources", "db", "flyway",
            "V20261030_05__async_task_failure_ledger.sql");

    @Test
    @DisplayName("⛔ 台账写入失败时返回 false, 绝不抛 —— 否则丢失的失败还要再赔一条噪音")
    void ledgerWriteFailureIsSwallowedAndReported() {
        AsyncTaskFailureLedger ledger = new AsyncTaskFailureLedger(new ExplodingJdbcTemplate());

        AtomicReference<Boolean> result = new AtomicReference<>();
        assertDoesNotThrow(() -> result.set(ledger.record(
                "com.example.Listener.onEvent", "F006", "SO-1",
                new IllegalStateException("business failure"), "补单")));

        assertFalse(result.get(),
                "台账没写进去却返回了 true —— 调用方会以为记下了, 日志里也会打 RECORDED");
    }

    /**
     * 🔴 与上一条方向相反, 刻意的。
     *
     * <p>如果哪天有人为了「统一风格」给 countOpen 也加个 try/catch 返回 0,
     * 本测试会当场红 —— 那个改动会让定时跑批永远到不了 INSTRUMENT_DEAD。
     */
    @Test
    @DisplayName("🔴 countOpen() 必须会抛 —— 它是跑批「没量到」那一态的唯一来源")
    void countOpenPropagatesFailureSoTheSweepCanReportInstrumentDead() {
        AsyncTaskFailureLedger ledger = new AsyncTaskFailureLedger(new ExplodingJdbcTemplate());

        assertThrows(RuntimeException.class, ledger::countOpen,
                "countOpen 把异常吞了 —— 「查不出来」会被跑批读成「查出来是 0」, "
                        + "连不上库的跑批从此安静地天天绿");
    }

    @Test
    @DisplayName("查询返回 null 也算没量到 —— ⛔ 不许兜底成 0")
    void nullCountIsNotSilentlyTreatedAsZero() {
        AsyncTaskFailureLedger ledger = new AsyncTaskFailureLedger(new NullReturningJdbcTemplate());

        assertThrows(IllegalStateException.class, ledger::countOpen,
                "queryForObject 返回 null 被兜底成 0 了 —— 「说不清」被翻译成了「没有」");
    }

    @Test
    @DisplayName("upsert 语义: 复发要把已处理的重新置回 OPEN 并累加次数")
    void upsertReopensResolvedRowsOnRecurrence() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        new AsyncTaskFailureLedger(jdbc).record(
                "com.example.Listener.onEvent", "F006", "SO-1",
                new IllegalStateException("x"), "hint");

        String sql = jdbc.lastSql;
        assertNotNull(sql, "根本没发出 SQL");
        assertTrue(sql.contains("ON CONFLICT (dedup_key) DO UPDATE"),
                "不是 upsert —— 同一个失败反复发生会插出无数行, 告警数字只会涨, 运维处理不完就会关掉它");
        assertTrue(sql.contains("occurrence_count = async_task_failures.occurrence_count + 1"),
                "复发没有累加次数");
        assertTrue(sql.contains("status = 'OPEN'") && sql.contains("resolved_at = NULL"),
                "已处理的失败复发时没有重新置回 OPEN —— 复发是新消息, 不能静静累加在一条已关闭的记录上");
        // 本地 PG17 实测抓到的: 只清 resolved_at/resolved_by 会留下 status=OPEN 但
        // resolve_note 还写着「已处理」的自相矛盾行, 运维会以为有人处理过了。
        assertTrue(sql.contains("resolve_note = NULL"),
                "重开时没有清掉上一轮的处理说明 —— 会出现 status=OPEN 却挂着「已处理」备注的行");
    }

    @Test
    @DisplayName("resolve 的工厂条件写在 UPDATE 里 —— ⛔ 不是先改后查")
    void resolveScopesByFactoryInsideTheUpdate() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        new AsyncTaskFailureLedger(jdbc).resolve(1L, "F006", "42", "done");

        assertTrue(jdbc.lastSql.contains("UPDATE async_task_failures"));
        assertTrue(jdbc.lastSql.contains("factory_id = ?"),
                "UPDATE 里没有工厂条件 —— 会变成「先改掉再返回 403」, 那一行已经被改并提交了 "
                        + "(本仓 CanvasAlertController.acknowledgeEvent 的实测缺陷)");
        assertTrue(jdbc.lastSql.contains("status = 'OPEN'"),
                "没有限定 OPEN —— 已处理的会被重复盖掉 resolved_by / resolved_at");
    }

    @Test
    @DisplayName("dedup key: 空值写 '-', 永不为 NULL")
    void dedupKeyNeverContainsNull() {
        String key = AsyncTaskFailureLedger.buildDedupKey("m", null, null, "E");

        assertEquals("m|-|-|E", key,
                "空值没有写成 '-'。用可空列做复合唯一索引时 PG 把 NULL 视为互不相等, "
                        + "提取不到工厂的行会绕过去重无限增长");
        assertFalse(key.contains("null"), "把字面量 null 拼进了键");
    }

    @Test
    @DisplayName("dedup key 截断时保住最有区分度的方法签名 (放在最前面)")
    void dedupKeyTruncationKeepsTheMethodSignature() {
        String longBusinessKey = "S".repeat(400);
        String key = AsyncTaskFailureLedger.buildDedupKey(
                "com.example.VeryLongListener.onEvent", "F006", longBusinessKey, "E");

        assertTrue(key.length() <= 255, "超长键没有截断, DB 会抛 value too long");
        assertTrue(key.startsWith("com.example.VeryLongListener.onEvent|F006|"),
                "截断把方法签名切掉了 —— 那是最有区分度的一段");
    }

    @Test
    @DisplayName("栈摘要带上 cause 链 —— 根因常常只在 cause 里")
    void stackExcerptIncludesCauseChain() {
        Throwable root = new IllegalArgumentException("the real reason");
        Throwable wrapper = new IllegalStateException("outer", root);

        String excerpt = AsyncTaskFailureLedger.stackExcerpt(wrapper);

        assertNotNull(excerpt);
        assertTrue(excerpt.contains("java.lang.IllegalStateException"), "缺顶层异常");
        assertTrue(excerpt.contains("Caused by: java.lang.IllegalArgumentException"), "缺 cause 链");
        assertTrue(excerpt.contains("the real reason"), "缺根因消息");
    }

    @Test
    @DisplayName("很深的 cause 链不会把台账撑爆, null 也不炸")
    void deepCauseChainIsBoundedAndNullIsSafe() {
        Throwable current = new IllegalStateException("root");
        for (int i = 0; i < 30; i++) {
            current = new IllegalStateException("layer-" + i, current);
        }
        String excerpt = AsyncTaskFailureLedger.stackExcerpt(current);

        assertNotNull(excerpt);
        // 只跟 5 层 cause, 否则一条深链就能写满整列。
        assertTrue(countOccurrences(excerpt, "Caused by:") <= 5,
                "cause 链没有设上限, 一条深链就能把 stack_excerpt 撑爆");
        assertNull(AsyncTaskFailureLedger.stackExcerpt(null), "null 异常不该炸");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    // ---- 迁移文件一致性 ----------------------------------------------------

    @Test
    @DisplayName("迁移文件与代码里的表名/列名/约束名对得上")
    void migrationMatchesTheCode() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS async_task_failures"), "表名对不上");
        assertTrue(sql.contains("CONSTRAINT uq_async_task_failure_dedup UNIQUE (dedup_key)"),
                "缺少 dedup_key 唯一约束 —— 没有它 ON CONFLICT (dedup_key) 会直接报错, "
                        + "台账一条都写不进去");

        // 代码里 INSERT 用到的每一列都必须在 DDL 里。
        List<String> columns = List.of("dedup_key", "factory_id", "method_signature", "business_key",
                "exception_class", "exception_message", "stack_excerpt", "remediation_hint",
                "status", "occurrence_count", "first_seen_at", "last_seen_at",
                "resolved_at", "resolved_by", "resolve_note", "created_at", "updated_at");
        List<String> missing = new ArrayList<>();
        for (String column : columns) {
            if (!sql.contains(column)) {
                missing.add(column);
            }
        }
        assertTrue(missing.isEmpty(), "迁移文件缺列: " + missing);
    }

    @Test
    @DisplayName("factory_id 可空, 且注释说清 NULL 的含义是「判不出来」")
    void factoryIdIsNullableAndItsMeaningIsDocumented() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertFalse(sql.contains("factory_id        VARCHAR(50) NOT NULL"),
                "factory_id 被写成了 NOT NULL —— 提取不到工厂的失败就整条写不进去, "
                        + "而那恰恰是最需要留痕的一类");
        assertTrue(sql.contains("COMMENT ON COLUMN async_task_failures.factory_id"),
                "NULL 的含义没有写进列注释, 下一个人会把它读成「没有工厂」");
    }

    @Test
    @DisplayName("VARCHAR 长度与代码里的截断常量一致")
    void truncationLimitsMatchTheDdl() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertTrue(sql.contains("dedup_key         VARCHAR(255)"),
                "dedup_key 长度与 MAX_DEDUP_KEY=" + AsyncTaskFailureLedger.MAX_DEDUP_KEY + " 不一致");
        assertTrue(sql.contains("factory_id        VARCHAR(50)"),
                "factory_id 长度与 MAX_FACTORY_ID=" + AsyncTaskFailureLedger.MAX_FACTORY_ID + " 不一致");
        assertTrue(sql.contains("business_key      VARCHAR(191)"),
                "business_key 长度与 MAX_BUSINESS_KEY=" + AsyncTaskFailureLedger.MAX_BUSINESS_KEY
                        + " 不一致");
        assertEquals(255, AsyncTaskFailureLedger.MAX_METHOD_SIGNATURE);
        assertEquals(255, AsyncTaskFailureLedger.MAX_EXCEPTION_CLASS);
    }

    @Test
    @DisplayName("truncate 不会在短串上多事")
    void truncateLeavesShortStringsAlone() {
        assertEquals("abc", AsyncTaskFailureLedger.truncate("abc", 10));
        assertEquals("ab", AsyncTaskFailureLedger.truncate("abcdef", 2));
        assertNull(AsyncTaskFailureLedger.truncate(null, 5));
    }

    // ---- 桩 ----------------------------------------------------------------

    /** 真实那一侧会给出这个形状吗? 会 —— 库不可达时 JdbcTemplate 抛的就是这一族。 */
    private static class ExplodingJdbcTemplate extends JdbcTemplate {
        @Override
        public int update(String sql, Object... args) {
            throw new DataAccessResourceFailureException("connection refused");
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            throw new DataAccessResourceFailureException("connection refused");
        }
    }

    private static class NullReturningJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            return null;
        }
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {
        String lastSql;

        @Override
        public int update(String sql, Object... args) {
            this.lastSql = sql;
            return 1;
        }
    }
}
