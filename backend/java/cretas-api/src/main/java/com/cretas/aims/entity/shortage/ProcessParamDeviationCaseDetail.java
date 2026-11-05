package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The latest immutable-report fact behind a process-parameter case.
 *
 * <p>The source key is deliberately independent of the human-readable case id:
 * a retry of a yield report or one of the three clerk fan-out rows must resolve
 * to the same fact.  The values here are the report-time snapshot, never the
 * current process standard.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "process_param_deviation_case_details")
@Where(clause = "deleted_at IS NULL")
public class ProcessParamDeviationCaseDetail extends BaseEntity {

    public enum SourceKind { YIELD_REPORT, PROCESS_SHEET_ROW }

    public enum Resolution {
        REASONABLE("偏离合理"), CORRECTED("已纠正"), STANDARD_UPDATE("标准需调整"), DATA_ERROR("数据有误");

        private final String label;

        Resolution(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "source_key", length = 255, nullable = false)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", length = 32, nullable = false)
    private SourceKind sourceKind;

    @Column(name = "source_report_id")
    private Long sourceReportId;

    @Column(name = "production_batch_id")
    private Long productionBatchId;

    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    @Column(name = "work_process_task_id")
    private Long workProcessTaskId;

    @Column(name = "process_entry_step_key", length = 255)
    private String processEntryStepKey;

    @Column(name = "process_name", length = 200)
    private String processName;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    @Column(name = "param_key", length = 191, nullable = false)
    private String paramKey;

    @Column(name = "param_label", length = 200)
    private String paramLabel;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "measured_value", precision = 20, scale = 6)
    private BigDecimal measuredValue;

    @Column(name = "standard_value", precision = 20, scale = 6)
    private BigDecimal standardValue;

    @Column(name = "range_min", precision = 20, scale = 6)
    private BigDecimal rangeMin;

    @Column(name = "range_max", precision = 20, scale = 6)
    private BigDecimal rangeMax;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "fact_fingerprint", length = 64, nullable = false)
    private String factFingerprint;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", length = 32)
    private Resolution resolutionCode;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
