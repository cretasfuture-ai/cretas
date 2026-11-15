package com.cretas.aims.service.smartbi;

import com.cretas.aims.exception.BusinessException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SmartBI 上传模板（只有表头的空 Excel）。对应原型 fusion-data-templates 的五类数据类型。
 * 不认识的类型明确报 400，不回退成任何默认模板。
 */
public final class SmartBIUploadTemplates {

    public record Definition(String sheetName, List<String> headers) {}

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    static {
        DEFINITIONS.put("sales", new Definition("销售数据", List.of("日期", "客户名称", "产品名称", "数量", "金额")));
        DEFINITIONS.put("finance", new Definition("财务数据", List.of("日期", "科目", "金额", "类型")));
        DEFINITIONS.put("inventory", new Definition("库存数据", List.of("日期", "产品名称", "数量", "操作类型")));
        DEFINITIONS.put("customer", new Definition("客户数据", List.of("客户名称", "联系人", "电话", "地址")));
        DEFINITIONS.put("product", new Definition("产品数据", List.of("产品名称", "类别", "规格", "单价")));
    }

    private SmartBIUploadTemplates() {}

    public static Set<String> supportedTypes() {
        return DEFINITIONS.keySet();
    }

    public static Definition definition(String type) {
        Definition d = type == null ? null : DEFINITIONS.get(type.trim().toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new BusinessException(400, "不支持的数据类型: " + type + "，可选: " + String.join("、", DEFINITIONS.keySet()))
                    .withHintTarget("type");
        }
        return d;
    }

    public static byte[] build(String type) {
        Definition d = definition(type);
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(d.sheetName());
            Row header = sheet.createRow(0);
            for (int i = 0; i < d.headers().size(); i++) {
                header.createCell(i).setCellValue(d.headers().get(i));
                sheet.setColumnWidth(i, 18 * 256);
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("模板生成失败", e);
        }
    }
}
