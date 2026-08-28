package com.cretas.aims.service.asyncops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * {@code @Async} 失败台账的读写口。
 *
 * <h3>⛔ 为什么用 JdbcTemplate 而不是 JPA</h3>
 * <p>要记的失败<b>本身就是 JPA 的失败</b>。prod 2026-06-19 ~ 08-19 的 211 次逃逸:
 * <pre>
 *   110  org.springframework.transaction.UnexpectedRollbackException
 *    86  org.springframework.orm.ObjectOptimisticLockingFailureException
 *    15  org.springframework.dao.DataIntegrityViolationException
 * </pre>
 * 用 JPA 记 JPA 的失败, 记录器和被记录的东西共用同一套正在坏的机器。
 *
 * <p>本仓有<b>实测代价</b>: {@code SalesOrderShortageReportListener.persistFailedPlaceholder}
 * 就是「失败了就用 JPA save 写一行 FAILED 占位」这个思路, prod 上
 * <b>76/77 次连占位行都没写进去</b> —— 它跑在同一个已被标记 rollback-only 的事务里,
 * 而且 JSONB 列的 Hibernate dirty-check deepCopy 会二次反序列化失败。
 * 同一课在 {@code VoucherServiceImpl.batchCreateForFactory} 的 javadoc 里也写着:
 * 「标 FAILED 也必须在【干净的新事务】里做」。
 *
 * <p>⇒ 本类: 无实体 / 无 {@code @Version} / 无 dirty-check / 无 JSONB;
 * {@code REQUIRES_NEW} 保证拿到一条干净连接 (而不是复用那个已经 doomed 的事务);
 * 单条 {@code INSERT ... ON CONFLICT DO UPDATE} 原子完成, 蓝绿双 JVM (10010 / 10020)
 * 同时收到同一个事件也不会互相打架 —— 这一点尤其要紧, 因为 211 次里 86 次
 * 正是乐观锁冲突, 读-改-写的记录器会重蹈覆辙。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskFailureLedger {

    /** 与 DDL 的 VARCHAR 长度一一对应 —— 超长在 Java 侧截断, 不让 DB 抛。 */
    static final int MAX_DEDUP_KEY = 255;
    static final int MAX_METHOD_SIGNATURE = 255;
    static final int MAX_EXCEPTION_CLASS = 255;
    static final int MAX_FACTORY_ID = 50;
    static final int MAX_BUSINESS_KEY = 191;

    /** 异常消息可能很长 (SQL 全文), 截到这个长度足够定位, 又不会把台账撑爆。 */
    static final int MAX_EXCEPTION_MESSAGE = 2000;

    /** 栈只留最上面这么多帧 —— 定位靠的是最上面几帧, 全量栈进库没人看。 */
    static final int STACK_FRAMES_KEPT = 12;

    /** 提取不到时在 dedup_key 里的占位符。⛔ 不写进 factory_id/business_key 列。 */
    static final String ABSENT = "-";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 记一次失败。已存在的同类失败累加次数并把状态拉回 OPEN。
     *
     * <p>{@code REQUIRES_NEW}: 调用点 (异常处理器) 在 {@code @Async} 方法的事务边界<b>之外</b>,
     * 正常情况下没有活动事务; 但万一有 (且很可能是个已经 rollback-only 的), 也必须开一条新的,
     * 否则这一行会和它一起被回滚 —— 那就是 persistFailedPlaceholder 76/77 次失败的原样重演。
     *
     * @return true = 确实落库了; false = 台账自己也失败了 (此时只剩日志)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(String methodSignature,
                          String factoryId,
                          String businessKey,
                          Throwable error,
                          String remediationHint) {
        String exceptionClass = error == null ? "java.lang.Throwable" : error.getClass().getName();
        String dedupKey = buildDedupKey(methodSignature, factoryId, businessKey, exceptionClass);
        try {
            jdbcTemplate.update(
                    "INSERT INTO async_task_failures "
                            + "(dedup_key, factory_id, method_signature, business_key, exception_class, "
                            + " exception_message, stack_excerpt, remediation_hint, status, occurrence_count, "
                            + " first_seen_at, last_seen_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, NOW(), NOW(), NOW(), NOW()) "
                            + "ON CONFLICT (dedup_key) DO UPDATE SET "
                            // 复发是新消息 —— 已经 resolve 过的重新置回 OPEN, 而不是静静累加。
                            + "  status = 'OPEN', "
                            + "  resolved_at = NULL, "
                            + "  resolved_by = NULL, "
                            // resolve_note 也要清 —— 否则复发后会出现 status=OPEN 但
                            // resolve_note='已处理' 的自相矛盾行, 运维会以为有人已经处理过了。
                            // (本地 PG17 实测发现: 不清它, 重开的行还挂着上一轮的处理说明。)
                            + "  resolve_note = NULL, "
                            + "  occurrence_count = async_task_failures.occurrence_count + 1, "
                            + "  last_seen_at = NOW(), "
                            + "  updated_at = NOW(), "
                            // 每次都覆盖成最近一次的现场, 老的现场对排查没有增量价值。
                            + "  exception_message = EXCLUDED.exception_message, "
                            + "  stack_excerpt = EXCLUDED.stack_excerpt, "
                            + "  remediation_hint = EXCLUDED.remediation_hint",
                    dedupKey,
                    truncate(factoryId, MAX_FACTORY_ID),
                    truncate(methodSignature, MAX_METHOD_SIGNATURE),
                    truncate(businessKey, MAX_BUSINESS_KEY),
                    truncate(exceptionClass, MAX_EXCEPTION_CLASS),
                    truncate(messageOf(error), MAX_EXCEPTION_MESSAGE),
                    stackExcerpt(error),
                    remediationHint);
            return true;
        } catch (Exception ledgerFailure) {
            // 台账自己坏了不能再抛 —— 抛出去只会被 Spring 的默认处理器再吞一次, 净效果是
            // 「一次丢失的失败」变成「一次丢失的失败 + 一条噪音」。但也绝不静默:
            // 这条日志是判断「台账是不是死了」的唯一依据, 措辞要能被 grep 出来。
            log.error("ASYNC_FAILURE_LEDGER_WRITE_FAILED method={} factory={} businessKey={} "
                            + "originalException={} —— 本次失败只剩这条日志, 台账里没有它",
                    methodSignature, factoryId, businessKey, exceptionClass, ledgerFailure);
            return false;
        }
    }

    /**
     * 还有多少条没处理完。
     *
     * <p>⛔ <b>这个方法故意会抛。</b> 调用方 (定时跑批) 必须把「查不出来」和「查出来是 0」
     * 分成两态 —— 把异常吞在这里返回 0, 会让一个连不上库的跑批<b>安静地天天绿</b>。
     */
    public int countOpen() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM async_task_failures WHERE status = 'OPEN'", Integer.class);
        // ⚠️ count(*) 空集就是 0, 这里的 0 是合法读数; 但 queryForObject 返回 null 属于
        // 「说不清」, 不能兜底成 0 —— 让它抛, 由调用方报「没量到」。
        if (n == null) {
            throw new IllegalStateException("count(*) 返回 null —— 台账读数无效");
        }
        return n;
    }

    /** 给告警正文用: 按方法归类的未处理条数 + 累计发生次数。 */
    public List<Map<String, Object>> openBreakdownByMethod() {
        return jdbcTemplate.queryForList(
                "SELECT method_signature, count(*) AS rows_open, "
                        + "       sum(occurrence_count) AS occurrences, max(last_seen_at) AS latest "
                        + "FROM async_task_failures WHERE status = 'OPEN' "
                        + "GROUP BY method_signature ORDER BY occurrences DESC");
    }

    /**
     * 按工厂归类的未处理条目 —— 站内通知要发给<b>那个工厂的</b>管理员, 所以得按工厂分。
     *
     * <p>⚠️ {@code factory_id} 可能为 NULL(异步方法的参数里提取不到工厂)。这些行
     * <b>没有人能被通知到</b>, 调用方必须把它们单独说出来, ⛔ 不许静默丢掉 ——
     * 「通知发不出去」和「没有失败」是两件事。
     */
    public List<Map<String, Object>> openBreakdownByFactory() {
        return jdbcTemplate.queryForList(
                "SELECT factory_id, count(*) AS rows_open, "
                        + "       sum(occurrence_count) AS occurrences, max(last_seen_at) AS latest "
                        + "FROM async_task_failures WHERE status = 'OPEN' "
                        + "GROUP BY factory_id ORDER BY occurrences DESC");
    }

    /** 运维查明细。{@code factoryId} 为 null 时跨工厂查 (含提取不到工厂的行)。 */
    public List<Map<String, Object>> list(String factoryId, String status, int limit) {
        String effectiveStatus = (status == null || status.isBlank()) ? null : status;
        return jdbcTemplate.queryForList(
                "SELECT id, factory_id, method_signature, business_key, exception_class, "
                        + "       exception_message, remediation_hint, status, occurrence_count, "
                        + "       first_seen_at, last_seen_at, resolved_at, resolved_by, resolve_note "
                        + "FROM async_task_failures "
                        + "WHERE (CAST(? AS varchar) IS NULL OR factory_id = ?) "
                        + "  AND (CAST(? AS varchar) IS NULL OR status = ?) "
                        + "ORDER BY last_seen_at DESC LIMIT ?",
                factoryId, factoryId, effectiveStatus, effectiveStatus, limit);
    }

    /**
     * 标记为已处理。
     *
     * <p>为什么要有这个: 一个永远静不下来的告警等于没有告警 (本仓形态 E)。
     * 运维照 {@code remediation_hint} 补完之后必须能让它闭嘴, 否则第三天就没人看了。
     *
     * <p>⚠️ 只认 OPEN → RESOLVED; 已经 RESOLVED 的重复调用返回 0, 由调用方决定怎么答。
     *
     * @return 实际更新的行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolve(long id, String factoryId, String resolvedBy, String note) {
        return jdbcTemplate.update(
                "UPDATE async_task_failures SET status = 'RESOLVED', resolved_at = NOW(), "
                        + "  resolved_by = ?, resolve_note = ?, updated_at = NOW() "
                        + "WHERE id = ? AND status = 'OPEN' "
                        // 跨工厂越权保护: 传了 factoryId 就必须对得上; 提取不到工厂的行
                        // (factory_id IS NULL) 只有不带 factoryId 的平台级调用能处理。
                        + "  AND (CAST(? AS varchar) IS NULL OR factory_id = ?)",
                truncate(resolvedBy, 100), note, id, factoryId, factoryId);
    }

    /**
     * dedup_key 的唯一拼法。
     *
     * <p>用一个永不为 NULL 的字符串键, 而不是 {@code (factory_id, business_key, ...)} 复合唯一索引 ——
     * PG 默认把 NULL 视为互不相等, 提取不到工厂的行会绕过去重无限增长。
     *
     * <p>⚠️ 超 255 会截断, 截断后两条不同的失败理论上可能并成一条。方法签名放在最前面,
     * 保证最有区分度的那一段不会被截掉。
     */
    static String buildDedupKey(String methodSignature, String factoryId,
                                String businessKey, String exceptionClass) {
        String raw = orAbsent(methodSignature) + "|" + orAbsent(factoryId) + "|"
                + orAbsent(businessKey) + "|" + orAbsent(exceptionClass);
        return truncate(raw, MAX_DEDUP_KEY);
    }

    private static String orAbsent(String s) {
        return (s == null || s.isBlank()) ? ABSENT : s;
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return null;
        }
        String msg = error.getMessage();
        return msg == null ? "(no message)" : msg;
    }

    /** 最上面几帧 + 完整的 cause 链类名 —— 根因往往在 cause 里, 只留顶层栈会漏掉它。 */
    static String stackExcerpt(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(error.getClass().getName());
        String msg = error.getMessage();
        if (msg != null) {
            sb.append(": ").append(msg);
        }
        StackTraceElement[] frames = error.getStackTrace();
        int kept = Math.min(STACK_FRAMES_KEPT, frames.length);
        for (int i = 0; i < kept; i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        if (frames.length > kept) {
            sb.append("\n\t... ").append(frames.length - kept).append(" more");
        }
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && cause != error && depth < 5) {
            sb.append("\nCaused by: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
            depth++;
        }
        return sb.toString();
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
