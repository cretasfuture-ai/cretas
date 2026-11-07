package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.lang.reflect.Array;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出包: 一个 ZIP, 每张表一个 CSV (UTF-8 带 BOM, Excel 可直接打开), 附件按对象键存放, manifest.json 最后写。
 *
 * <p>manifest 最后写 —— 包在中途出错被截断时没有 manifest, 收件人一眼就知道这个包不完整。
 */
public final class FactoryDataPackageWriter implements AutoCloseable {

    public record Entry(String path, long rows, long bytes, String sha256) {}

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final JsonFactory JSON = new JsonFactory();
    private static final int FETCH_SIZE = 1000;

    private final ZipOutputStream zip;
    private final List<Entry> entries = new ArrayList<>();
    private final byte[] redactionKey;

    public FactoryDataPackageWriter(OutputStream out) {
        this(out, FactoryDataRedaction.newKey());
    }

    public FactoryDataPackageWriter(OutputStream out, byte[] redactionKey) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.redactionKey = redactionKey.clone();
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    byte[] redactionKey() { return redactionKey.clone(); }

    @FunctionalInterface
    interface RowsReader<T> { T read(ResultSet rs) throws SQLException, IOException; }

    /** 在同一个快照里按「本厂拥有的行」查一张表 (按主键排序), 交给 reader。 */
    static <T> T queryOwned(Connection conn, String schema, TableScope ts, String factoryId, RowsReader<T> reader)
            throws SQLException, IOException {
        FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, ts, factoryId);
        String order = ts.pkColumn() != null ? " ORDER BY " + FactoryDataScopeResolver.q(ts.pkColumn()) : "";
        String sql = "SELECT * FROM " + FactoryDataScopeResolver.q(schema) + "." + FactoryDataScopeResolver.q(ts.table())
                + " WHERE " + where.sql() + order;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(FETCH_SIZE);
            FactoryDataScopeResolver.bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                return reader.read(rs);
            }
        }
    }

    /** 写成 {@code <dir>/<table>.json}: 保留 JDBC 数字/布尔类型; 凭证值替换为不含原文的唯一占位符。 */
    public Entry writeTableJson(Connection conn, String dir, String schema, TableScope ts, String factoryId)
            throws SQLException, IOException {
        String path = dir + "/" + ts.table() + ".json";
        return queryOwned(conn, schema, ts, factoryId, rs -> writeEntry(path, out -> {
            ResultSetMetaData md = rs.getMetaData();
            validateJsonTypes(md, ts.table());
            int n = md.getColumnCount();
            JsonGenerator g = JSON.createGenerator(new FilterOutputStream(out) {
                @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                @Override public void close() throws IOException { flush(); }
            });
            g.writeStartArray();
            long rows = 0;
            while (rs.next()) {
                g.writeStartObject();
                for (int i = 1; i <= n; i++) {
                    String name = md.getColumnName(i);
                    writeJsonValue(g, name, rs, md, i, ts.table(), redactionKey);
                }
                g.writeEndObject();
                rows++;
            }
            g.writeEndArray();
            g.close();
            return rows;
        }, false));
    }

    /** 把这张表加进该库的 Excel 工作簿 (工作簿在库写完后用 {@link #writeBook} 落进包里)。 */
    public long addToBook(Connection conn, FactoryDataXlsxBook book, String schema, TableScope ts, String factoryId)
            throws SQLException, IOException {
        return queryOwned(conn, schema, ts, factoryId, rs -> book.addTable(ts.table(), rs));
    }

    public Entry writeBook(String path, FactoryDataXlsxBook book) throws IOException {
        try {
            return writeEntry(path, out -> {
                book.write(new FilterOutputStream(out) {
                    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                    @Override public void close() throws IOException { flush(); }
                });
                return 0;
            }, false);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把本厂在这张表里的行写成 CSV; 凭证类列使用包级 HMAC 占位符。 */
    public Entry writeTable(Connection conn, String dir, String schema, TableScope ts, String factoryId)
            throws SQLException, IOException {
        String path = dir + "/" + ts.table() + ".csv";
        return queryOwned(conn, schema, ts, factoryId, rs -> {
                return writeEntry(path, out -> {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    boolean[] redact = new boolean[n + 1];
                    Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
                    for (int i = 1; i <= n; i++) {
                        redact[i] = FactoryDataCatalog.shouldRedactColumn(ts.table(), md.getColumnName(i),
                                md.getColumnType(i), md.getScale(i));
                        if (i > 1) w.write(',');
                        w.write(csv(md.getColumnName(i)));
                    }
                    w.write("\r\n");
                    long rows = 0;
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) w.write(',');
                            String v = rs.getString(i);
                            if (v != null) {
                                w.write(csv(redact[i]
                                        ? FactoryDataRedaction.value(ts.table(), md.getColumnName(i), v,
                                                redactionKey, md.getColumnDisplaySize(i))
                                        : v));
                            }
                        }
                        w.write("\r\n");
                        rows++;
                    }
                    w.flush();
                    return rows;
                }, true);
        });
    }

    private static void writeJsonValue(JsonGenerator g, String name, ResultSet rs,
                                       ResultSetMetaData md, int index, String table, byte[] redactionKey)
            throws SQLException, IOException {
        Object raw = rs.getObject(index);
        if (raw == null) {
            g.writeNullField(name);
            return;
        }
        if (FactoryDataCatalog.shouldRedactColumn(table, md.getColumnName(index), md.getColumnType(index),
                md.getScale(index))) {
            g.writeStringField(name, FactoryDataRedaction.value(table, md.getColumnName(index), raw.toString(),
                    redactionKey, md.getColumnDisplaySize(index)));
            return;
        }
        switch (md.getColumnType(index)) {
            case Types.BIT, Types.BOOLEAN -> g.writeBooleanField(name, rs.getBoolean(index));
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    g.writeNumberField(name, rs.getLong(index));
            case Types.DECIMAL, Types.NUMERIC -> g.writeNumberField(name, rs.getBigDecimal(index));
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                if (raw instanceof Number floating && !Double.isFinite(floating.doubleValue())) {
                    throw new IllegalStateException("浮点特殊值不支持安全 JSON 恢复: " + table + "." + name);
                }
                // Preserve IEEE negative zero and exact wire spelling. The restore
                // side binds this textual value to the catalogued float column.
                g.writeStringField(name, raw.toString());
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                byte[] value = rs.getBytes(index);
                g.writeStringField(name, "\\x" + HexFormat.of().formatHex(value));
            }
            case Types.DATE -> g.writeStringField(name, rs.getObject(index, java.time.LocalDate.class).toString());
            case Types.TIME -> {
                String typeName = md.getColumnTypeName(index).toLowerCase(java.util.Locale.ROOT);
                if (typeName.equals("timetz") || typeName.contains("with time zone")) {
                    g.writeStringField(name, rs.getObject(index, java.time.OffsetTime.class).toString());
                } else {
                    g.writeStringField(name, rs.getObject(index, java.time.LocalTime.class).toString());
                }
            }
            case Types.TIME_WITH_TIMEZONE ->
                    g.writeStringField(name, rs.getObject(index, java.time.OffsetTime.class).toString());
            case Types.TIMESTAMP -> {
                String typeName = md.getColumnTypeName(index).toLowerCase(java.util.Locale.ROOT);
                if (typeName.equals("timestamptz") || typeName.contains("with time zone")) {
                    g.writeStringField(name, rs.getObject(index, java.time.OffsetDateTime.class).toString());
                } else {
                    g.writeStringField(name, rs.getObject(index, java.time.LocalDateTime.class).toString());
                }
            }
            case Types.TIMESTAMP_WITH_TIMEZONE ->
                    g.writeStringField(name, rs.getObject(index, java.time.OffsetDateTime.class).toString());
            case Types.ARRAY -> {
                java.sql.Array array = rs.getArray(index);
                if (array == null) {
                    g.writeNullField(name);
                } else {
                    if (array.toString().startsWith("[")) {
                        throw new IllegalStateException("非默认数组下界不能无损 JSON 还原: " + table + "." + name);
                    }
                    g.writeFieldName(name);
                    writeArrayValue(g, array.getArray());
                }
            }
            case Types.OTHER -> {
                // PostgreSQL json/jsonb and uuid are represented as OTHER by the
                // JDBC driver, but have stable textual wire forms.
                String typeName = md.getColumnTypeName(index).toLowerCase(java.util.Locale.ROOT);
                if (isVectorTypeName(typeName)) {
                    String value = rs.getString(index);
                    validateVector(value, table, name);
                    g.writeStringField(name, value);
                } else if (!typeName.equals("json") && !typeName.equals("jsonb") && !typeName.equals("uuid")) {
                    throw new IllegalStateException("不支持的 PostgreSQL 类型，拒绝写出: " + table + "." + name
                            + " (" + typeName + ")");
                } else {
                    g.writeStringField(name, rs.getString(index));
                }
            }
            default -> g.writeStringField(name, rs.getString(index));
        }
    }

    /**
     * JSON is the restore wire format.  Reject unknown JDBC/PG types before
     * opening the ZIP entry; silently calling ResultSet#getString would lose
     * bytea, arrays, vector and extension-defined values.
     */
    static boolean supportedJsonType(int jdbcType, String typeName) {
        if (jdbcType == Types.ARRAY) {
            String normalized = typeName == null ? "" : typeName.toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("_int4") || normalized.equals("_int8") || normalized.equals("_text");
        }
        if (jdbcType == Types.OTHER) {
            String normalized = typeName == null ? "" : typeName.toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("json") || normalized.equals("jsonb") || normalized.equals("uuid")
                    || isVectorTypeName(normalized);
        }
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE,
                    Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR, Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE,
                    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> true;
            default -> false;
        };
    }

    private static boolean isVectorTypeName(String name) {
        // The canonical schema-only search_path intentionally excludes public.
        // PG JDBC therefore qualifies this verified public extension type.
        return "vector".equals(name) || "public.vector".equals(name)
                || "\"public\".\"vector\"".equals(name);
    }

    private static void validateJsonTypes(ResultSetMetaData md, String table) throws SQLException {
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (!supportedJsonType(md.getColumnType(i), md.getColumnTypeName(i))) {
                throw new IllegalStateException("不支持的 PostgreSQL 类型，拒绝导出: " + table + "."
                        + md.getColumnName(i) + " (" + md.getColumnTypeName(i) + ")");
            }
            if (FactoryDataCatalog.shouldRedactColumn(table, md.getColumnName(i), md.getColumnType(i), md.getScale(i))
                    && !isTextualJdbcType(md.getColumnType(i))) {
                throw new IllegalStateException("敏感列不是文本类型，拒绝写出: " + table + "."
                        + md.getColumnName(i) + " (" + md.getColumnTypeName(i) + ")");
            }
        }
    }

    private static boolean isTextualJdbcType(int jdbcType) {
        return jdbcType == Types.VARCHAR || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NVARCHAR || jdbcType == Types.LONGNVARCHAR;
    }

    private static void validateVector(String value, String table, String column) {
        if (value == null || value.length() < 2 || value.charAt(0) != '['
                || value.charAt(value.length() - 1) != ']') {
            throw new IllegalStateException("vector wire 值不合法，拒绝写出: " + table + "." + column);
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) throw new IllegalStateException("vector 不允许为空: " + table + "." + column);
        for (String part : body.split(",", -1)) {
            try {
                double component = Double.parseDouble(part.trim());
                if (!Double.isFinite(component)) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalStateException("vector 含非有限或非法分量，拒绝写出: " + table + "." + column);
            }
        }
    }

    private static void writeArrayValue(JsonGenerator g, Object value) throws IOException {
        if (value == null) {
            g.writeNull();
        } else if (value.getClass().isArray()) {
            g.writeStartArray();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) writeArrayValue(g, Array.get(value, i));
            g.writeEndArray();
        } else if (value instanceof Boolean b) {
            g.writeBoolean(b);
        } else if (value instanceof Number n) {
            if (n instanceof Double d && !Double.isFinite(d)
                    || n instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalStateException("数组含浮点特殊值，拒绝安全 JSON 恢复");
            }
            g.writeNumber(n.toString());
        } else if (value instanceof byte[] bytes) {
            g.writeString("\\x" + HexFormat.of().formatHex(bytes));
        } else {
            g.writeString(value.toString());
        }
    }

    /** 原样复制一个附件。 */
    public Entry writeEntryFromStore(String path, FactoryObjectStore store, FactoryObjectStore.StoredObject object)
            throws IOException {
        try {
            return writeEntry(path, out -> {
                store.copyTo(object, out);
                return 0;
            }, false);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 任意文本文件 (README、附件清单)。 */
    public Entry writeText(String path, String text) throws IOException {
        try {
            return writeEntry(path, out -> {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                return 0;
            }, false);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** manifest 不进 entries 清单 (它自己就是清单)。 */
    public void writeManifest(byte[] json) throws IOException {
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(json);
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        zip.finish();
        zip.flush();
    }

    @FunctionalInterface
    private interface Body {
        long write(OutputStream out) throws IOException, SQLException;
    }

    private Entry writeEntry(String path, Body body, boolean bom) throws IOException, SQLException {
        MessageDigest sha = sha256();
        path = uniquePath(safeEntryPath(path));
        zip.putNextEntry(new ZipEntry(path));
        CountingStream counting = new CountingStream(new NonClosing(zip));
        DigestOutputStream out = new DigestOutputStream(counting, sha);
        if (bom) out.write(BOM);
        long rows = body.write(out);
        out.flush();
        zip.closeEntry();
        Entry entry = new Entry(path, rows, counting.count, HexFormat.of().formatHex(sha.digest()));
        entries.add(entry);
        return entry;
    }

    static String csv(String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        // 以 = + - @ 制表符 回车 开头的值在 Excel 里可能被当公式执行 (CSV 注入): 前置单引号
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 && !isNumber(value)) {
            value = "'" + value;
            quote = true;
        }
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static boolean isNumber(String v) {
        return v.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }

    /**
     * ZIP 条目路径: 对象键里的 {@code ..}、{@code .}、反斜杠、空段都转义掉, 解包时不会跳出目标目录 (zip-slip)。
     * 对象键可能带用户上传的原文件名。
     */
    static String safeEntryPath(String path) {
        StringBuilder out = new StringBuilder();
        for (String seg : path.replace("\\", "%5C").split("/")) {
            if (seg.isEmpty()) continue;
            if (seg.equals("..")) seg = "%2E%2E";
            else if (seg.equals(".")) seg = "%2E";
            if (out.length() > 0) out.append('/');
            out.append(seg);
        }
        return out.toString();
    }

    private final java.util.Set<String> usedPaths = new java.util.HashSet<>();

    /** 两个对象键规范化后可能撞成同一个条目名 (a//b 与 a/b): 后来的加 ~2、~3, ⛔ 不让整个导出因重名失败。 */
    private String uniquePath(String path) {
        if (usedPaths.add(path)) return path;
        for (int i = 2; ; i++) {
            String p = path + "~" + i;
            if (usedPaths.add(p)) return p;
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { flush(); }
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
