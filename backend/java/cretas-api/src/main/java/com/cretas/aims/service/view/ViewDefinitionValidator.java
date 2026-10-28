package com.cretas.aims.service.view;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** 服务端校验视图定义: 只认白名单实体 / 字段 / 操作符, 值按字段类型可解析。 */
@Component
public class ViewDefinitionValidator {

    public static final int MAX_COLUMNS = 30;
    public static final int MAX_FILTERS = 10;
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;

    private final ViewEntityCatalog catalog;

    public ViewDefinitionValidator(ViewEntityCatalog catalog) {
        this.catalog = catalog;
    }

    /** 校验并返回该定义对应的实体规格。 */
    public ViewEntityCatalog.EntitySpec validate(ViewDefinition def) {
        if (def == null) throw bad("视图定义不能为空");
        ViewEntityCatalog.EntitySpec spec = catalog.find(def.getEntity())
                .orElseThrow(() -> bad("不支持的实体: " + def.getEntity()));

        if (def.getColumns() == null || def.getColumns().isEmpty()) throw bad("至少选择一列");
        if (def.getColumns().size() > MAX_COLUMNS) throw bad("列数超过上限 " + MAX_COLUMNS);
        Set<String> seen = new HashSet<>();
        for (String col : def.getColumns()) {
            if (col == null || !spec.fields().containsKey(col)) throw bad("不支持的列: " + col);
            if (!seen.add(col)) throw bad("重复的列: " + col);
        }

        if (def.getFilters() != null) {
            if (def.getFilters().size() > MAX_FILTERS) throw bad("过滤条件超过上限 " + MAX_FILTERS);
            for (ViewDefinition.Filter fl : def.getFilters()) {
                if (fl == null || fl.getField() == null || !spec.fields().containsKey(fl.getField())) {
                    throw bad("不支持的过滤字段: " + (fl == null ? null : fl.getField()));
                }
                ViewEntityCatalog.FieldSpec fs = spec.fields().get(fl.getField());
                if (fl.getOp() == null || !fs.type().allows(fl.getOp())) {
                    throw bad("字段 " + fs.name() + " 不支持操作符: " + fl.getOp());
                }
                if (fl.getValue() == null || fl.getValue().isBlank()) throw bad("过滤值不能为空: " + fs.name());
                checkValue(fs, fl.getValue());
            }
        }

        ViewDefinition.Sort sort = def.getSort();
        if (sort != null) {
            if (sort.getField() == null || !spec.fields().containsKey(sort.getField())) {
                throw bad("不支持的排序字段: " + sort.getField());
            }
            if (!"asc".equals(sort.getDirection()) && !"desc".equals(sort.getDirection())) {
                throw bad("排序方向只能是 asc 或 desc");
            }
        }

        Integer limit = def.getLimit();
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) throw bad("limit 必须在 1~" + MAX_LIMIT);
        return spec;
    }

    private static void checkValue(ViewEntityCatalog.FieldSpec fs, String value) {
        try {
            switch (fs.type()) {
                case NUMBER -> new BigDecimal(value.trim());
                case BOOLEAN -> {
                    if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException();
                }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            throw bad("字段 " + fs.name() + " 的过滤值格式不对: " + value);
        }
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
