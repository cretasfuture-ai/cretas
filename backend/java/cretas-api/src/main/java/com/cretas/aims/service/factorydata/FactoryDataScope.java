package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一个工厂在一个库里「拥有哪些行」—— 导出、预演、删除共用这一份定义。
 *
 * <p>由 {@link FactoryDataScopeResolver} 在同一个快照里算出:
 * 种子 (租户列等于该编号) + 顺外键扩散到的子行 (不动点)。
 */
public final class FactoryDataScope {

    /** 选中行的方式: 有单列主键的表按主键值, 没有的按租户列条件。 */
    public enum Selection { KEYS, PREDICATE }

    /** 跨租户命中的种类。任何一种不为 0, 删除都必须停手。 */
    public enum ForeignKind {
        /** 租户列之一是本厂, 另一个租户列是别的工厂 (例: 两厂之间的调拨单)。 */
        SHARED_ROW,
        /** 别的工厂的行引用了本厂的行。 */
        OTHER_TENANT,
        /** 带租户列但租户列为空的行引用了本厂的行 —— 归属不明。 */
        UNSCOPED,
        /** 子表既没有租户列也没有单列主键, 无法逐行认定。 */
        UNSUPPORTED
    }

    public record ForeignHit(String table, String rowKey, ForeignKind kind, String detail, String viaTable) {}

    /** 本厂的行删掉后, 这些别处的引用会被外键置空 (ON DELETE SET NULL / SET DEFAULT)。 */
    public record NulledReference(String table, String column, long rows, String parentTable) {}

    public static final class TableScope {
        private final String table;
        private final Category category;
        private final List<String> tenantColumns;
        private final String pkColumn;
        private final Selection selection;
        private final Set<String> keys = new LinkedHashSet<>();
        private long predicateRows;
        private int depth;

        TableScope(String table, Category category, List<String> tenantColumns, String pkColumn, int depth) {
            this.table = table;
            this.category = category;
            this.tenantColumns = List.copyOf(tenantColumns);
            this.pkColumn = pkColumn;
            this.selection = pkColumn != null ? Selection.KEYS : Selection.PREDICATE;
            this.depth = depth;
        }

        public String table() { return table; }
        public Category category() { return category; }
        public List<String> tenantColumns() { return tenantColumns; }
        public String pkColumn() { return pkColumn; }
        public Selection selection() { return selection; }
        public Set<String> keys() { return Collections.unmodifiableSet(keys); }
        /** 0 = 种子表 (带租户列), 1+ = 顺外键扩散到的层数。 */
        public int depth() { return depth; }

        public long rows() { return selection == Selection.KEYS ? keys.size() : predicateRows; }

        boolean addKey(String key) { return keys.add(key); }
        void setPredicateRows(long rows) { this.predicateRows = rows; }
    }

    private final FactoryDataCatalog.Database database;
    private final String schema;
    private final String factoryId;
    private final Map<String, TableScope> tables = new TreeMap<>();
    private final List<ForeignHit> foreignHits = new ArrayList<>();
    private final List<NulledReference> nulledReferences = new ArrayList<>();
    private final List<String> suspectTenantColumns = new ArrayList<>();
    private int closurePasses;

    FactoryDataScope(FactoryDataCatalog.Database database, String schema, String factoryId) {
        this.database = database;
        this.schema = schema;
        this.factoryId = factoryId;
    }

    public FactoryDataCatalog.Database database() { return database; }
    public String schema() { return schema; }
    public String factoryId() { return factoryId; }
    public Map<String, TableScope> tables() { return Collections.unmodifiableMap(tables); }
    public List<ForeignHit> foreignHits() { return Collections.unmodifiableList(foreignHits); }
    public List<NulledReference> nulledReferences() { return Collections.unmodifiableList(nulledReferences); }
    /** 名字里含 factory、却既不是 factory_id 也没在目录里登记的列 ({@code 表.列})。 */
    public List<String> suspectTenantColumns() { return Collections.unmodifiableList(suspectTenantColumns); }
    public int closurePasses() { return closurePasses; }

    /** 有行的表 (rows &gt; 0)。 */
    public List<TableScope> nonEmpty() {
        return tables.values().stream().filter(t -> t.rows() > 0).toList();
    }

    public long totalRows() {
        return tables.values().stream().mapToLong(TableScope::rows).sum();
    }

    TableScope table(String name) { return tables.get(name); }

    TableScope putTable(TableScope scope) {
        tables.put(scope.table(), scope);
        return scope;
    }

    void addForeign(ForeignHit hit) { foreignHits.add(hit); }
    void addNulled(NulledReference ref) { nulledReferences.add(ref); }
    void addSuspect(String tableDotColumn) { suspectTenantColumns.add(tableDotColumn); }
    void setClosurePasses(int passes) { this.closurePasses = passes; }
}
