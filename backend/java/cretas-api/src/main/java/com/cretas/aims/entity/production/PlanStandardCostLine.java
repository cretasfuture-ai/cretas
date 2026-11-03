package com.cretas.aims.entity.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计划标准成本快照的一行 (食品 DAG 缺口 09, Steve 2026-11-03 裁定)。
 *
 * <p>标准成本 = 计划锁定的配方/工艺版本算出的标准用量 × <b>开计划那一刻</b>的物料单价。这张表在开计划时
 * 写一次, 之后<b>不随价格变</b> —— 价格涨了, 差异才会落在「价格差」上。⛔ 不维护标准价表、不用近期采购均价;
 * 开计划时物料档案没有单价 ({@code raw_material_types.unit_price} 为空) 就把 {@link #unitPrice} 记成 null,
 * 报表上那一项显示「无法比较」, 不猜。</p>
 *
 * <p>不可变记录: 没有 updated_at / deleted_at, 不继承 BaseEntity (不进软删除巡检)。</p>
 */
@Entity
@Table(name = "plan_standard_cost_lines", indexes = {
        @Index(name = "idx_pscl_plan", columnList = "factory_id, plan_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStandardCostLine {

    /** 原料/辅料/包材/调料 —— 配方里的标准投入。 */
    public static final String TYPE_MATERIAL = "MATERIAL";
    /** 配方登记的替代料 (只为它记开计划时单价, 标准用量归主料)。 */
    public static final String TYPE_SUBSTITUTE = "SUBSTITUTE";
    /** 标准人工 (研发预估 元/kg 成品)。 */
    public static final String TYPE_LABOR = "LABOR";

    /** 包材: 每 1 计划单位成品用多少 (BOM 标准用量, 已按出成率折算)。 */
    public static final String BASIS_PER_OUTPUT_UNIT = "PER_OUTPUT_UNIT";
    /** 调料: 每 1 kg 主料标准投入用多少 kg (BOM 调料 g/kg ÷ 1000)。 */
    public static final String BASIS_PER_RAW_INPUT_KG = "PER_RAW_INPUT_KG";
    /** 主料: 标准投入 kg = 成品 kg ÷ 出成率, 再按工艺投入端口份额分给各主料。 */
    public static final String BASIS_RAW_INPUT_SHARE = "RAW_INPUT_SHARE";
    /** 人工: 每 1 kg 成品多少元。 */
    public static final String BASIS_PER_OUTPUT_KG = "PER_OUTPUT_KG";
    /** 配方没有登记标准用量 (如辅料只登记配方资格): 用量差无法比较, 价格差照算。 */
    public static final String BASIS_NONE = "NONE";

    /** 开计划时物料档案的未税单价 (raw_material_types.unit_price)。 */
    public static final String PRICE_SOURCE_MATERIAL_UNIT_PRICE = "MATERIAL_UNIT_PRICE";
    /** 研发报价任务的预估人工 元/kg。 */
    public static final String PRICE_SOURCE_QUOTATION_LABOR = "QUOTATION_LABOR_PER_KG";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "plan_id", nullable = false, length = 191)
    private String planId;

    @Column(name = "line_type", nullable = false, length = 24)
    private String lineType;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    /** RAW / AUXILIARY / PACKAGING / SEASONING; 人工行为 null。 */
    @Column(name = "material_category", length = 32)
    private String materialCategory;

    /** 替代料行: 它替的那个主料。 */
    @Column(name = "parent_material_type_id", length = 191)
    private String parentMaterialTypeId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "bom_recipe_id", length = 191)
    private String bomRecipeId;

    @Column(name = "bom_version")
    private Integer bomVersion;

    @Column(name = "workflow_input_port_id", length = 128)
    private String workflowInputPortId;

    @Column(name = "standard_basis", nullable = false, length = 24)
    private String standardBasis;

    /** 与 {@link #standardBasis} 配套的系数 (包材每单位用量 / 调料 kg 每 kg / 主料份额 % / 人工 元每 kg)。 */
    @Column(name = "standard_rate", precision = 24, scale = 8)
    private BigDecimal standardRate;

    /** 标准用量的单位 (包材为 BOM 单位; 主料/调料为 kg)。 */
    @Column(name = "standard_unit", length = 20)
    private String standardUnit;

    /** 主料行: 锁定配方的整体出成率 (%), 成品 kg ÷ 它 = 标准投入 kg。 */
    @Column(name = "raw_yield_rate", precision = 9, scale = 4)
    private BigDecimal rawYieldRate;

    /** 开计划时的单价; null = 当时没有单价 (该项无法比较)。 */
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 单价的计量单位 (物料档案单位, 人工为 kg)。 */
    @Column(name = "price_unit", length = 20)
    private String priceUnit;

    @Column(name = "price_source", length = 40)
    private String priceSource;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;
}
