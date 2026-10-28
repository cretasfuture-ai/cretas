package com.cretas.aims.service.factorydata;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个库一个 Excel 工作簿, 每张表一个 sheet, 第一个 sheet「目录」列出 sheet 与表名的对应。
 *
 * <p>Excel 的硬限制 (口径 Q5: 超出时自动拆分, 并在 manifest 注明):
 * <ul>
 *   <li>每个 sheet 最多 1,048,576 行 (含表头) ⇒ 超出的行拆到 {@code <名字>~2}、{@code ~3}……</li>
 *   <li>sheet 名最多 31 个字符、不能重名 ⇒ 截断并加 {@code ~n} 去重; 「目录」里写全名</li>
 *   <li>单元格最多 32,767 个字符 ⇒ 截断并计数; 完整值见同一个包里的 CSV / JSON (若选了)</li>
 * </ul>
 * 用 SXSSF 流式写 (内存里只留 100 行), 临时文件压缩并在 close 时删除。
 */
final class FactoryDataXlsxBook implements AutoCloseable {

    static final int MAX_DATA_ROWS = 1_048_575;
    static final int MAX_CELL_CHARS = 32_767;
    static final int MAX_SHEET_NAME = 31;
    static final String INDEX_SHEET = "目录";
    private static final String TRUNCATED_MARK = "…[已截断]";

    private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    private final Sheet index;
    private final Set<String> usedNames = new HashSet<>();
    private final List<Map<String, Object>> sheets = new ArrayList<>();
    private int maxDataRows = MAX_DATA_ROWS;
    private long truncatedCells;

    FactoryDataXlsxBook() {
        workbook.setCompressTempFiles(true);
        index = workbook.createSheet(INDEX_SHEET);
        usedNames.add(INDEX_SHEET.toLowerCase(Locale.ROOT));
        Row header = index.createRow(0);
        String[] cols = {"工作表", "表名", "起始行号", "行数", "说明"};
        for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
    }

    /** 测试用: 把每个 sheet 的行数上限调小, 验证拆分。 */
    void setMaxDataRowsForTest(int rows) { this.maxDataRows = rows; }

    long truncatedCells() { return truncatedCells; }

    List<Map<String, Object>> sheets() { return List.copyOf(sheets); }

    /** 把结果集写成一个或多个 sheet, 返回写入的数据行数。 */
    long addTable(String table, ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] header = new String[n];
        boolean[] redact = new boolean[n];
        for (int i = 0; i < n; i++) {
            header[i] = md.getColumnName(i + 1);
            redact[i] = FactoryDataCatalog.isCredentialColumn(header[i]);
        }
        int part = 1;
        Sheet sheet = null;
        int rowInSheet = 0;
        long total = 0;
        long partStart = 0;
        while (rs.next()) {
            if (sheet == null || rowInSheet >= maxDataRows) {
                if (sheet != null) record(sheet.getSheetName(), table, part - 1, partStart, rowInSheet);
                sheet = workbook.createSheet(sheetName(table, part++));
                writeHeader(sheet, header);
                rowInSheet = 0;
                partStart = total;
            }
            Row row = sheet.createRow(rowInSheet + 1);
            for (int i = 0; i < n; i++) {
                String v = rs.getString(i + 1);
                if (v == null) continue;
                if (redact[i]) v = FactoryDataCatalog.REDACTED;
                if (v.length() > MAX_CELL_CHARS) {
                    v = v.substring(0, MAX_CELL_CHARS - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
                    truncatedCells++;
                }
                Cell cell = row.createCell(i);
                cell.setCellValue(v);
            }
            rowInSheet++;
            total++;
        }
        if (sheet == null) {               // 空表也给一个只有表头的 sheet
            sheet = workbook.createSheet(sheetName(table, part++));
            writeHeader(sheet, header);
        }
        record(sheet.getSheetName(), table, part - 1, partStart, rowInSheet);
        return total;
    }

    void write(OutputStream out) throws IOException {
        int r = 1;
        for (Map<String, Object> s : sheets) {
            Row row = index.createRow(r++);
            row.createCell(0).setCellValue((String) s.get("sheet"));
            row.createCell(1).setCellValue((String) s.get("table"));
            row.createCell(2).setCellValue(((Number) s.get("firstRow")).doubleValue());
            row.createCell(3).setCellValue(((Number) s.get("rows")).doubleValue());
            row.createCell(4).setCellValue((String) s.get("note"));
        }
        workbook.write(out);
    }

    @Override
    public void close() {
        workbook.dispose();
    }

    private void record(String sheet, String table, int part, long firstRow, int rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheet", sheet);
        m.put("table", table);
        m.put("part", part);
        m.put("firstRow", firstRow + 1);
        m.put("rows", rows);
        List<String> notes = new ArrayList<>();
        if (!sheet.equals(table)) notes.add("sheet 名超过 " + MAX_SHEET_NAME + " 字符或重名, 已缩短");
        if (part > 1 || rows >= maxDataRows) notes.add("超过单 sheet " + maxDataRows + " 行, 拆分第 " + part + " 部分");
        m.put("note", String.join("; ", notes));
        sheets.add(m);
    }

    private static void writeHeader(Sheet sheet, String[] header) {
        Row h = sheet.createRow(0);
        for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
    }

    private String sheetName(String table, int part) {
        String suffix = part > 1 ? "~" + part : "";
        String base = table.length() + suffix.length() > MAX_SHEET_NAME
                ? table.substring(0, MAX_SHEET_NAME - suffix.length()) : table;
        String name = base + suffix;
        int k = 2;
        while (!usedNames.add(name.toLowerCase(Locale.ROOT))) {
            String dedup = "~" + k++;
            name = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME - suffix.length() - dedup.length()))
                    + dedup + suffix;
        }
        return name;
    }
}
