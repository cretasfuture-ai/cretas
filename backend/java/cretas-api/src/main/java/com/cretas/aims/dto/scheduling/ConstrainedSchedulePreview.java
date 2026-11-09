package com.cretas.aims.dto.scheduling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 自动排程计算结果 (不落库)。{@link #plans()} 与入参 {@code planIds} <b>同序</b>。
 *
 * @param outcome          {@code SCHEDULED} 或 {@code CAPACITY_REQUIRED} (与 {@link SchedulingPlanDTO} 同义; 后者时 plans 为空)
 * @param productionLineId 实际排在哪条线
 * @param warnings         整体提示 (按历史估算、已在别的排程里排过 等)
 */
public record ConstrainedSchedulePreview(String outcome, String productionLineId, LocalDate firstDay,
                                         List<DailyCapacityRequiredDTO> capacityRequired, List<PlanResult> plans,
                                         List<String> warnings) {

    /**
     * @param status           SCHEDULED | NOTHING_REMAINING | EXCEEDS_HORIZON | NEEDS_CAPACITY | EQUIPMENT_UNAVAILABLE
     *                         | PREDECESSOR_NOT_SCHEDULED
     * @param days             哪天做多少 (SCHEDULED 才有)
     * @param delays           为什么推迟 / 中途跳过
     * @param notes            缺数据降级的提示 (没核对的约束)
     * @param dailyAllocations 可直接写进 {@code LineSchedule.daily_allocations} 的 JSON (SCHEDULED 才有)
     */
    public record PlanResult(String planId, String planNumber, String status, List<Day> days, List<Delay> delays,
                             List<String> notes, Map<String, Object> dailyAllocations) {
    }

    public record Day(LocalDate date, BigDecimal quantity, BigDecimal shareOfDay) {
    }

    /** code: MATERIAL_ARRIVAL | PREDECESSOR | EQUIPMENT_BUSY | MAINTENANCE | LINE_FULL; from..to 含两端。 */
    public record Delay(String code, LocalDate from, LocalDate to, String text) {
    }
}
