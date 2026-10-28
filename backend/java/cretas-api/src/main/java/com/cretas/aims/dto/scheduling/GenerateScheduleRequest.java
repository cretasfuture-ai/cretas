package com.cretas.aims.dto.scheduling;

import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 自动排程请求 (APS v1: 按工艺图 + 预计工时排工序时间)。
 *
 * <p>{@code planIds} 与 {@code batchIds} 至少给一个: 批次会映射到它所属的生产计划。
 * 多个计划按传入顺序先到先排 (同一工位同一时刻只做一个)。
 */
@Data
public class GenerateScheduleRequest {
    @NotNull(message = "计划日期不能为空")
    private LocalDate planDate;

    /** 要排的生产计划 id (按顺序先到先排)。 */
    private List<String> planIds;

    /** 兼容旧调用: 指定批次, 映射到所属生产计划。 */
    private List<Long> batchIds;

    /** 可选: 产线。不给时工厂恰好有 1 条可用产线就用它, 多条要用户选。 */
    @Size(max = 191, message = "产线ID长度不能超过191个字符")
    private String productionLineId;

    /** 可选: 开工时刻 HH:mm; 不给按计划日期 08:00 开工排, 并在提示里写明。 */
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "开工时刻格式应为 HH:mm, 例如 08:00")
    private String startTime;

    // 以下为旧客户端字段, v1 排程不使用 (不自动派工、不按概率挑计划), 保留以免旧请求反序列化失败。
    private String priorityStrategy = "balanced";

    private Boolean includeTemporaryWorkers = true;

    private Double targetProbability = 0.8;
}
