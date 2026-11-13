package com.cretas.aims.dto.scheduling;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 更新排程请求
 */
@Data
public class UpdateScheduleRequest {
    private Integer sequenceOrder;
    private LocalDateTime plannedStartTime;
    private LocalDateTime plannedEndTime;
    private Integer plannedQuantity;
    private String notes;
    private List<Long> workerIds;
    /**
     * 客户端读到的排程版本号 (LineScheduleDTO.version)。传了就校验: 与库里不一致 = 读之后别人改过 → 409,
     * 不覆盖别人的修改。不传 (老版本 App) 只靠事务内乐观锁。
     */
    private Long version;
}
