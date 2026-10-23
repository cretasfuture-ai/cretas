package com.cretas.aims.dto.quality;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class QualityInspectionSnapshotDTO {
    private String standardBasis;
    private String standardRevision;
    private List<Map<String, Object>> items;
}
