package com.cretas.aims.dto.smartbi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Fixed, tenant-bound request for the factory SmartBI conversation endpoint. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FactoryChartAnalysisRequest {

    @NotBlank
    @Size(max = 1000)
    private String question;

    private String source;
    private Integer uploadId;
    private Integer year;
    private String periodType;
    private Integer startMonth;
    private Integer endMonth;
    private String analysisSubject;
    private String contextId;

    /** Do not silently discard actor, endpoint, SQL, or table injection fields at the HTTP edge. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unsupported factory analysis field: " + name);
    }
}
