package com.cretas.aims.client;

import com.cretas.aims.dto.smartbi.DataFeatureResult;
import com.cretas.aims.dto.smartbi.ExcelParseResponse;
import com.cretas.aims.dto.smartbi.FieldMappingResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shapes are the real Python /auto-parse output for the F006 sample workbook
 * (2026-10-13 probe on prod, sheet "区域销售" and "产品毛利").
 */
class ParsedHeaderRestorerTest {

    @Test
    void renamedStandardKeysGoBackToTheOriginalHeaders() {
        ExcelParseResponse r = response(
                List.of("区域", "sales_amount", "sales_amount_2", "sales_amount_3", "sales_amount_4", "sales_amount_5", "yoy_rate"),
                List.of(new Object[]{"区域", "区域"}, new Object[]{"同比增长", "yoy_rate"},
                        new Object[]{"Q1(万元)", "sales_amount"}, new Object[]{"Q2(万元)", "sales_amount_2"},
                        new Object[]{"Q3(万元)", "sales_amount_3"}, new Object[]{"Q4(万元)", "sales_amount_4"},
                        new Object[]{"全年(万元)", "sales_amount_5"}),
                new Object[][]{{"上海", 141.9, 151.1, 154.2, 171.4, 618.6, 0.18}});

        ParsedHeaderRestorer.restore(r);

        assertThat(r.getHeaders()).containsExactly("区域", "Q1(万元)", "Q2(万元)", "Q3(万元)", "Q4(万元)", "全年(万元)", "同比增长");
        assertThat(r.getPreviewData().get(0)).containsExactly(
                Map.entry("区域", "上海"), Map.entry("Q1(万元)", 141.9), Map.entry("Q2(万元)", 151.1),
                Map.entry("Q3(万元)", 154.2), Map.entry("Q4(万元)", 171.4), Map.entry("全年(万元)", 618.6),
                Map.entry("同比增长", 0.18));
        assertThat(r.getDataFeatures()).extracting(DataFeatureResult::getColumnName)
                .containsExactly("区域", "Q1(万元)", "Q2(万元)", "Q3(万元)", "Q4(万元)", "全年(万元)", "同比增长");
        // semantics are kept on the mapping, not on the key
        assertThat(r.getFieldMappings()).extracting(FieldMappingResult::getStandardField)
                .contains("sales_amount_2", "yoy_rate");
        // every mapping now names a real column → persistence's Bug #42 filter keeps all of them
        assertThat(r.getFieldMappings()).extracting(FieldMappingResult::getOriginalColumn)
                .allSatisfy(c -> assertThat(r.getPreviewData().get(0)).containsKey(c));
    }

    @Test
    void unmappedAndNullStandardColumnsAreLeftAlone() {
        ExcelParseResponse r = response(
                List.of("product", "规格", "原料成本(万元)", "cost"),
                List.of(new Object[]{"产品", "product"}, new Object[]{"规格", null},
                        new Object[]{"原料成本(万元)", "原料成本(万元)"}, new Object[]{"人工及制造(万元)", "cost"}),
                new Object[][]{{"酱鸭翅", "500g", 63.6, 18}});

        ParsedHeaderRestorer.restore(r);

        assertThat(r.getHeaders()).containsExactly("产品", "规格", "原料成本(万元)", "人工及制造(万元)");
    }

    @Test
    void alreadyOriginalRowsAreUntouched() {
        // auto-sync uploads already key rows by the Chinese header
        ExcelParseResponse r = response(
                List.of("员工姓名", "产出数量"),
                List.of(new Object[]{"员工姓名", "worker_name"}, new Object[]{"产出数量", "output_quantity"}),
                new Object[][]{{"张六膳", 0.0}});
        List<Map<String, Object>> before = r.getPreviewData();

        ParsedHeaderRestorer.restore(r);

        assertThat(r.getPreviewData()).isSameAs(before);
        assertThat(r.getHeaders()).containsExactly("员工姓名", "产出数量");
    }

    @Test
    void neverRenamesOntoAnExistingColumn() {
        // "收入" is both an original column and the original of a renamed one → skip, keep data
        ExcelParseResponse r = response(
                List.of("收入", "revenue"),
                List.of(new Object[]{"收入", "收入"}, new Object[]{"收入", "revenue"}),
                new Object[][]{{1, 2}});

        ParsedHeaderRestorer.restore(r);

        assertThat(r.getHeaders()).containsExactly("收入", "revenue");
        assertThat(r.getPreviewData().get(0)).containsEntry("收入", 1).containsEntry("revenue", 2);
    }

    static ExcelParseResponse response(List<String> headers, List<Object[]> mappings, Object[][] rows) {
        ExcelParseResponse r = new ExcelParseResponse();
        r.setSuccess(true);
        r.setHeaders(new ArrayList<>(headers));
        List<FieldMappingResult> fms = new ArrayList<>();
        for (Object[] m : mappings) {
            FieldMappingResult fm = new FieldMappingResult();
            fm.setOriginalColumn((String) m[0]);
            fm.setStandardField((String) m[1]);
            fms.add(fm);
        }
        r.setFieldMappings(fms);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                map.put(headers.get(i), row[i]);
            }
            data.add(map);
        }
        r.setPreviewData(data);
        List<DataFeatureResult> features = new ArrayList<>();
        for (String h : headers) {
            DataFeatureResult f = new DataFeatureResult();
            f.setColumnName(h);
            features.add(f);
        }
        r.setDataFeatures(features);
        return r;
    }
}
