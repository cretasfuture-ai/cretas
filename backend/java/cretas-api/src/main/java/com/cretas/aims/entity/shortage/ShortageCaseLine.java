package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 缺料实例里一种物料的缺口。单位跟物料 (BOM 展开时的单位) 走, ⛔ 不默认 kg。
 *
 * <p>gap = max(0, required − released); 质检中和在途只是「快来了」, 不减缺口 —— 放行入库才算。
 * 在途只计单位与需求一致的采购行, 单位不同的行数记在 {@link #onOrderUnitMismatchLines}, ⛔ 不换算。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shortage_case_lines")
@SQLDelete(sql = "UPDATE shortage_case_lines SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ShortageCaseLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "material_type_id", length = 191, nullable = false)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "required_qty", precision = 20, scale = 6, nullable = false)
    private BigDecimal requiredQty;

    @Column(name = "released_qty", precision = 20, scale = 6, nullable = false)
    private BigDecimal releasedQty;

    @Column(name = "inspecting_qty", precision = 20, scale = 6, nullable = false)
    private BigDecimal inspectingQty;

    @Column(name = "on_order_qty", precision = 20, scale = 6, nullable = false)
    private BigDecimal onOrderQty;

    @Column(name = "on_order_unit_mismatch_lines", nullable = false)
    private int onOrderUnitMismatchLines;

    @Column(name = "gap_qty", precision = 20, scale = 6, nullable = false)
    private BigDecimal gapQty;
}
