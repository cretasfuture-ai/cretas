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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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

    public FactoryDataPackageWriter(OutputStream out) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
    }

    public List<Entry> entries() { return List.copyOf(entries); }

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

    /** 写成 {@code <dir>/<table>.json}: 对象数组, 值一律是文本或 null (与 CSV 同一套取值); 凭证类列已移除。 */
    public Entry writeTableJson(Connection conn, String dir, String schema, TableScope ts, String factoryId)
            throws SQLException, IOException {
        String path = dir + "/" + ts.table() + ".json";
        return queryOwned(conn, schema, ts, factoryId, rs -> writeEntry(path, out -> {
            ResultSetMetaData md = rs.getMetaData();
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
                    String v = rs.getString(i);
                    if (v == null) g.writeNullField(name);
                    else g.writeStringField(name, FactoryDataCatalog.isCredentialColumn(name) ? FactoryDataCatalog.REDACTED : v);
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

    /** 把本厂在这张表里的行写成 {@code <dir>/<table>.csv}; 凭证类列的值替换成 {@link FactoryDataCatalog#REDACTED}。 */
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
                        redact[i] = FactoryDataCatalog.isCredentialColumn(md.getColumnName(i));
                        if (i > 1) w.write(',');
                        w.write(csv(md.getColumnName(i)));
                    }
                    w.write("\r\n");
                    long rows = 0;
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) w.write(',');
                            String v = rs.getString(i);
                            if (v != null) w.write(csv(redact[i] ? FactoryDataCatalog.REDACTED : v));
                        }
                        w.write("\r\n");
                        rows++;
                    }
                    w.flush();
                    return rows;
                }, true);
        });
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
