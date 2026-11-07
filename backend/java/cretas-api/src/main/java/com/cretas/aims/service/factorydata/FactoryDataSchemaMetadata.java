package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;
import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Captures source-time PostgreSQL structure for an export package. */
public final class FactoryDataSchemaMetadata {

    private FactoryDataSchemaMetadata() {}
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public record Snapshot(Map<String, Object> schema, Map<String, Object> migrations) {}

    /**
     * Reads metadata from the same repeatable-read source connection as the rows.
     * The returned data is descriptive only; it never claims that the destination
     * schema is compatible or silently fills in missing migration history.
     */
    public static Snapshot capture(Connection conn, Database database, String schema,
                                   List<TableScope> tables) throws SQLException {
        // The metadata queries use pg_get_* definitions.  Bind the schema and
        // pg_catalog explicitly so source descriptors do not depend on a
        // caller's mutable search_path.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('search_path', quote_ident(?) || ', pg_catalog', true)")) {
            ps.setString(1, schema);
            ps.executeQuery().close();
        }
        Set<String> names = tables.stream().map(TableScope::table).collect(Collectors.toCollection(TreeSet::new));
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("format", "cretas-factory-source-schema/2");
        structure.put("database", database.name());
        structure.put("sourceSchema", schema);
        structure.put("tables", tableScopes(tables));
        structure.put("columns", columns(conn, schema, names));
        List<Map<String, Object>> constraintMetadata = constraints(conn, schema, names);
        Set<String> dependencyNames = constraintMetadata.stream()
                .map(c -> c.get("referencedTable"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(t -> !names.contains(t))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> structureNames = new TreeSet<>(names);
        structureNames.addAll(dependencyNames);
        List<Map<String, Object>> columnMetadata = columns(conn, schema, structureNames);
        validateColumnTypes(columnMetadata);
        validateRedactionConstraints(conn, schema, columnMetadata, constraintMetadata);
        structure.put("columns", columnMetadata);
        structure.put("constraints", constraintMetadata);
        List<Map<String, Object>> externalReferences = externalReferences(constraintMetadata, names);
        if (!externalReferences.isEmpty()) {
            throw new IllegalStateException("导出表存在未随包提供的外部外键，拒绝生成可恢复包: " + externalReferences);
        }
        structure.put("externalReferences", externalReferences);
        structure.put("indexes", indexes(conn, schema, structureNames));
        structure.put("sequences", sequences(conn, schema));
        structure.put("triggers", triggers(conn, schema, structureNames));
        structure.put("dependencyTables", dependencyNames);
        structure.put("snapshot", snapshot(conn));
        structure.put("restoreContract", "COMPATIBLE_SCHEMA_REQUIRED");
        structure.put("restoreStatus", "REQUIRES_PROTECTED_SCHEMA_BASELINE");
        structure.put("rawDdlIncluded", false);
        structure.put("coveredObjects", List.of("tables", "columns", "constraints", "indexes", "sequences", "triggers"));
        structure.put("fingerprintAlgorithm", "SHA-256");
        structure.put("fingerprint", fingerprint(structure));
        Map<String, Object> migrationMetadata = migrations(conn, database, schema);
        if (!Boolean.TRUE.equals(migrationMetadata.get("available"))
                || !(migrationMetadata.get("fingerprint") instanceof String fingerprint)
                || fingerprint.isBlank()) {
            throw new IllegalStateException("源库缺少可验证的 migration ledger，拒绝生成成功 manifest: " + database);
        }
        return new Snapshot(structure, migrationMetadata);
    }

    private static List<Map<String, Object>> tableScopes(List<TableScope> tables) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableScope table : tables) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table.table());
            row.put("category", table.category().name());
            row.put("depth", table.depth());
            row.put("rows", table.rows());
            row.put("selection", table.selection().name());
            row.put("primaryKeyColumn", table.pkColumn());
            row.put("tenantColumns", table.tenantColumns());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> columns(Connection conn, String schema, Set<String> tables)
            throws SQLException {
        if (tables.isEmpty()) return List.of();
        String sql = "SELECT c.table_name, c.column_name, c.ordinal_position, c.data_type, c.udt_name, c.is_nullable, "
                + "column_default, character_maximum_length, numeric_precision, numeric_scale, datetime_precision, "
                + "c.is_identity, c.identity_generation, c.is_generated, c.generation_expression, "
                + "format_type(pa.atttypid, pa.atttypmod) AS type_signature, "
                + "EXISTS(SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid=d.refobjid "
                + "WHERE d.classid='pg_type'::regclass AND d.objid=pa.atttypid AND d.deptype='e' "
                + "AND e.extname='vector') AS extension_vector_type "
                + "FROM information_schema.columns c "
                + "LEFT JOIN pg_namespace pn ON pn.nspname = c.table_schema "
                + "LEFT JOIN pg_class pc ON pc.relnamespace = pn.oid AND pc.relname = c.table_name "
                + "LEFT JOIN pg_attribute pa ON pa.attrelid = pc.oid AND pa.attname = c.column_name AND NOT pa.attisdropped "
                + "WHERE c.table_schema = ? AND c.table_name IN ("
                + placeholders(tables.size()) + ") ORDER BY c.table_name, c.ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, schema);
            for (String table : tables) ps.setString(i++, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    if ("USER-DEFINED".equals(rs.getString("data_type"))
                            && "vector".equals(rs.getString("udt_name"))
                            && !rs.getBoolean("extension_vector_type")) {
                        throw new IllegalStateException("当前列不是 pgvector 扩展拥有的类型，拒绝生成包: "
                                + rs.getString("table_name") + "." + rs.getString("column_name"));
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", rs.getString("table_name"));
                    row.put("column", rs.getString("column_name"));
                    row.put("ordinal", rs.getInt("ordinal_position"));
                    row.put("dataType", rs.getString("data_type"));
                    row.put("udtName", rs.getString("udt_name"));
                    row.put("nullable", "YES".equals(rs.getString("is_nullable")));
                    row.put("defaultFingerprint", sha256Text(rs.getString("column_default")));
                    row.put("characterMaxLength", rs.getObject("character_maximum_length"));
                    row.put("numericPrecision", rs.getObject("numeric_precision"));
                    row.put("numericScale", rs.getObject("numeric_scale"));
                    row.put("datetimePrecision", rs.getObject("datetime_precision"));
                    row.put("identity", "YES".equals(rs.getString("is_identity")));
                    row.put("identityGeneration", rs.getString("identity_generation"));
                    row.put("generated", "ALWAYS".equals(rs.getString("is_generated")));
                    row.put("generationFingerprint", sha256Text(rs.getString("generation_expression")));
                    row.put("typeSignatureFingerprint", sha256Text(rs.getString("type_signature")));
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static List<Map<String, Object>> constraints(Connection conn, String schema, Set<String> tables)
            throws SQLException {
        if (tables.isEmpty()) return List.of();
        String sql = "SELECT tc.table_name, tc.constraint_name, tc.constraint_type, kcu.column_name, "
                + "kcu.ordinal_position, ccu.table_name AS referenced_table, ccu.column_name AS referenced_column, "
                + "rc.update_rule, rc.delete_rule, (SELECT md5(pg_get_constraintdef(pc.oid)) FROM pg_constraint pc "
                + "JOIN pg_class pr ON pr.oid = pc.conrelid JOIN pg_namespace pn ON pn.oid = pr.relnamespace "
                + "WHERE pn.nspname = tc.table_schema AND pr.relname = tc.table_name "
                + "AND pc.conname = tc.constraint_name LIMIT 1) AS definition_fingerprint "
                + "FROM information_schema.table_constraints tc "
                + "LEFT JOIN information_schema.key_column_usage kcu ON kcu.constraint_schema = tc.constraint_schema "
                + "AND kcu.constraint_name = tc.constraint_name AND kcu.table_name = tc.table_name "
                + "LEFT JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_schema = tc.constraint_schema "
                + "AND ccu.constraint_name = tc.constraint_name "
                + "LEFT JOIN information_schema.referential_constraints rc ON rc.constraint_schema = tc.constraint_schema "
                + "AND rc.constraint_name = tc.constraint_name WHERE tc.table_schema = ? AND tc.table_name IN ("
                + placeholders(tables.size()) + ") ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, schema);
            for (String table : tables) ps.setString(i++, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", rs.getString("table_name"));
                    String constraintName = rs.getString("constraint_name");
                    if ("CHECK".equals(rs.getString("constraint_type"))
                            && constraintName != null && constraintName.matches("\\d+_\\d+_\\d+_not_null")) {
                        constraintName = "__NOT_NULL__";
                    }
                    row.put("name", constraintName);
                    row.put("type", rs.getString("constraint_type"));
                    row.put("column", rs.getString("column_name"));
                    row.put("ordinal", rs.getObject("ordinal_position"));
                    row.put("referencedTable", rs.getString("referenced_table"));
                    row.put("referencedColumn", rs.getString("referenced_column"));
                    row.put("updateRule", rs.getString("update_rule"));
                    row.put("deleteRule", rs.getString("delete_rule"));
                    row.put("definitionFingerprint", rs.getString("definition_fingerprint"));
                    out.add(row);
                }
                out.sort(java.util.Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("table")))
                        .thenComparing(row -> String.valueOf(row.get("name")))
                        .thenComparing(row -> String.valueOf(row.get("column")))
                        .thenComparing(row -> String.valueOf(row.get("ordinal")))
                        .thenComparing(row -> String.valueOf(row.get("referencedColumn"))));
                return out;
            }
        }
    }

    private static List<Map<String, Object>> indexes(Connection conn, String schema, Set<String> tables)
            throws SQLException {
        if (tables.isEmpty()) return List.of();
        String sql = "SELECT t.relname AS table_name, i.relname AS index_name, x.indisunique, x.indisprimary, "
                + "x.indisvalid, md5(pg_get_indexdef(i.oid)) AS index_fingerprint, "
                + "array_remove(array_agg(a.attname ORDER BY k.ordinality), NULL) AS columns "
                + "FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace "
                + "JOIN pg_index x ON x.indrelid = t.oid JOIN pg_class i ON i.oid = x.indexrelid "
                + "LEFT JOIN LATERAL unnest(x.indkey) WITH ORDINALITY k(attnum, ordinality) ON TRUE "
                + "LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                + "WHERE n.nspname = ? AND t.relname IN (" + placeholders(tables.size()) + ") "
                + "GROUP BY t.relname, i.relname, i.oid, x.indisunique, x.indisprimary, x.indisvalid "
                + "ORDER BY t.relname, i.relname";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, schema);
            for (String table : tables) ps.setString(i++, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", rs.getString("table_name"));
                    row.put("name", rs.getString("index_name"));
                    row.put("unique", rs.getBoolean("indisunique"));
                    row.put("primary", rs.getBoolean("indisprimary"));
                    row.put("valid", rs.getBoolean("indisvalid"));
                    row.put("fingerprint", rs.getString("index_fingerprint"));
                    java.sql.Array indexColumns = rs.getArray("columns");
                    row.put("columns", indexColumns == null ? List.of()
                            : java.util.Arrays.asList((Object[]) indexColumns.getArray()));
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static List<Map<String, Object>> sequences(Connection conn, String schema) throws SQLException {
        String sql = "SELECT s.sequence_name, s.data_type, s.start_value, s.minimum_value, s.maximum_value, "
                + "s.increment, s.cycle_option, p.cache_size, p.last_value, t.relname AS owned_table, a.attname AS owned_column "
                + "FROM information_schema.sequences s JOIN pg_namespace sn ON sn.nspname = s.sequence_schema "
                + "JOIN pg_class seq ON seq.relnamespace = sn.oid AND seq.relname = s.sequence_name "
                + "LEFT JOIN pg_sequences p ON p.schemaname = s.sequence_schema AND p.sequencename = s.sequence_name "
                + "LEFT JOIN pg_depend d ON d.objid = seq.oid AND d.deptype = 'a' "
                + "LEFT JOIN pg_class t ON t.oid = d.refobjid "
                + "LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid "
                + "WHERE s.sequence_schema = ? ORDER BY s.sequence_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("sequence_name"));
                    row.put("dataType", rs.getString("data_type"));
                    row.put("startValue", rs.getString("start_value"));
                    row.put("minimumValue", rs.getString("minimum_value"));
                    row.put("maximumValue", rs.getString("maximum_value"));
                    row.put("increment", rs.getString("increment"));
                    row.put("cycle", rs.getString("cycle_option"));
                    row.put("cacheSize", rs.getObject("cache_size"));
                    row.put("lastValue", rs.getObject("last_value"));
                    String sequence = rs.getString("sequence_name");
                    try (PreparedStatement state = conn.prepareStatement("SELECT is_called FROM "
                            + FactoryDataScopeResolver.q(schema) + "." + FactoryDataScopeResolver.q(sequence))) {
                        try (ResultSet stateRs = state.executeQuery()) {
                            row.put("isCalled", stateRs.next() && stateRs.getBoolean(1));
                        }
                    }
                    row.put("ownedTable", rs.getString("owned_table"));
                    row.put("ownedColumn", rs.getString("owned_column"));
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static Map<String, Object> migrations(Connection conn, Database database, String schema) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "cretas-factory-source-migrations/2");
        out.put("sourceSchema", schema);
        out.put("snapshot", snapshot(conn));
        String ledger = null;
        for (String candidate : database == Database.SMARTBI
                ? List.of("smartbi_migrations", "smartbi_schema_history", "flyway_schema_history")
                : List.of("flyway_schema_history", "cretas_schema_history")) {
            try (PreparedStatement exists = conn.prepareStatement("SELECT to_regclass(?)")) {
                exists.setString(1, schema + "." + candidate);
                try (ResultSet rs = exists.executeQuery()) {
                    rs.next();
                    if (rs.getString(1) != null) {
                        ledger = candidate;
                        break;
                    }
                }
            }
        }
        if (ledger == null) {
            out.put("available", false);
            out.put("reason", "SOURCE_MIGRATION_TABLE_ABSENT");
            return out;
        }
        out.put("ledgerTable", ledger);
        String qualified = FactoryDataScopeResolver.q(schema) + "." + FactoryDataScopeResolver.q(ledger);
        if ("smartbi_migrations".equals(ledger)) {
            out.put("ledgerKind", "SMARTBI");
            String smartbiSql = "SELECT filename, version, checksum, applied_at, applied_by, duration_ms FROM "
                    + qualified + " ORDER BY applied_at, filename";
            try (PreparedStatement ps = conn.prepareStatement(smartbiSql); ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int rank = 0;
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("installedRank", ++rank);
                    row.put("version", rs.getString("version"));
                    row.put("description", rs.getString("filename"));
                    row.put("type", "SMARTBI");
                    row.put("script", rs.getString("filename"));
                    row.put("checksum", rs.getObject("checksum"));
                    row.put("installedBy", rs.getString("applied_by"));
                    row.put("installedOn", rs.getTimestamp("applied_at"));
                    row.put("executionTime", rs.getInt("duration_ms"));
                    row.put("success", true);
                    rows.add(row);
                }
                out.put("available", true);
                out.put("rows", rows);
                out.put("fingerprintAlgorithm", "SHA-256");
                out.put("fingerprint", fingerprint(rows));
                return out;
            }
        }
        out.put("ledgerKind", "FLYWAY");
        String sql = "SELECT installed_rank, version, description, type, script, checksum, installed_by, "
                + "installed_on, execution_time, success FROM " + qualified + " ORDER BY installed_rank";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("installedRank", rs.getInt("installed_rank"));
                row.put("version", rs.getString("version"));
                row.put("description", rs.getString("description"));
                row.put("type", rs.getString("type"));
                row.put("script", rs.getString("script"));
                row.put("checksum", rs.getObject("checksum"));
                row.put("installedBy", rs.getString("installed_by"));
                row.put("installedOn", rs.getTimestamp("installed_on"));
                row.put("executionTime", rs.getInt("execution_time"));
                row.put("success", rs.getBoolean("success"));
                rows.add(row);
            }
            out.put("available", true);
            out.put("rows", rows);
            out.put("fingerprintAlgorithm", "SHA-256");
            out.put("fingerprint", fingerprint(rows));
            return out;
        }
    }

    private static Map<String, Object> snapshot(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT current_database(), pg_current_snapshot()::text, txid_current(), clock_timestamp()::text");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("database", rs.getString(1));
            out.put("pgSnapshot", rs.getString(2));
            out.put("transactionId", rs.getLong(3));
            out.put("capturedAt", rs.getString(4));
            out.put("crossDatabaseAtomic", false);
            return out;
        }
    }

    private static String fingerprint(Object value) {
        try {
            Object fingerprintValue = value;
            if (value instanceof Map<?, ?> source) {
                Map<String, Object> stable = new LinkedHashMap<>();
                source.forEach((key, item) -> {
                    if (!("fingerprint".equals(key) || "snapshot".equals(key) || "tables".equals(key))) {
                        stable.put(String.valueOf(key), item);
                    }
                });
                if (source.get("tables") instanceof List<?> tableList) {
                    List<Map<String, Object>> tables = new ArrayList<>();
                    for (Object item : tableList) {
                        if (!(item instanceof Map<?, ?> table)) continue;
                        Map<String, Object> stableTable = new LinkedHashMap<>();
                        table.forEach((key, tableValue) -> {
                            if (!("rows".equals(key) || "depth".equals(key))) {
                                stableTable.put(String.valueOf(key), tableValue);
                            }
                        });
                        tables.add(stableTable);
                    }
                    stable.put("tables", tables);
                }
                fingerprintValue = stable;
            }
            byte[] bytes = JSON.writeValueAsBytes(fingerprintValue);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("生成源结构指纹失败", e);
        }
    }

    private static List<Map<String, Object>> triggers(Connection conn, String schema, Set<String> tables)
            throws SQLException {
        if (tables.isEmpty()) return List.of();
        String sql = "SELECT c.relname AS table_name, t.tgname, p.proname AS function_name, "
                + "md5(pg_get_functiondef(t.tgfoid)) AS function_fingerprint, "
                + "md5(pg_get_triggerdef(t.oid)) AS trigger_fingerprint "
                + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_proc p ON p.oid = t.tgfoid "
                + "WHERE n.nspname = ? AND NOT t.tgisinternal AND c.relname IN (" + placeholders(tables.size()) + ") "
                + "ORDER BY c.relname, t.tgname";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, schema);
            for (String table : tables) ps.setString(i++, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", rs.getString("table_name"));
                    row.put("name", rs.getString("tgname"));
                    row.put("function", rs.getString("function_name"));
                    row.put("functionFingerprint", rs.getString("function_fingerprint"));
                    row.put("triggerFingerprint", rs.getString("trigger_fingerprint"));
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static void validateRedactionConstraints(Connection conn, String schema,
                                                     List<Map<String, Object>> columns,
                                                     List<Map<String, Object>> constraints) {
        Map<String, Integer> types = new java.util.HashMap<>();
        Set<String> sensitive = new java.util.HashSet<>();
        for (Map<String, Object> c : columns) {
            types.put(c.get("table") + "." + c.get("column"), jdbcType(String.valueOf(c.get("dataType"))));
            Integer type = types.get(c.get("table") + "." + c.get("column"));
            if (type != null && FactoryDataCatalog.shouldRedactColumn(String.valueOf(c.get("table")),
                    String.valueOf(c.get("column")), type, null)) {
                sensitive.add(c.get("table") + "." + c.get("column"));
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Map<String, Object> c : constraints) {
                if (!"FOREIGN KEY".equals(c.get("type"))) continue;
                String referenced = c.get("referencedTable") + "." + c.get("referencedColumn");
                String local = c.get("table") + "." + c.get("column");
                if (sensitive.contains(referenced)) changed |= sensitive.add(local);
            }
        } while (changed);
        for (Map<String, Object> column : columns) {
            String key = column.get("table") + "." + column.get("column");
            if (sensitive.contains(key) && (Boolean.TRUE.equals(column.get("generated"))
                    || Boolean.TRUE.equals(column.get("identity")))) {
                throw new IllegalStateException("凭证列为生成列或 identity，无法安全恢复: " + key);
            }
        }
        Map<String, Set<String>> primaryColumns = new java.util.HashMap<>();
        for (Map<String, Object> constraint : constraints) {
            if ("PRIMARY KEY".equals(constraint.get("type"))) {
                primaryColumns.computeIfAbsent(constraint.get("table") + "." + constraint.get("name"),
                        ignored -> new java.util.HashSet<>()).add(String.valueOf(constraint.get("column")));
            }
        }
        for (Map<String, Object> c : constraints) {
            String type = String.valueOf(c.get("type"));
            String key = c.get("table") + "." + c.get("column");
            if ("PRIMARY KEY".equals(type) && sensitive.contains(key)
                    && primaryColumns.get(c.get("table") + "." + c.get("name")).size() != 1) {
                throw new IllegalStateException("凭证列参与复合主键，无法安全恢复: " + key);
            }
            // A standalone textual credential PK uses the same stable,
            // package-scoped HMAC as a UNIQUE credential column. Type, width
            // and CHECK safety gates still apply. Credential FK aliases must
            // refuse until the writer binds their shared redaction identity.
            if (!"FOREIGN KEY".equals(type)) continue;
            if (sensitive.contains(key)) {
                throw new IllegalStateException("凭证列或其外键别名参与外键，无法安全恢复: " + key);
            }
        }
        Set<String> sensitiveColumns = sensitive;
        String checkSql = "SELECT tc.table_name, tc.constraint_name, pg_get_constraintdef(pc.oid) "
                + "FROM information_schema.table_constraints tc "
                + "JOIN pg_class rel ON rel.relname = tc.table_name "
                + "JOIN pg_namespace ns ON ns.oid = rel.relnamespace AND ns.nspname = tc.table_schema "
                + "JOIN pg_constraint pc ON pc.conrelid = rel.oid AND pc.conname = tc.constraint_name "
                + "WHERE tc.table_schema = ? AND tc.constraint_type = 'CHECK'";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String definition = rs.getString(3);
                    if (definition == null) continue;
                    for (String sensitiveColumn : sensitiveColumns) {
                        String[] parts = sensitiveColumn.split("\\.", 2);
                        if (parts.length == 2 && parts[0].equals(table)
                                && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])"
                                + java.util.regex.Pattern.quote(parts[1])
                                + "(?![A-Za-z0-9_])", java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(definition).find()) {
                            throw new IllegalStateException("敏感列参与 CHECK 约束，无法证明脱敏后仍满足: "
                                    + table + "." + parts[1]);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("无法读取 CHECK 约束安全形状", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取 CHECK 约束安全形状", e);
        }
    }

    /**
     * The package has one typed JSON wire format.  PostgreSQL extension types
     * (vector, user-defined domains with no explicit codec, etc.) must stop the
     * export before a manifest is emitted; converting them with getString would
     * produce a package which cannot be restored without silently changing data.
     */
    private static void validateColumnTypes(List<Map<String, Object>> columns) {
        for (Map<String, Object> column : columns) {
            String dataType = String.valueOf(column.get("dataType"));
            String udtName = String.valueOf(column.get("udtName"));
            if ("ARRAY".equalsIgnoreCase(dataType)
                    && !Set.of("_int4", "_int8", "_text").contains(udtName.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException("导出表含未绑定数组类型，拒绝生成包: "
                        + column.get("table") + "." + column.get("column") + " (" + udtName + ")");
            }
            if ("USER-DEFINED".equalsIgnoreCase(dataType)
                    && !"vector".equalsIgnoreCase(udtName)) {
                throw new IllegalStateException("导出表含未绑定 PostgreSQL 扩展/自定义类型，拒绝生成包: "
                        + column.get("table") + "." + column.get("column") + " (" + udtName + ")");
            }
            int jdbcType = jdbcType(dataType);
            if (FactoryDataCatalog.shouldRedactColumn(String.valueOf(column.get("table")),
                    String.valueOf(column.get("column")), jdbcType,
                    column.get("numericScale") instanceof Number n ? n.intValue() : null)
                    && !isTextualDataType(dataType)) {
                throw new IllegalStateException("敏感列不是文本类型，拒绝生成可恢复包: "
                        + column.get("table") + "." + column.get("column") + " (" + dataType + ")");
            }
        }
    }

    private static boolean isTextualDataType(String dataType) {
        return Set.of("character varying", "text", "varchar",
                "nvarchar", "national character varying")
                .contains(dataType.toLowerCase(java.util.Locale.ROOT));
    }

    private static List<Map<String, Object>> externalReferences(List<Map<String, Object>> constraints,
                                                                 Set<String> tables) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : constraints) {
            if (!"FOREIGN KEY".equals(c.get("type")) || c.get("referencedTable") == null) continue;
            String referenced = String.valueOf(c.get("referencedTable"));
            if (tables.contains(referenced)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", c.get("table"));
            row.put("constraint", c.get("name"));
            row.put("column", c.get("column"));
            row.put("referencedTable", referenced);
            row.put("referencedColumn", c.get("referencedColumn"));
            row.put("action", "REQUIRES_COMPATIBLE_DESTINATION_BASELINE");
            out.add(row);
        }
        return out;
    }

    private static int jdbcType(String dataType) {
        return switch (dataType) {
            case "smallint" -> java.sql.Types.SMALLINT;
            case "integer" -> java.sql.Types.INTEGER;
            case "bigint" -> java.sql.Types.BIGINT;
            case "numeric" -> java.sql.Types.NUMERIC;
            case "decimal" -> java.sql.Types.DECIMAL;
            case "real" -> java.sql.Types.REAL;
            case "double precision" -> java.sql.Types.DOUBLE;
            case "date" -> java.sql.Types.DATE;
            case "time without time zone" -> java.sql.Types.TIME;
            case "time with time zone" -> java.sql.Types.TIME_WITH_TIMEZONE;
            case "timestamp without time zone" -> java.sql.Types.TIMESTAMP;
            case "timestamp with time zone" -> java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
            case "bytea" -> java.sql.Types.LONGVARBINARY;
            case "array" -> java.sql.Types.ARRAY;
            case "json", "jsonb", "uuid" -> java.sql.Types.OTHER;
            default -> java.sql.Types.VARCHAR;
        };
    }

    private static String sha256Text(String value) {
        if (value == null) return null;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("生成字段指纹失败", e);
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
