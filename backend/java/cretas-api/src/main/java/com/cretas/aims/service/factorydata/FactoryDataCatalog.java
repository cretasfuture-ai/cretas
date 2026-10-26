package com.cretas.aims.service.factorydata;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 整厂导出 / 删除的表分类 —— 唯一来源 (手册 docs/security/factory-data-export-and-erasure-runbook.md §2)。
 *
 * <p>默认类别是 {@link Category#BUSINESS}: 新加的带租户列的表自动随工厂导出和删除。
 * 这里只登记例外。⛔ 不要把业务表逐个列进来 —— 手写名单一定会漏掉以后新增的表。
 */
public final class FactoryDataCatalog {

    private FactoryDataCatalog() {}

    public enum Category {
        /** 业务数据 (默认): 导出 + 删除。 */
        BUSINESS(true, true, true),
        /** {@code factories} 里该厂那一行: 导出, 最后删。 */
        FACTORY_ROOT(true, true, true),
        /** 审计 / 留痕: 导出; 删除时先把谁 / 何时 / 做了什么 / 对象编号挪进留存区 (口径 Q3), 再删。 */
        AUDIT(true, true, false),
        /** 训练样本: 导出; 删除时脱敏样本保留并清空工厂编号, 其余删除 (口径 Q4)。 */
        TRAINING(true, true, true),
        /** 库内副本 (public 里的 backup_* / repair_backup_* / bak_*): 不导出; 删除时一并删 (口径 Q6)。 */
        COPY(false, true, true),
        /** 本功能的回执台账与审计留存区: 不导出, 不删 —— 它们证明「删过了、删了多少」。 */
        RECEIPT(false, false, false),
        /** 迁移 / 调度台账: 只有表名和计数, 不导出, 不删。 */
        LEDGER(false, false, false);

        private final boolean exported;
        private final boolean erased;
        private final boolean strict;

        Category(boolean exported, boolean erased, boolean strict) {
            this.exported = exported;
            this.erased = erased;
            this.strict = strict;
        }

        public boolean exported() { return exported; }

        public boolean erased() { return erased; }

        /**
         * 删除时与预演逐表比对的类别。审计 / 留痕不比: 预演、删除接口本身就会让平台访问记录多出几行。
         */
        public boolean strict() { return strict; }
    }

    /** 主库 (cretas_prod_db) 与 SmartBI 库 (smartbi_prod_db)。 */
    public enum Database { CRETAS, SMARTBI }

    public static final String FACTORY_ID = "factory_id";
    public static final String FACTORIES_TABLE = "factories";
    public static final String RECEIPT_TABLE = "factory_data_requests";

    /**
     * 不叫 {@code factory_id} 的租户列。值是工厂编号, 与 {@code factory_id} 同等对待。
     * 预演会把以 factory_id 结尾或叫 tenant_id、却不在这里登记的列列出来 (疑似租户列), 由人判断。
     */
    private static final Map<String, List<String>> EXTRA_TENANT_COLUMNS = Map.of(
            "internal_transfers", List.of("source_factory_id", "target_factory_id"),
            "transfer_diff_records", List.of("source_factory_id", "target_factory_id"),
            "platform_data_access_log", List.of("target_factory_id"),
            "cross_factory_knowledge", List.of("source_factory_id"),
            "tool_execution_audit_events", List.of("tenant_id"),
            "tool_execution_idempotency", List.of("tenant_id"),
            "intent_preview_tokens", List.of("tenant_id"));

    /** 每张都要在 {@link FactoryAuditRetention#MAPPINGS} 里有取数表达式。 */
    static final Set<String> AUDIT_TABLES = Set.of(
            "platform_data_access_log",
            "operation_logs",
            "tool_execution_audit_events",
            "process_secret_access_events",
            "smart_bi_llm_egress_audit");

    /**
     * 训练样本表 -> 「保留」条件 (SQL, 对该表的列)。只有 SmartBI 蒸馏样本打了标记:
     * {@code training_rule} 表示过了训练规则, {@code desensitized=true} 表示是脱敏后存的
     * (backend/python/smartbi/services/training_consent.py RULE_KEY / DESENSITIZED_KEY)。
     * 已授权工厂原样存的样本只有 training_rule 没有 desensitized, 属于原始记录, 删除。
     * Java 侧的意图样本 (ai_training_samples / active_learning_samples) 没有脱敏标记, 按业务数据删除。
     */
    private static final Map<String, String> TRAINING_RETAIN = Map.of(
            "smart_bi_distillation_samples",
            "metadata ->> 'training_rule' IS NOT NULL AND metadata ->> 'desensitized' = 'true'");

    /**
     * 按工厂备份的套餐 / 改动留痕 / 执行记录 (V20261103_88) 也是回执: 它们证明「这个厂的备份删干净了」,
     * 且调度每天写执行记录 —— 若按业务表算, 删除前的预演与删除时的逐表计数会对不上。
     */
    private static final Set<String> RECEIPT_TABLES = Set.of(RECEIPT_TABLE, FactoryAuditRetention.TABLE,
            "tenant_backup_plans", "tenant_backup_plan_changes", "tenant_backup_runs");

    private static final Pattern COPY_TABLE = Pattern.compile("^(backup_|repair_backup_|bak_|mock_rest_bak_).*");
    private static final Pattern LEDGER_TABLE = Pattern.compile(
            "^(migration_.*|flyway_schema_history|smartbi_migrations|smart_bi_schema_history|shedlock)$");

    /** 导出包会离开平台: 这些列的值一律替换成 {@link #REDACTED}。 */
    private static final Pattern CREDENTIAL_COLUMN = Pattern.compile(
            "(password|passwd|secret|token|api_?key|app_?key|access_?key|aes_?key|credential|private_?key|salt|(^|_)otp($|_))",
            Pattern.CASE_INSENSITIVE);
    public static final String REDACTED = "[已移除]";

    /** 以 factory_id 结尾或叫 tenant_id 的列: 用于把「疑似租户列」列出来。 */
    private static final Pattern FACTORY_LIKE_COLUMN = Pattern.compile("(.*factory_id|tenant_id)", Pattern.CASE_INSENSITIVE);

    public static Category categorize(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        if (FACTORIES_TABLE.equals(t)) return Category.FACTORY_ROOT;
        if (RECEIPT_TABLES.contains(t)) return Category.RECEIPT;
        if (LEDGER_TABLE.matcher(t).matches()) return Category.LEDGER;
        if (COPY_TABLE.matcher(t).matches()) return Category.COPY;
        if (AUDIT_TABLES.contains(t)) return Category.AUDIT;
        if (TRAINING_RETAIN.containsKey(t)) return Category.TRAINING;
        return Category.BUSINESS;
    }

    /** 训练样本表的「保留」SQL 条件; 不是训练样本表时报错。 */
    public static String trainingRetainPredicate(String table) {
        String p = TRAINING_RETAIN.get(table);
        if (p == null) throw new IllegalArgumentException("不是训练样本表: " + table);
        return p;
    }

    /** 该表的租户列 (只返回表里真实存在的列)。 */
    public static List<String> tenantColumns(String table, Set<String> columns) {
        List<String> extras = EXTRA_TENANT_COLUMNS.getOrDefault(table, List.of());
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (columns.contains(FACTORY_ID)) out.add(FACTORY_ID);
        for (String c : extras) {
            if (columns.contains(c) && !out.contains(c)) out.add(c);
        }
        return List.copyOf(out);
    }

    public static boolean isCredentialColumn(String column) {
        return CREDENTIAL_COLUMN.matcher(column).find();
    }

    /**
     * 名字像删前备份 / 临时副本的 schema —— ⚠️ 必须与 scripts/cron/backup-copy-retention.sh 的 COPY_SCHEMA_RE
     * 逐字相同 (FactoryDataCatalogTest 钉着): 这里删该厂在副本里的行, 那边按 30 天保留期整份清掉, 两边认的必须是同一批。
     */
    public static final String COPY_SCHEMA_REGEX =
            "^(tenant_purge_[0-9]+|f006_clear_[0-9]+|f006_clean_[0-9]{8}|legacy_retired|backup_[a-z0-9_]+|bak_[a-z0-9_]+|[a-z0-9_]+_bak_[0-9]{8})$";
    private static final Pattern COPY_SCHEMA = Pattern.compile(COPY_SCHEMA_REGEX);

    public static boolean isCopySchema(String schema) {
        return schema != null && COPY_SCHEMA.matcher(schema).matches();
    }

    public static boolean looksLikeTenantColumn(String column) {
        return FACTORY_LIKE_COLUMN.matcher(column).matches();
    }

    /** 工厂编号只允许字母数字下划线横线 —— 它会进 OSS 前缀和文件名。 */
    private static final Pattern FACTORY_ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public static boolean validFactoryId(String factoryId) {
        return factoryId != null && FACTORY_ID_FORMAT.matcher(factoryId).matches();
    }
}
