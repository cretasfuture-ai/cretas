package com.cretas.aims.dto.quality;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class DynamicQualityInspectionRequest {
    private String standardRevision;
    private BigDecimal sampleSize;
    private List<Answer> answers;
    private String notes;

    @Data
    public static class Answer {
        private String bindingId;
        private String itemId;
        private Integer itemVersion;
        private Object value;
        private String note;
        private List<String> photoAttachmentIds;
    }
}
