package com.cretas.aims.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自动排程「需要补日产能」的一个产品: 人工没填 (或填的单位与计划单位不一致) 且算不出经验日产能。
 * 前端据此当场弹出填写, 填完带 {@code dailyCapacityOverrides} 重新提交。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyCapacityRequiredDTO {
    /** 日产能存在这个产品上 (联产计划是原料归属锚点)。 */
    private String productTypeId;
    /** 给人看的名字 (联产计划的锚点显示原料名)。 */
    private String productName;
    /** 要填的单位 = 计划单位。 */
    private String unit;
    /** 联产计划 (一张图出多个成品): 问的是「这个计划一天能做多少」, 不是某一个成品。 */
    private boolean jointPlan;
    /** 页面上该问的那句话。 */
    private String question;
    /** MISSING = 没填; UNIT_MISMATCH = 填了但单位与计划单位不一致又换算不了。 */
    private String reason;
    /** UNIT_MISMATCH 时产品上现有的值 / 单位, 方便对照。 */
    private BigDecimal currentQuantityPerDay;
    private String currentUnit;
    /** 经验日产能 (可选; 算得出就不会进这个清单, 所以通常为空), 与算不出的原因。 */
    private BigDecimal experienceQuantityPerDay;
    private String experienceNote;
    /** 用到这个产品的计划编号。 */
    private List<String> planNumbers;
}
