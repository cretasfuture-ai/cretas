package com.cretas.aims.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关掉 Flyway、手写建表的 PG 夹具用: 把<b>真正的</b> V20261110_47 里针对某张表的 ALTER TABLE 原样执行一遍。
 *
 * <p>2026-11-10 #1580 CI: {@code ReportReversalLogRepositoryPostgresConcurrencyIT} 手写的建表 SQL 没有
 * self_approved / self_approval_reason, 而实体已经映射了这两列, Hibernate 插入当场报「列不存在」。
 * ⛔ 不在夹具里再抄一份列定义 (同一个东西两份一定会漂) —— 夹具跟着迁移走。
 */
public final class SelfApprovalMigrationFixture {

    public static final String MIGRATION = "db/flyway/V20261110_47__factory_allow_self_approval.sql";

    private SelfApprovalMigrationFixture() {
    }

    /** 迁移里针对 {@code table} 的 ALTER TABLE 语句 (去注释、按分号切)。 */
    public static List<String> alterStatementsFor(String table) {
        String prefix = ("alter table " + table + " ").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String statement : statements()) {
            String normalized = statement.replaceAll("\\s+", " ").trim();
            if (normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(statement);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(MIGRATION + " 里没有 ALTER TABLE " + table);
        }
        return out;
    }

    public static void applyTo(JdbcTemplate jdbc, String table) {
        for (String statement : alterStatementsFor(table)) {
            jdbc.execute(statement);
        }
    }

    static List<String> statements() {
        String sql;
        try (InputStream in = SelfApprovalMigrationFixture.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到 " + MIGRATION);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 " + MIGRATION, e);
        }
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int dash = line.indexOf("--");
            withoutComments.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
