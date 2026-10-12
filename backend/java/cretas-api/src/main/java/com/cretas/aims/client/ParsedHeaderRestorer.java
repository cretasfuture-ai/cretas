package com.cretas.aims.client;

import com.cretas.aims.dto.smartbi.DataFeatureResult;
import com.cretas.aims.dto.smartbi.ExcelParseResponse;
import com.cretas.aims.dto.smartbi.FieldMappingResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python /auto-parse 把行数据的列名改成了标准字段名 ("Q2(万元)" → "sales_amount_2"),
 * 而 fieldMappings 里仍是 originalColumn="Q2(万元)" → standardField="sales_amount_2"。
 *
 * Java 侧的约定是「行按原始表头存, 语义靠 standardField」(自动同步的上传就是这样存的,
 * getFieldValue 也是先按映射找原始列名)。两边不一致时, 持久化的 Bug #42 过滤会把
 * originalColumn 不在行里的映射全部丢掉, 再由 backfill 用英文键补回字段定义 ——
 * 结果原始表头丢失, 界面显示 "sales amount(2)" (2026-10-12 F006 实测)。
 *
 * 这里在解析结果进入 Java 流程之前, 按映射把被改名的列名还原成原始表头。
 * 只还原「行里有标准名、却没有原始表头」的列; 目标名已被占用时跳过, 不覆盖数据。
 */
final class ParsedHeaderRestorer {

    private ParsedHeaderRestorer() {
    }

    static ExcelParseResponse restore(ExcelParseResponse response) {
        if (response == null || response.getFieldMappings() == null) {
            return response;
        }
        Set<String> present = new HashSet<>();
        if (response.getHeaders() != null) {
            present.addAll(response.getHeaders());
        }
        List<Map<String, Object>> rows = response.getPreviewData();
        if (rows != null && !rows.isEmpty() && rows.get(0) != null) {
            present.addAll(rows.get(0).keySet());
        }

        Map<String, String> renames = new HashMap<>();   // standard key -> original header
        Set<String> targets = new HashSet<>();
        for (FieldMappingResult m : response.getFieldMappings()) {
            String original = m.getOriginalColumn();
            String standard = m.getStandardField();
            if (original == null || standard == null || original.equals(standard)) {
                continue;
            }
            if (!present.contains(standard) || present.contains(original)) {
                continue;   // not renamed by Python, or original already a column
            }
            if (renames.containsKey(standard) || !targets.add(original)) {
                continue;   // ambiguous: never let two columns land on one name
            }
            renames.put(standard, original);
        }
        if (renames.isEmpty()) {
            return response;
        }

        if (response.getHeaders() != null) {
            List<String> headers = new ArrayList<>(response.getHeaders().size());
            for (String h : response.getHeaders()) {
                headers.add(renames.getOrDefault(h, h));
            }
            response.setHeaders(headers);
        }
        if (rows != null) {
            List<Map<String, Object>> restored = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    restored.add(null);
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    copy.put(renames.getOrDefault(e.getKey(), e.getKey()), e.getValue());
                }
                restored.add(copy);
            }
            response.setPreviewData(restored);
        }
        if (response.getDataFeatures() != null) {
            for (DataFeatureResult f : response.getDataFeatures()) {
                if (f != null && f.getColumnName() != null && renames.containsKey(f.getColumnName())) {
                    f.setColumnName(renames.get(f.getColumnName()));
                }
            }
        }
        return response;
    }
}
