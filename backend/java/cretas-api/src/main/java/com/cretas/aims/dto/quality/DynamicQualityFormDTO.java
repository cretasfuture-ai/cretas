package com.cretas.aims.dto.quality;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class DynamicQualityFormDTO {
    private String batchId;
    private String productTypeId;
    private String productName;
    private String standardRevision;
    private List<Item> items;

    @Data
    public static class Item {
        private String bindingId;
        private String itemId;
        private Integer itemVersion;
        private String label;
        private String valueType;
        private String unit;
        private String effectiveStandardValue;
        private BigDecimal effectiveMinValue;
        private BigDecimal effectiveMaxValue;
        private Boolean effectiveIsRequired;
    }
}
