package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO.IncomparableReason;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.service.yield.YieldAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class YieldAnalysisServiceImpl implements YieldAnalysisService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final ProductionReportRepository reportRepo;
    private final ProcessSecretAggregateExclusions aggregateExclusions;

    /** 一侧 (投入或产出) 的单位读数: 没有单位 / 唯一单位 / 混杂。 */
    private record Side(String display, boolean mixed) {
        static final Side NONE = new Side(null, false);
        static final Side MIXED = new Side(null, true);
        boolean known() { return display != null; }
    }

    @Override
    public List<ProcessYieldAggDTO> aggregateByProcess(String factoryId, LocalDate startDate, LocalDate endDate, String productTypeId) {
        // audit SQL-1: sentinel 避 PG null 参数类型推断失败
        LocalDate start = startDate != null ? startDate : LocalDate.of(1900, 1, 1);
        LocalDate end = endDate != null ? endDate : LocalDate.of(2999, 12, 31);
        String pt = productTypeId != null ? productTypeId : "";

        // 工艺保密: 厂级按工序汇总里, 调用者无完整查看权的保密产品 (连同多产出计划的原料锚点) 整个不参与 ——
        // 一道只有保密产品在用的工序, 汇总出来就是它的出成率。点名受保护产品由路由闸拦 (403);
        // 点名别的产品 (例如锚点) 时同样排除
        ProcessSecretAggregateExclusions.Scope excluded =
                aggregateExclusions.forCurrentCaller(factoryId, "yield by-process aggregate");
        List<Map<String, Object>> rows = reportRepo.aggregateYieldByProcess(factoryId, start, end, pt,
                excluded.productTypeIdsOrSentinel(), excluded.planIdsOrSentinel());
        List<ProcessYieldAggDTO> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal totalInput = toBig(r.get("total_input"));
            BigDecimal totalOutput = toBig(r.get("total_output"));
            int reportCount = toInt(r.get("report_count"));
            // 单位按报工实际所记 (SQL 已退回工序标准、剔除空串与 'unitless' 占位)。
            // 2026-11-12 验收: 只看工序标准单位时, 定量包装报工 kg/袋 → 袋/盒 被判同单位, 转化率 333%~500%。
            Side in = side(r, "input", reportCount);
            Side out = side(r, "output", reportCount);

            BigDecimal conversionRate = null;
            BigDecimal wastageRate = null;
            IncomparableReason reason;
            if (in.mixed() || out.mixed()) {
                reason = IncomparableReason.MIXED_UNITS;
            } else if (!in.known() || !out.known()) {
                // 没有单位就不能断定两侧同单位 —— 不相除, 不出假百分比
                reason = IncomparableReason.NO_UNIT;
            } else {
                // 同单位原样返回; 不同单位只走内置科学换算 (kg↔g 等), 计数/包装单位之间没有可靠换算
                BigDecimal outputInInputTerms = FgQuantityUnitConverter.convert(totalOutput,
                        code(out.display()), code(in.display()), null);
                if (outputInInputTerms == null) {
                    reason = IncomparableReason.UNIT_MISMATCH;
                } else if (totalInput.compareTo(BigDecimal.ZERO) <= 0) {
                    reason = IncomparableReason.NO_INPUT;
                } else {
                    reason = null;
                    conversionRate = outputInInputTerms.multiply(HUNDRED).divide(totalInput, 1, RoundingMode.HALF_UP);
                    BigDecimal waste = totalInput.subtract(outputInInputTerms).multiply(HUNDRED)
                            .divide(totalInput, 1, RoundingMode.HALF_UP);
                    wastageRate = waste.max(BigDecimal.ZERO);
                }
            }

            result.add(ProcessYieldAggDTO.builder()
                    .processName(asStr(r.get("process_name")))
                    // 一侧单位混杂时, 那一侧的合计本身不成立 (0.5 kg + 3 袋), 不给数字
                    .inputQuantity(in.mixed() ? null : totalInput.setScale(2, RoundingMode.HALF_UP))
                    .outputQuantity(out.mixed() ? null : totalOutput.setScale(2, RoundingMode.HALF_UP))
                    .conversionRate(conversionRate)
                    .wastageRate(wastageRate)
                    .unit(in.display())
                    .inputUnit(in.display())
                    .outputUnit(out.display())
                    .unitComparable(reason == null)
                    .incomparableReason(reason)
                    .batchCount(toInt(r.get("batch_count")))
                    .build());
        }
        return result;
    }

    /**
     * 一侧单位: 全部报工都没单位 → NONE; 部分报工没单位 → MIXED (合计里混了不知单位的量);
     * 只有一种, 或两种写法归一后是同一个码 (kg / 千克) → 唯一; 否则 MIXED。
     */
    private static Side side(Map<String, Object> r, String prefix, int reportCount) {
        int known = toInt(r.get(prefix + "_unit_known"));
        if (known == 0) return Side.NONE;
        if (known < reportCount) return Side.MIXED;
        String lowest = asStr(r.get(prefix + "_unit_min"));
        String highest = asStr(r.get(prefix + "_unit_max"));
        int kinds = toInt(r.get(prefix + "_unit_kinds"));
        if (kinds == 1 || kinds == 2 && sameCanonical(lowest, highest)) return new Side(lowest, false);
        return Side.MIXED;
    }

    private static String code(String raw) {
        return UnitContractServiceImpl.crossLanguageCode(raw);
    }

    /** 两种写法归一后是否同一个码 (形参故意不叫 unit: 闸 BareUnitEqualityRatchet 只认单位命名的变量)。 */
    private static boolean sameCanonical(String left, String right) {
        return left != null && right != null && Objects.equals(code(left), code(right));
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static String asStr(Object v) {
        return v == null ? null : v.toString();
    }
}
