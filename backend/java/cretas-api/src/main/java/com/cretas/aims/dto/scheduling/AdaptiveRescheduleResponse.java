package com.cretas.aims.dto.scheduling;

import lombok.Data;

/**
 * 重排响应 DTO
 *
 * <p>自适应重排目前只登记调整标记、不改时间和资源, 所以「重排后准时率 / 提升」没有读数 = null,
 * ⛔ 不填一个推算出来的数。没有可算概率的任务时 beforeOnTimeRate 也是 null。
 */
@Data
public class AdaptiveRescheduleResponse {
    private String scheduleBatchNo;
    private int rescheduledTasks;
    private Double beforeOnTimeRate;
    private Double afterOnTimeRate;
    private Double improvementPercent;
    /** 这次实际做了什么 (照实说明没改时间和资源)。 */
    private String note;
}
