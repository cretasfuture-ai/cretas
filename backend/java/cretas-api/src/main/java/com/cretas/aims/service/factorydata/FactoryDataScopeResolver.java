package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;
import com.cretas.aims.service.factorydata.FactoryDataScope.ForeignHit;
import com.cretas.aims.service.factorydata.FactoryDataScope.ForeignKind;
import com.cretas.aims.service.factorydata.FactoryDataScope.NulledReference;
import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * 在调用方给的连接 (同一个快照) 里算出 {@link FactoryDataScope}。
 *
 * <p>为什么要顺外键扩散: 2026-08-07 清理 60 个测试租户 (V20261029_68) 时, 只按 factory_id 扫的第一版删不动 ——
 * 订单明细、凭证分录、设备维修、排班明细等 21 张表不带 factory_id, 只能从父行顺外键找到; 只按 factory_id 删,
 * 要么撞外键, 要么 (外键允许时) 静默留下孤儿行。
 *
 * <p>规则 (手册 §4):
 * <ol>
 *   <li>种子: 租户列等于该编号的行; 主库再加 {@code factories} 里该编号那一行。</li>
 *   <li>扩散: 只顺 NO ACTION / RESTRICT / CASCADE 外键 —— 这些外键在父行删除时会拦住或连带删除子行。
 *       不带租户列的子行归本厂; 带租户列的子行若不是本厂, 记为跨租户命中, ⛔ 不纳入。</li>
 *   <li>SET NULL / SET DEFAULT 外键不扩散 (子行不属于本厂), 只统计会被置空的引用;
 *       被置空的若是别的工厂的行, 同样记为跨租户命中。</li>
 * </ol>
 * 调用方负责事务: 导出 / 预演用 REPEATABLE READ 只读事务, 删除用同一个写事务。
 */
public final class FactoryDataScopeResolver {

    static final int MAX_CLOSURE_PASSES = 20;

    private record Fk(String child, String parent, char deleteAction, List<String> childColumns,
                      List<String> parentColumns) {
        boolean expands() { return deleteAction == 'a' || deleteAction == 'r' || deleteAction == 'c'; }
    }

    private record Meta(Map<String, List<String>> columns, Map<String, String> singlePk, List<Fk> fks,
                        List<Fk> droppedFks) {}

    private FactoryDataScopeResolver() {}

    public static FactoryDataScope resolve(Connection conn, Database database, String schema, String factoryId)
            throws SQLException {
        if (!FactoryDataCatalog.validFactoryId(factoryId)) {
            throw new IllegalArgumentException("工厂编号格式不合法: " + factoryId);
        }
        Meta meta = loadMeta(conn, schema);
        FactoryDataScope scope = new FactoryDataScope(database, schema, factoryId);

        // 1) 种子
        Map<String, Set<String>> frontier = new HashMap<>();
        for (Map.Entry<String, List<String>> e : meta.columns().entrySet()) {
            String table = e.getKey();
            Set<String> cols = new LinkedHashSet<>(e.getValue());
            List<String> tenant = FactoryDataCatalog.tenantColumns(table, cols);
            for (String c : cols) {
                if (!tenant.contains(c) && FactoryDataCatalog.looksLikeTenantColumn(c)) {
                    scope.addSuspect(table + "." + c);
                }
            }
            if (database == Database.CRETAS && FactoryDataCatalog.FACTORIES_TABLE.equals(table)) {
                seedFactoryRoot(conn, schema, meta, scope, frontier);
                continue;
            }
            if (tenant.isEmpty()) continue;
            seedTable(conn, schema, table, tenant, meta.singlePk().get(table), scope, frontier);
        }

        // 2) 顺外键扩散到不动点
        int pass = 0;
        while (!frontier.isEmpty()) {
            if (++pass > MAX_CLOSURE_PASSES) {
                throw new IllegalStateException("外键闭包 " + MAX_CLOSURE_PASSES + " 轮仍未收敛: " + frontier.keySet());
            }
            Map<String, Set<String>> next = new HashMap<>();
            for (Fk fk : meta.fks()) {
                if (!fk.expands()) continue;
                Set<String> parentKeys = frontier.get(fk.parent());
                if (parentKeys == null || parentKeys.isEmpty()) continue;
                expand(conn, schema, meta, fk, parentKeys, scope, next);
            }
            frontier = next;
        }
        scope.setClosurePasses(pass);

        // 3) 置空类外键: 用最终的本厂行集合统计
        for (Fk fk : meta.fks()) {
            if (fk.expands()) continue;
            TableScope parent = scope.table(fk.parent());
            if (parent == null || parent.keys().isEmpty()) continue;
            countNulled(conn, schema, meta, fk, parent.keys(), scope);
        }

        // 4) 顺外键认领的子行 (不带租户列) 若同时引用了别的工厂的行 (例: 两厂订单之间的关联行), 不能算本厂的
        for (Fk fk : meta.fks()) {
            TableScope child = scope.table(fk.child());
            if (child == null || child.depth() == 0 || child.keys().isEmpty()) continue;
            checkOtherParents(conn, schema, meta, fk, child, scope);
        }

        // 5) 父表没有单列主键的外键算不了闭包: 父表里有本厂行、子表又不带租户列 ⇒ 子行归属判不了, 记为无法判定
        for (Fk fk : meta.droppedFks()) {
            if (!fk.expands()) continue;
            TableScope parent = scope.table(fk.parent());
            if (parent == null || parent.rows() == 0) continue;
            if (!FactoryDataCatalog.tenantColumns(fk.child(), new LinkedHashSet<>(meta.columns().get(fk.child()))).isEmpty()) {
                continue;
            }
            scope.addForeign(new ForeignHit(fk.child(), rowKey(null), ForeignKind.UNSUPPORTED,
                    "父表 " + fk.parent() + " 无单列主键, 无法逐行判定引用它的子行", fk.parent()));
        }
        return scope;
    }

    /**
     * 按「谁能看见」正确地算范围。SmartBI 有行级安全 (2026-10-26 prod: 95 张表是严格策略, 只认
     * {@code factory_id = app.factory_id}; 30 张是宽松策略, app.factory_id 为空时可见全部):
     * <ul>
     *   <li>本厂的行在 app.factory_id = 本厂 时算 —— 严格表只有这样才看得见本厂的行;</li>
     *   <li>跨租户命中再在 app.factory_id 为空时算一遍并入 —— 宽松表只有这样才看得见别的工厂的行。</li>
     * </ul>
     * 两次设置都是事务内 ({@code set_config(..., true)})。返回时连接停在「本厂可见」, 后续导出 / 删除直接读写本厂行。
     */
    public static FactoryDataScope resolveVisible(Connection conn, Database database, String schema, String factoryId)
            throws SQLException {
        if (database != Database.SMARTBI) return resolve(conn, database, schema, factoryId);
        setTenant(conn, "");
        FactoryDataScope wide = resolve(conn, database, schema, factoryId);
        setTenant(conn, factoryId);
        FactoryDataScope own = resolve(conn, database, schema, factoryId);
        own.mergeForeignHits(wide.foreignHits());
        return own;
    }

    /** SmartBI 行级安全的租户变量, 只在本事务内有效。 */
    public static void setTenant(Connection conn, String factoryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.factory_id', ?, true)")) {
            ps.setString(1, factoryId);
            ps.execute();
        }
    }

    /**
     * 已认领的子行 (不带租户列) 经这条外键指向的父行若不是本厂的 ⇒ 跨租户命中。
     * <ul>
     *   <li>父表是 factories: 父行的主键就是工厂编号;</li>
     *   <li>父表带租户列: 看租户列;</li>
     *   <li>父行看不见 (SmartBI 严格行级安全下别的工厂的行): LEFT JOIN 取不到 ⇒ 归属不明, 记 UNSCOPED;</li>
     *   <li>父表不带租户列、也不是 factories (平台共享字典等): 引用它不改变子行归属, 不查。</li>
     * </ul>
     * 只查一层: 父行本身也是「不带租户列、经别的工厂外键链挂着」的情形不在内 (手册 §4 写明)。
     */
    private static void checkOtherParents(Connection conn, String schema, Meta meta, Fk fk, TableScope child,
                                          FactoryDataScope scope) throws SQLException {
        String parentPk = meta.singlePk().get(fk.parent());
        boolean parentIsFactories = scope.database() == Database.CRETAS
                && FactoryDataCatalog.FACTORIES_TABLE.equals(fk.parent());
        List<String> parentTenant = parentIsFactories ? List.of(parentPk)
                : FactoryDataCatalog.tenantColumns(fk.parent(), new LinkedHashSet<>(meta.columns().get(fk.parent())));
        if (parentTenant.isEmpty()) return;
        TableScope ownParent = scope.table(fk.parent());
        StringJoiner select = new StringJoiner(", ");
        select.add("c." + q(child.pkColumn()) + "::text");
        select.add("p." + q(parentPk) + " IS NULL");
        parentTenant.forEach(col -> select.add("p." + q(col) + "::text"));
        StringJoiner on = new StringJoiner(" AND ");
        StringJoiner notNull = new StringJoiner(" AND ");
        for (int i = 0; i < fk.childColumns().size(); i++) {
            on.add("c." + q(fk.childColumns().get(i)) + " = p." + q(fk.parentColumns().get(i)));
            notNull.add("c." + q(fk.childColumns().get(i)) + " IS NOT NULL");
        }
        String sql = "SELECT " + select + " FROM " + q(schema) + "." + q(fk.child()) + " c LEFT JOIN " + q(schema) + "."
                + q(fk.parent()) + " p ON " + on + " WHERE c." + q(child.pkColumn()) + "::text = ANY(?) AND " + notNull
                + " AND (p." + q(parentPk) + " IS NULL OR NOT (p." + q(parentPk) + "::text = ANY(?)))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", child.keys().toArray(new String[0])));
            ps.setArray(2, conn.createArrayOf("text",
                    ownParent == null ? new String[0] : ownParent.keys().toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (rs.getBoolean(2)) {
                        scope.addForeign(new ForeignHit(fk.child(), rowKey(key), ForeignKind.UNSCOPED,
                                "引用的 " + fk.parent() + " 行在当前身份下看不见 (可能是别的工厂的)", fk.parent()));
                        continue;
                    }
                    String other = null;
                    boolean own = false;
                    for (int i = 0; i < parentTenant.size(); i++) {
                        String v = rs.getString(3 + i);
                        if (v == null) continue;
                        if (v.equals(scope.factoryId())) own = true;
                        else if (other == null) other = parentTenant.get(i) + "=" + v;
                    }
                    if (own && other == null) continue;
                    scope.addForeign(new ForeignHit(fk.child(), rowKey(key),
                            other != null ? ForeignKind.OTHER_TENANT : ForeignKind.UNSCOPED,
                            "同时引用 " + fk.parent() + " 的行 (" + (other != null ? other : "租户列为空") + ")", fk.parent()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ seeds

    private static void seedFactoryRoot(Connection conn, String schema, Meta meta, FactoryDataScope scope,
                                        Map<String, Set<String>> frontier) throws SQLException {
        String pk = meta.singlePk().get(FactoryDataCatalog.FACTORIES_TABLE);
        if (pk == null) throw new IllegalStateException("factories 没有单列主键");
        String sql = "SELECT " + q(pk) + "::text FROM " + q(schema) + "." + q(FactoryDataCatalog.FACTORIES_TABLE)
                + " WHERE " + q(pk) + "::text = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope.factoryId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                TableScope root = scope.putTable(new TableScope(FactoryDataCatalog.FACTORIES_TABLE,
                        Category.FACTORY_ROOT, List.of(), pk, 0));
                root.addKey(rs.getString(1));
                frontier.computeIfAbsent(root.table(), k -> new LinkedHashSet<>()).add(rs.getString(1));
            }
        }
    }

    private static void seedTable(Connection conn, String schema, String table, List<String> tenant, String pk,
                                  FactoryDataScope scope, Map<String, Set<String>> frontier) throws SQLException {
        StringJoiner select = new StringJoiner(", ");
        select.add(pk != null ? q(pk) + "::text" : "NULL::text");
        tenant.forEach(c -> select.add(q(c) + "::text"));
        StringJoiner where = new StringJoiner(" OR ");
        tenant.forEach(c -> where.add(q(c) + "::text = ?"));
        String sql = "SELECT " + select + " FROM " + q(schema) + "." + q(table) + " WHERE " + where;

        TableScope ts = new TableScope(table, FactoryDataCatalog.categorize(table), tenant, pk, 0);
        long owned = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < tenant.size(); i++) ps.setString(i + 1, scope.factoryId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String other = otherTenant(rs, 2, tenant, scope.factoryId());
                    if (other != null) {
                        scope.addForeign(new ForeignHit(table, rowKey(key), ForeignKind.SHARED_ROW, other, null));
                        continue;
                    }
                    owned++;
                    if (pk != null && ts.addKey(key)) {
                        frontier.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(key);
                    }
                }
            }
        }
        if (pk == null) ts.setPredicateRows(owned);
        scope.putTable(ts);
    }

    // ---------------------------------------------------------------- closure

    private static void expand(Connection conn, String schema, Meta meta, Fk fk, Set<String> parentKeys,
                               FactoryDataScope scope, Map<String, Set<String>> next) throws SQLException {
        String childPk = meta.singlePk().get(fk.child());
        List<String> childTenant = FactoryDataCatalog.tenantColumns(fk.child(),
                new LinkedHashSet<>(meta.columns().get(fk.child())));
        try (PreparedStatement ps = conn.prepareStatement(joinSql(schema, meta, fk, childPk, childTenant))) {
            Array keys = conn.createArrayOf("text", parentKeys.toArray(new String[0]));
            ps.setArray(1, keys);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (!childTenant.isEmpty()) {
                        // 带租户列的子行: 本厂的已是种子; 别的工厂或空值 = 跨租户命中
                        classifyTenantChild(rs, key, childTenant, fk, scope);
                        continue;
                    }
                    if (childPk == null) {
                        scope.addForeign(new ForeignHit(fk.child(), rowKey(null), ForeignKind.UNSUPPORTED,
                                "子表既无租户列也无单列主键", fk.parent()));
                        continue;
                    }
                    TableScope ts = scope.table(fk.child());
                    if (ts == null) {
                        int depth = scope.table(fk.parent()).depth() + 1;
                        ts = scope.putTable(new TableScope(fk.child(), FactoryDataCatalog.categorize(fk.child()),
                                List.of(), childPk, depth));
                    }
                    if (ts.addKey(key)) next.computeIfAbsent(fk.child(), k -> new LinkedHashSet<>()).add(key);
                }
            }
        }
    }

    private static void classifyTenantChild(ResultSet rs, String key, List<String> tenant, Fk fk,
                                            FactoryDataScope scope) throws SQLException {
        boolean anyOwn = false;
        String other = null;
        for (int i = 0; i < tenant.size(); i++) {
            String v = rs.getString(2 + i);
            if (v == null) continue;
            if (v.equals(scope.factoryId())) anyOwn = true;
            else if (other == null) other = tenant.get(i) + "=" + v;
        }
        if (anyOwn && other == null) return;            // 本厂种子, 已计入
        if (anyOwn) return;                             // 共享行, 种子阶段已记 SHARED_ROW
        if (other != null) {
            scope.addForeign(new ForeignHit(fk.child(), rowKey(key), ForeignKind.OTHER_TENANT, other, fk.parent()));
        } else {
            scope.addForeign(new ForeignHit(fk.child(), rowKey(key), ForeignKind.UNSCOPED,
                    String.join("/", tenant) + " 为空", fk.parent()));
        }
    }

    private static void countNulled(Connection conn, String schema, Meta meta, Fk fk, Set<String> parentKeys,
                                    FactoryDataScope scope) throws SQLException {
        String childPk = meta.singlePk().get(fk.child());
        List<String> childTenant = FactoryDataCatalog.tenantColumns(fk.child(),
                new LinkedHashSet<>(meta.columns().get(fk.child())));
        TableScope ownChild = scope.table(fk.child());
        long nulled = 0;
        try (PreparedStatement ps = conn.prepareStatement(joinSql(schema, meta, fk, childPk, childTenant))) {
            ps.setArray(1, conn.createArrayOf("text", parentKeys.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (ownChild != null && key != null && ownChild.keys().contains(key)) continue;
                    if (!childTenant.isEmpty()) {
                        String other = null;
                        boolean own = false;
                        for (int i = 0; i < childTenant.size(); i++) {
                            String v = rs.getString(2 + i);
                            if (v == null) continue;
                            if (v.equals(scope.factoryId())) own = true;
                            else if (other == null) other = childTenant.get(i) + "=" + v;
                        }
                        if (own) continue;
                        if (other != null) {
                            scope.addForeign(new ForeignHit(fk.child(), rowKey(key), ForeignKind.OTHER_TENANT,
                                    other + " (外键置空)", fk.parent()));
                            continue;
                        }
                    }
                    nulled++;
                }
            }
        }
        if (nulled > 0) {
            scope.addNulled(new NulledReference(fk.child(), String.join(",", fk.childColumns()), nulled, fk.parent()));
        }
    }

    private static String joinSql(String schema, Meta meta, Fk fk, String childPk, List<String> childTenant) {
        String parentPk = meta.singlePk().get(fk.parent());
        StringJoiner select = new StringJoiner(", ");
        select.add(childPk != null ? "c." + q(childPk) + "::text" : "NULL::text");
        childTenant.forEach(col -> select.add("c." + q(col) + "::text"));
        StringJoiner on = new StringJoiner(" AND ");
        for (int i = 0; i < fk.childColumns().size(); i++) {
            on.add("c." + q(fk.childColumns().get(i)) + " = p." + q(fk.parentColumns().get(i)));
        }
        return "SELECT " + select + " FROM " + q(schema) + "." + q(fk.child()) + " c JOIN "
                + q(schema) + "." + q(fk.parent()) + " p ON " + on
                + " WHERE p." + q(parentPk) + "::text = ANY(?)";
    }

    // --------------------------------------------------------------- metadata

    private static Meta loadMeta(Connection conn, String schema) throws SQLException {
        Map<String, List<String>> columns = new TreeMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.relname, a.attname FROM pg_attribute a "
                        + "JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relkind IN ('r','p') AND NOT c.relispartition "
                        + "AND a.attnum > 0 AND NOT a.attisdropped ORDER BY c.relname, a.attnum")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        Map<String, String> singlePk = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.relname, a.attname FROM pg_constraint p "
                        + "JOIN pg_class c ON c.oid = p.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "JOIN pg_attribute a ON a.attrelid = p.conrelid AND a.attnum = p.conkey[1] "
                        + "WHERE n.nspname = ? AND p.contype = 'p' AND array_length(p.conkey, 1) = 1")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) singlePk.put(rs.getString(1), rs.getString(2));
            }
        }
        List<Fk> fks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cc.relname, pc.relname, con.confdeltype::text, "
                        + "array(SELECT a.attname::text FROM unnest(con.conkey) WITH ORDINALITY k(n, o) "
                        + "      JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.n ORDER BY k.o), "
                        + "array(SELECT a.attname::text FROM unnest(con.confkey) WITH ORDINALITY k(n, o) "
                        + "      JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.n ORDER BY k.o) "
                        + "FROM pg_constraint con "
                        + "JOIN pg_class cc ON cc.oid = con.conrelid JOIN pg_namespace cn ON cn.oid = cc.relnamespace "
                        + "JOIN pg_class pc ON pc.oid = con.confrelid JOIN pg_namespace pn ON pn.oid = pc.relnamespace "
                        + "WHERE con.contype = 'f' AND con.conparentid = 0 AND cn.nspname = ? AND pn.nspname = ? "
                        + "AND NOT cc.relispartition ORDER BY cc.relname, con.conname")) {
            ps.setString(1, schema);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fks.add(new Fk(rs.getString(1), rs.getString(2), rs.getString(3).charAt(0),
                            List.of((String[]) rs.getArray(4).getArray()),
                            List.of((String[]) rs.getArray(5).getArray())));
                }
            }
        }
        // 只保留两端都在 columns 里的外键 (分区子表已被排除); 父表没有单列主键的另放一边, 不能做闭包但要报出来
        fks.removeIf(fk -> !columns.containsKey(fk.child()) || !columns.containsKey(fk.parent()));
        List<Fk> dropped = new ArrayList<>();
        fks.removeIf(fk -> {
            if (singlePk.containsKey(fk.parent())) return false;
            dropped.add(fk);
            return true;
        });
        return new Meta(columns, singlePk, fks, dropped);
    }

    // ---------------------------------------------------------------- helpers

    private static String otherTenant(ResultSet rs, int firstIndex, List<String> tenant, String factoryId)
            throws SQLException {
        for (int i = 0; i < tenant.size(); i++) {
            String v = rs.getString(firstIndex + i);
            if (v != null && !v.equals(factoryId)) return tenant.get(i) + "=" + v;
        }
        return null;
    }

    private static String rowKey(String key) {
        return key != null ? key : "(无单列主键)";
    }

    /** PostgreSQL 标识符引用。 */
    static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 选中本厂行的 WHERE 条件 (不含 WHERE 关键字) 与它的参数: 导出 / 计数 / 删除共用。 */
    record Where(String sql, List<Object> params) {}

    static Where ownedRows(Connection conn, TableScope ts, String factoryId) throws SQLException {
        return ownedRows(conn, ts, factoryId, "");
    }

    /** @param alias 列名前缀 (表别名加点, 如 {@code "t."}; 不用别名传空串) */
    static Where ownedRows(Connection conn, TableScope ts, String factoryId, String alias) throws SQLException {
        if (ts.selection() == FactoryDataScope.Selection.KEYS) {
            return new Where(alias + q(ts.pkColumn()) + "::text = ANY(?)",
                    List.of(conn.createArrayOf("text", ts.keys().toArray(new String[0]))));
        }
        // 无单列主键: 至少一个租户列是本厂, 且没有租户列是别的工厂
        StringJoiner any = new StringJoiner(" OR ", "(", ")");
        StringJoiner none = new StringJoiner(" AND ");
        List<Object> params = new ArrayList<>();
        for (String c : ts.tenantColumns()) {
            any.add(alias + q(c) + "::text = ?");
            params.add(factoryId);
        }
        for (String c : ts.tenantColumns()) {
            none.add("(" + alias + q(c) + " IS NULL OR " + alias + q(c) + "::text = ?)");
            params.add(factoryId);
        }
        return new Where(any + " AND " + none, params);
    }

    static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    /**
     * 按工厂分组的行数 (每张表的每个租户列各算一次再求和): 删除前后用它做「其他工厂一行都没少」的阴性对照。
     *
     * @param rlsTenants 非空时 (SmartBI) 逐个工厂把 app.factory_id 设成它再数 —— 严格行级安全的表只有这样
     *                   才数得到它的行; 为空时 (主库, 无行级安全) 直接 GROUP BY
     */
    public static Map<String, Long> rowsPerFactory(Connection conn, String schema, Set<String> rlsTenants)
            throws SQLException {
        Meta meta = loadMeta(conn, schema);
        List<String[]> pairs = new ArrayList<>();      // {table, tenantColumn}
        for (Map.Entry<String, List<String>> e : meta.columns().entrySet()) {
            for (String c : FactoryDataCatalog.tenantColumns(e.getKey(), new LinkedHashSet<>(e.getValue()))) {
                pairs.add(new String[]{e.getKey(), c});
            }
        }
        Map<String, Long> totals = new TreeMap<>();
        if (rlsTenants == null) {
            for (String[] p : pairs) {
                String sql = "SELECT " + q(p[1]) + "::text, count(*) FROM " + q(schema) + "." + q(p[0])
                        + " WHERE " + q(p[1]) + " IS NOT NULL GROUP BY 1";
                try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) totals.merge(rs.getString(1), rs.getLong(2), Long::sum);
                }
            }
            return totals;
        }
        for (String tenant : rlsTenants) {
            setTenant(conn, tenant);
            long n = 0;
            for (String[] p : pairs) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + q(schema) + "." + q(p[0])
                        + " WHERE " + q(p[1]) + "::text = ?")) {
                    ps.setString(1, tenant);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        n += rs.getLong(1);
                    }
                }
            }
            if (n > 0) totals.put(tenant, n);
        }
        setTenant(conn, "");
        return totals;
    }

    /** 主库无行级安全时的旧签名。 */
    public static Map<String, Long> rowsPerFactory(Connection conn, String schema) throws SQLException {
        return rowsPerFactory(conn, schema, null);
    }

    /** 库里出现过的全部租户编号 (各租户列的去重值; 行级安全下只看得见宽松表里的)。 */
    public static Set<String> tenantValues(Connection conn, String schema) throws SQLException {
        Meta meta = loadMeta(conn, schema);
        Set<String> out = new java.util.TreeSet<>();
        for (Map.Entry<String, List<String>> e : meta.columns().entrySet()) {
            for (String c : FactoryDataCatalog.tenantColumns(e.getKey(), new LinkedHashSet<>(e.getValue()))) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT " + q(c) + "::text FROM "
                        + q(schema) + "." + q(e.getKey()) + " WHERE " + q(c) + " IS NOT NULL");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
