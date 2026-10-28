package com.cretas.aims.entity.pot;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * S3-16 锅次: 一个生产批次里的一次下锅 → 出锅 (表 production_pots, V20261103_119)。
 *
 * <p>设备 / 锅次时间与人工工时是两件独立事实: 本实体只有下锅/出锅时间, 没有工时;
 * 人工工时仍在报工 laborSegments。摄像候选时间 ({@link #TIME_CAMERA_CANDIDATE}) 永不自动算精确
 * ({@link #timeConfirmed} 恒 false, 数据库 CHECK 钉住), 关联不明的锅 {@link #BINDING_PENDING}
 * 照样进召回视图但标「待确认」。锅不物理删除, 作废 = {@link #STATUS_VOIDED} + 原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "production_pots")
@SQLDelete(sql = "UPDATE production_pots SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ProductionPot extends BaseEntity {

    public static final String TIME_MANUAL = "MANUAL";
    public static final String TIME_DEVICE = "DEVICE";
    public static final String TIME_CAMERA_CANDIDATE = "CAMERA_CANDIDATE";
    public static final String TIME_UNKNOWN = "UNKNOWN";

    public static final String BINDING_CONFIRMED = "CONFIRMED";
    public static final String BINDING_PENDING = "PENDING_CONFIRMATION";

    public static final String STATUS_RECORDED = "RECORDED";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String SOURCE_POT_API = "POT_API";
    public static final String SOURCE_PROCESS_SHEET = "PROCESS_SHEET";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    @Column(name = "process_sheet_row_id")
    private Long processSheetRowId;

    @Column(name = "process_code", length = 64)
    private String processCode;

    @Column(name = "process_name", length = 100)
    private String processName;

    @Column(name = "pot_number", nullable = false)
    private Integer potNumber;

    /** 锅/设备标识 (如「2 号夹层锅」), 自由文本。 */
    @Column(name = "equipment_label", length = 100)
    private String equipmentLabel;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Builder.Default
    @Column(name = "time_source", nullable = false, length = 32)
    private String timeSource = TIME_MANUAL;

    @Builder.Default
    @Column(name = "time_confirmed", nullable = false)
    private Boolean timeConfirmed = Boolean.FALSE;

    @Builder.Default
    @Column(name = "binding_status", nullable = false, length = 32)
    private String bindingStatus = BINDING_CONFIRMED;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 100)
    private String operatorName;

    @Column(name = "output_quantity", precision = 18, scale = 6)
    private BigDecimal outputQuantity;

    @Column(name = "output_unit", length = 20)
    private String outputUnit;

    /** 关键工艺参数, 如 {"temperatureC": 98, "durationMin": 45}; 工序有就录, 没有就空。 */
    @Builder.Default
    @Type(JsonBinaryType.class)
    @Column(name = "process_params", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> processParams = new HashMap<>();

    @Builder.Default
    @Column(name = "source", nullable = false, length = 32)
    private String source = SOURCE_POT_API;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_RECORDED;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;

    public boolean isVoided() {
        return STATUS_VOIDED.equals(status);
    }
}
