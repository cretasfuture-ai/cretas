package com.cretas.aims.service.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 保存的视图定义 (只有查询意图, 没有任何结果行)。未知属性一律拒绝。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ViewDefinition {
    private String entity;
    private List<String> columns = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    private Sort sort;
    private Integer limit;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Filter {
        private String field;
        private String op;
        private String value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Sort {
        private String field;
        private String direction = "asc";
    }
}
