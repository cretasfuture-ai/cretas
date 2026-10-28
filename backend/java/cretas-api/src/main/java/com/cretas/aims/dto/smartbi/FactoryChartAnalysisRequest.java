package com.cretas.aims.dto.smartbi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

/** Fixed, tenant-bound request for the factory SmartBI conversation endpoint. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FactoryChartAnalysisRequest {

    @NotBlank
    @Size(max = 1000)
    private String question;

    private String source;
    @Positive
    private Integer uploadId;
    @Positive
    private Integer inspectionUploadId;
    @Positive
    private Integer costUploadId;
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

    @AssertTrue(message = "inspectionUploadId 与 costUploadId 必须同时提供且与 uploadId 不同")
    @JsonIgnore
    public boolean isUploadedJointSelectionValid() {
        if (inspectionUploadId == null && costUploadId == null) return true;
        return "upload".equalsIgnoreCase(source)
                && uploadId != null && inspectionUploadId != null && costUploadId != null
                && !uploadId.equals(inspectionUploadId)
                && !uploadId.equals(costUploadId)
                && !inspectionUploadId.equals(costUploadId);
    }
}
