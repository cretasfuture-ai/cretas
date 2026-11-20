package com.cretas.aims.dto.finance.report;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ShippedRevenueBasis {
    GROSS("折扣前，未扣退货",
            "发货收入（折扣前，未扣退货）：期间内已确认发货的实发数量 × 发货不含税单价，不含未发货的订单金额。"),
    NET("扣折扣与退货后净额",
            "发货收入（扣折扣与退货后净额）：已确认发货的不含税金额扣除订单折扣分摊及本期已完成销售退货的不含税金额。");

    public static final ShippedRevenueBasis DEFAULT = GROSS;

    private final String label;
    private final String description;

    ShippedRevenueBasis(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    @JsonCreator
    public static ShippedRevenueBasis fromValue(String value) {
        return valueOf(value);
    }
}
