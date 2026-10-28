package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 经验日产能取数。口径 (见 {@link ExperienceCapacity}):
 * 表 {@code finished_goods_batches}, 字段 {@code produced_quantity} / {@code unit} / {@code production_date},
 * 只取挂了生产计划的入库 (生产产出), 排除退货入库与已撤回。
 *
 * <p>为什么是成品入库而不是报工: 报工 ({@code production_reports} YIELD / {@code process_sheet_rows}) 是逐道工序的,
 * 中间道产出的是半成品, 「哪一行是这个成品的最终产出」要靠工艺图判, 多产出时更说不清;
 * 成品入库每行就是「哪天、哪个成品、入了多少、什么单位」, 结单 / 小结 / 批次完工三条成品入库路径都写这张表。
 *
 * <p>窗口 = [asOf − 90 天, asOf − 1 天]: 不含当天 (当天还没做完, 会把中位数往下拉)。
 */
@Service
@RequiredArgsConstructor
public class ExperienceCapacityService {

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Transactional(readOnly = true)
    public ExperienceCapacity experience(String factoryId, String productTypeId, String planUnit, LocalDate asOf) {
        if (planUnit == null || planUnit.isBlank()) {
            return ExperienceCapacity.unavailable("计划没有单位，算不出历史日产能");
        }
        LocalDate to = asOf.minusDays(1);
        LocalDate from = asOf.minusDays(ExperienceCapacity.WINDOW_DAYS);
        List<ExperienceCapacity.OutputRow> rows = new ArrayList<>();
        for (Object[] r : finishedGoodsBatchRepository.sumProductionOutputByDayAndUnit(
                factoryId, productTypeId, from, to)) {
            rows.add(new ExperienceCapacity.OutputRow((LocalDate) r[0], (String) r[1], toDecimal(r[2])));
        }
        return ExperienceCapacity.fromRows(rows, planUnit.trim());
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        return new BigDecimal(value.toString());
    }
}
