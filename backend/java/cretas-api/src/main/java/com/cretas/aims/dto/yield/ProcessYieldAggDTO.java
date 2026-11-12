package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 工序级出成率聚合 (厂级跨批, GET /production/yield/by-process 输出) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessYieldAggDTO {

    /** 转化率 / 损耗率算不出来的原因 (unitComparable=false 时必有一个)。 */
    public enum IncomparableReason {
        /** 投入、产出各自单位明确但不同, 也没有可靠的换算 (例: kg → 盒)。 */
        UNIT_MISMATCH,
        /** 同一道工序的报工里, 投入或产出记了不止一种单位 (或部分报工没记单位), 合计本身就不成立。 */
        MIXED_UNITS,
        /** 投入或产出没有登记单位, 无法确认两侧是同一单位。 */
        NO_UNIT,
        /** 投入合计为 0。 */
        NO_INPUT
    }

    private String processName;
    private BigDecimal inputQuantity;   // Σ input_quantity, scale 2; 投入单位混杂时 null (不出假合计)
    private BigDecimal outputQuantity;  // Σ output_quantity, scale 2; 产出单位混杂时 null
    private BigDecimal conversionRate;  // 出成率% 0-100, scale 1; 不可比时 null
    private BigDecimal wastageRate;     // 损耗率% 0-100, scale 1; 不可比时 null
    /** 兼容旧前端: 同 inputUnit。 */
    private String unit;
    /** 投入单位 (报工所记, 没记退回工序标准); 没有单位或混杂时 null。 */
    private String inputUnit;
    /** 产出单位; 没有单位或混杂时 null。 */
    private String outputUnit;
    /** 转化率是否按同一单位 (或可靠换算后) 算出。 */
    private Boolean unitComparable;
    /** unitComparable=false 时的原因; 可比时 null。 */
    private IncomparableReason incomparableReason;
    private Integer batchCount;
}
