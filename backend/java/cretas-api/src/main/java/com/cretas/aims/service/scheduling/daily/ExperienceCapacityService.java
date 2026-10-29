package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 经验日产能取数。口径 (见 {@link ExperienceCapacity}):
 *
 * <p><b>普通产品</b> ({@link #experience}): 表 {@code finished_goods_batches}, 字段 {@code produced_quantity} /
 * {@code unit} / {@code production_date}, 只取挂了生产计划的入库 (生产产出), 排除退货入库与已撤回。
 * 为什么是成品入库而不是报工: 报工 ({@code production_reports} YIELD / {@code process_sheet_rows}) 是逐道工序的,
 * 中间道产出的是半成品, 「哪一行是这个成品的最终产出」要靠工艺图判, 多产出时更说不清;
 * 成品入库每行就是「哪天、哪个成品、入了多少、什么单位」, 结单 / 小结 / 批次完工三条成品入库路径都写这张表。
 *
 * <p><b>联产计划</b> ({@link #rawInput}, Steve 2026-10-29, 只作建议): 表 {@code material_consumptions}
 * ({@code quantity} / {@code consumption_time}), 经 {@code batch_id → material_batches} 取原料
 * ({@code material_type_id}) 与单位 ({@code quantity_unit}), 见
 * {@link MaterialConsumptionRepository#findProductionInputOfMaterialType}。
 * 不用 {@code batch_lineage_edges} RAW_TO_PRODUCTION: 边表只增不减 (撤回不删边、差异调整的退回写不进边),
 * 拿它合计会把退回 / 撤回的量也算成投入。
 *
 * <p>窗口 = [asOf − 90 天, asOf − 1 天]: 不含当天 (当天还没做完, 会把中位数往下拉)。
 */
@Service
@RequiredArgsConstructor
public class ExperienceCapacityService {

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;

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

    /**
     * 联产计划的建议值: 近 90 天 (不含今天) 每天实际投入这种原料的量 (换成计划单位) 的中位数。
     *
     * @param materialTypeId 原料 ({@code raw_material_types.id}, 即联产计划主产品锚点的 id)
     * @param materialName   原料名, 说明里用
     */
    @Transactional(readOnly = true)
    public ExperienceCapacity rawInput(String factoryId, String materialTypeId, String materialName, String planUnit,
                                       LocalDate asOf) {
        if (planUnit == null || planUnit.isBlank()) {
            return ExperienceCapacity.unavailable("计划没有单位，给不出建议值", ExperienceCapacity.Basis.RAW_INPUT);
        }
        LocalDateTime from = asOf.minusDays(ExperienceCapacity.WINDOW_DAYS).atStartOfDay();
        LocalDateTime toExclusive = asOf.atStartOfDay();
        List<ExperienceCapacity.OutputRow> rows = new ArrayList<>();
        for (Object[] r : materialConsumptionRepository.findProductionInputOfMaterialType(
                factoryId, materialTypeId, from, toExclusive)) {
            LocalDateTime at = (LocalDateTime) r[0];
            rows.add(new ExperienceCapacity.OutputRow(at == null ? null : at.toLocalDate(), (String) r[1],
                    toDecimal(r[2])));
        }
        return ExperienceCapacity.fromRows(rows, planUnit.trim(), ExperienceCapacity.Basis.RAW_INPUT, materialName);
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
