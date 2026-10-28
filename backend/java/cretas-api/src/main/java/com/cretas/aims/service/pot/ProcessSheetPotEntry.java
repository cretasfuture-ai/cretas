package com.cretas.aims.service.pot;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * S3-16: 逐工序报工时随行提交的一锅 (只在按锅生产的工序上填; 不填 = 不记锅次, 行为不变)。
 *
 * <p>投入只能填这一行实际投入的批次; 不知道每锅用了哪批料就不填, 系统如实记「未分摊」,
 * 召回时按整批算 (不猜)。产出量会分到这一行唯一的产出批次上。
 */
@Data
public class ProcessSheetPotEntry {
    private Integer potNumber;
    private String equipmentLabel;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** MANUAL / DEVICE / CAMERA_CANDIDATE / UNKNOWN; 空 = MANUAL。 */
    private String timeSource;
    /** CONFIRMED / PENDING_CONFIRMATION; 空 = CONFIRMED。 */
    private String bindingStatus;
    private String operatorName;
    private BigDecimal outputQuantity;
    private String outputUnit;
    /** 关键工艺参数, 如 {"temperatureC": 98, "durationMin": 45}。 */
    private Map<String, Object> processParams;
    private List<ProductionPotService.PotLink> inputs;
    private String remark;
}
