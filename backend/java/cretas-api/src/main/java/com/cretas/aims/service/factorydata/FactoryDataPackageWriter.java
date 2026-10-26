package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;

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
    private static final int FETCH_SIZE = 1000;

    private final ZipOutputStream zip;
    private final List<Entry> entries = new ArrayList<>();

    public FactoryDataPackageWriter(OutputStream out) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    /** 把本厂在这张表里的行写成 {@code <dir>/<table>.csv}; 凭证类列的值替换成 {@link FactoryDataCatalog#REDACTED}。 */
    public Entry writeTable(Connection conn, String dir, String schema, TableScope ts, String factoryId)
            throws SQLException, IOException {
        FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, ts, factoryId);
        String order = ts.pkColumn() != null ? " ORDER BY " + FactoryDataScopeResolver.q(ts.pkColumn()) : "";
        String sql = "SELECT * FROM " + FactoryDataScopeResolver.q(schema) + "." + FactoryDataScopeResolver.q(ts.table())
                + " WHERE " + where.sql() + order;
        String path = dir + "/" + ts.table() + ".csv";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(FETCH_SIZE);
            FactoryDataScopeResolver.bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
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
            }
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
        // 以 = + - @ 开头的值在 Excel 里会被当公式执行 (CSV 注入): 前置单引号
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !isNumber(value)) {
            value = "'" + value;
            quote = true;
        }
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static boolean isNumber(String v) {
        return v.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
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
