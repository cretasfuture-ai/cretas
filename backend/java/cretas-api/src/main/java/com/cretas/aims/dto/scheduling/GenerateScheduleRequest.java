package com.cretas.aims.dto.scheduling;

import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 自动排程请求 (APS v2: 按产品日产能按天排)。
 *
 * <p>{@code planIds} 与 {@code batchIds} 至少给一个: 批次会映射到它所属的生产计划。
 * 多个计划按传入顺序先到先排 (同一条产线一天的产能被它们按顺序分着用)。
 */
@Data
public class GenerateScheduleRequest {
    /** 第一天: 从这天开始按天排, 一天做不完顺延到下一天。 */
    @NotNull(message = "计划日期不能为空")
    private LocalDate planDate;

    /** 要排的生产计划 id (按顺序先到先排)。 */
    private List<String> planIds;

    /** 兼容旧调用: 指定批次, 映射到所属生产计划。 */
    private List<Long> batchIds;

    /** 可选: 产线。不给时工厂恰好有 1 条可用产线就用它, 多条要用户选。 */
    @Size(max = 191, message = "产线ID长度不能超过191个字符")
    private String productionLineId;

    /**
     * 排程页当场补的日产能: 先保存到产品 (下次不用再填), 再参与本次排程。
     * 只接受本次所选计划用到的产品, 单位必须与计划单位一致。
     */
    @Valid
    @Size(max = 200, message = "一次最多补 200 个产品的日产能")
    private List<DailyCapacityOverride> dailyCapacityOverrides;

    /**
     * 旧客户端字段: v1 的开工时刻。v2 按天排, 不再用它 (保留以免旧请求反序列化失败, 格式仍校验)。
     */
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "开工时刻格式应为 HH:mm, 例如 08:00")
    private String startTime;

    // 以下为旧客户端字段, 排程不使用 (不自动派工、不按概率挑计划), 保留以免旧请求反序列化失败。
    private String priorityStrategy = "balanced";

    private Boolean includeTemporaryWorkers = true;

    private Double targetProbability = 0.8;
}
