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
        BUSINESS(true, true),
        /** {@code factories} 里该厂那一行: 导出, 最后删。 */
        FACTORY_ROOT(true, true),
        /** 审计 / 留痕: 导出; 删除时的去留按口径 Q3。 */
        AUDIT(true, false),
        /** 训练样本 / 授权: 导出; 删除时的去留按口径 Q4。 */
        TRAINING(true, false),
        /** 库内副本 (backup_* / repair_backup_* / bak_*): 不导出; 删除时的去留按口径 Q6。 */
        COPY(false, false),
        /** 本功能的回执台账: 不导出, 不删 —— 它证明「删过了、删了多少」。 */
        RECEIPT(false, false),
        /** 迁移 / 调度台账: 只有表名和计数, 不导出, 不删。 */
        LEDGER(false, false);

        private final boolean exported;
        private final boolean erasedByDefault;

        Category(boolean exported, boolean erasedByDefault) {
            this.exported = exported;
            this.erasedByDefault = erasedByDefault;
        }

        public boolean exported() { return exported; }

        /** 口径未拍板前, 只有 BUSINESS / FACTORY_ROOT 计入「将删除」。 */
        public boolean erasedByDefault() { return erasedByDefault; }
    }

    /** 主库 (cretas_prod_db) 与 SmartBI 库 (smartbi_prod_db)。 */
    public enum Database { CRETAS, SMARTBI }

    public static final String FACTORY_ID = "factory_id";
    public static final String FACTORIES_TABLE = "factories";
    public static final String RECEIPT_TABLE = "factory_data_requests";

    /**
     * 不叫 {@code factory_id} 的租户列。值是工厂编号, 与 {@code factory_id} 同等对待。
     * 预演会把名字里含 {@code factory} 却不在这里、也不是 {@code factory_id} 的列列出来 (疑似租户列), 由人判断。
     */
    private static final Map<String, List<String>> EXTRA_TENANT_COLUMNS = Map.of(
            "internal_transfers", List.of("source_factory_id", "target_factory_id"),
            "transfer_diff_records", List.of("source_factory_id", "target_factory_id"),
            "platform_data_access_log", List.of("target_factory_id"),
            "cross_factory_knowledge", List.of("source_factory_id"),
            "tool_execution_audit_events", List.of("tenant_id"),
            "tool_execution_idempotency", List.of("tenant_id"),
            "intent_preview_tokens", List.of("tenant_id"));

    private static final Set<String> AUDIT_TABLES = Set.of(
            "platform_data_access_log",
            "operation_logs",
            "tool_execution_audit_events",
            "process_secret_access_events",
            "smart_bi_llm_egress_audit");

    private static final Set<String> TRAINING_TABLES = Set.of(
            "ai_training_samples",
            "smart_bi_distillation_samples",
            "training_consent");

    private static final Pattern COPY_TABLE = Pattern.compile("^(backup_|repair_backup_|bak_|mock_rest_bak_).*");
    private static final Pattern LEDGER_TABLE = Pattern.compile(
            "^(migration_.*|flyway_schema_history|smartbi_migrations|smart_bi_schema_history|shedlock)$");

    /** 导出包会离开平台: 这些列的值一律替换成 {@link #REDACTED}。 */
    private static final Pattern CREDENTIAL_COLUMN = Pattern.compile(
            "(password|passwd|secret|token|api_?key|credential|private_?key|salt)", Pattern.CASE_INSENSITIVE);
    public static final String REDACTED = "[已移除]";

    /** 以 factory_id 结尾或叫 tenant_id 的列: 用于把「疑似租户列」列出来。 */
    private static final Pattern FACTORY_LIKE_COLUMN = Pattern.compile("(.*factory_id|tenant_id)", Pattern.CASE_INSENSITIVE);

    public static Category categorize(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        if (FACTORIES_TABLE.equals(t)) return Category.FACTORY_ROOT;
        if (RECEIPT_TABLE.equals(t)) return Category.RECEIPT;
        if (LEDGER_TABLE.matcher(t).matches()) return Category.LEDGER;
        if (COPY_TABLE.matcher(t).matches()) return Category.COPY;
        if (AUDIT_TABLES.contains(t)) return Category.AUDIT;
        if (TRAINING_TABLES.contains(t)) return Category.TRAINING;
        return Category.BUSINESS;
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

    public static boolean looksLikeTenantColumn(String column) {
        return FACTORY_LIKE_COLUMN.matcher(column).matches();
    }

    /** 工厂编号只允许字母数字下划线横线 —— 它会进 OSS 前缀和文件名。 */
    private static final Pattern FACTORY_ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public static boolean validFactoryId(String factoryId) {
        return factoryId != null && FACTORY_ID_FORMAT.matcher(factoryId).matches();
    }
}
