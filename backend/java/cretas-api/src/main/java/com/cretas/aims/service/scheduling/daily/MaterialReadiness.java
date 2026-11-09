package com.cretas.aims.service.scheduling.daily;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原料够不够、不够的话最早哪天能开工 (缺口 18)。纯计算, 不读库。
 *
 * <p>口径 (Steve 2026-11-09): 多个计划抢同一种原料, 按调用方给的顺序先到先得 (调用方已按交期排好);
 * 现有可用库存先用, 不够的部分按在走采购单的预计到货日从早到晚凑, 凑够那张单的到货日<b>次日</b>才能开工。
 * 凑不够 (没有采购单 / 采购单没填到货日 / 单位换不了 / 采购量也不够): ⛔ 不拦, 不猜日子, 照排并给提示。
 *
 * <p>提示里只说「哪种原料不够、哪天到」, ⛔ 不说用量 (用量来自配方, 可能是工艺保密内容)。
 */
public final class MaterialReadiness {

    private MaterialReadiness() {
    }

    /** 一个计划对一种原料的需要量, 已折成库存单位。 */
    public record Need(String materialId, String materialName, BigDecimal quantity) {
    }

    /** 一张在走采购单上还没到的量 (库存单位)。 */
    public record Arrival(LocalDate date, BigDecimal quantity) {
    }

    /**
     * 一种原料的供应。
     *
     * @param freeStock       现在可用 (库存单位)
     * @param arrivals        有到货日、单位换得过来的在走采购
     * @param unknownArrivals 另有在走采购单没填到货日或单位换不过来 (这部分量不知道哪天到)
     * @param overdueArrivals 另有在走采购单已过预计到货日还没到 (⛔ 不当作已到货, 也不知道哪天到)
     */
    public record Supply(BigDecimal freeStock, List<Arrival> arrivals, boolean unknownArrivals,
                         boolean overdueArrivals) {
        public Supply(BigDecimal freeStock, List<Arrival> arrivals, boolean unknownArrivals) {
            this(freeStock, arrivals, unknownArrivals, false);
        }
    }

    /**
     * @param earliestStart 最早哪天能开工; null = 原料不卡 (够, 或卡不了只能提示)
     * @param reason        卡住时的说明 (哪种原料哪天到)
     * @param notes         没能核对的提示
     */
    public record Ready(LocalDate earliestStart, String reason, List<String> notes) {
    }

    public static Map<String, Ready> compute(List<String> orderedKeys, Map<String, List<Need>> needsByKey,
                                             Map<String, Supply> supplyByMaterial) {
        Map<String, BigDecimal> stock = new HashMap<>();
        Map<String, List<Arrival>> pending = new HashMap<>();
        supplyByMaterial.forEach((m, s) -> {
            stock.put(m, s.freeStock() == null || s.freeStock().signum() < 0 ? BigDecimal.ZERO : s.freeStock());
            List<Arrival> list = new ArrayList<>();
            for (Arrival a : s.arrivals()) {
                if (a.date() != null && a.quantity() != null && a.quantity().signum() > 0) {
                    list.add(a);
                }
            }
            list.sort(Comparator.comparing(Arrival::date));
            pending.put(m, list);
        });

        Map<String, Ready> out = new LinkedHashMap<>();
        for (String key : orderedKeys) {
            LocalDate earliest = null;
            List<String> reasons = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (Need need : needsByKey.getOrDefault(key, List.of())) {
                if (need.quantity() == null || need.quantity().signum() <= 0) {
                    continue;
                }
                Supply supply = supplyByMaterial.get(need.materialId());
                if (supply == null) {
                    notes.add("原料「" + need.materialName() + "」的库存与采购没读到，没核对");
                    continue;
                }
                BigDecimal have = stock.getOrDefault(need.materialId(), BigDecimal.ZERO);
                if (have.compareTo(need.quantity()) >= 0) {
                    stock.put(need.materialId(), have.subtract(need.quantity()));
                    continue;
                }
                BigDecimal shortfall = need.quantity().subtract(have);
                // 先不动账: 采购凑得够才真扣, 凑不够就不占 (照排, 不卡后面的计划)
                List<Arrival> list = pending.getOrDefault(need.materialId(), List.of());
                BigDecimal covered = BigDecimal.ZERO;
                int used = 0;
                LocalDate lastDate = null;
                while (used < list.size() && covered.compareTo(shortfall) < 0) {
                    covered = covered.add(list.get(used).quantity());
                    lastDate = list.get(used).date();
                    used++;
                }
                if (covered.compareTo(shortfall) >= 0) {
                    stock.put(need.materialId(), BigDecimal.ZERO);
                    BigDecimal leftover = covered.subtract(shortfall);
                    List<Arrival> rest = new ArrayList<>(list.subList(used, list.size()));
                    if (leftover.signum() > 0) {
                        rest.add(0, new Arrival(lastDate, leftover));
                    }
                    pending.put(need.materialId(), rest);
                    LocalDate start = lastDate.plusDays(1);
                    reasons.add("原料「" + need.materialName() + "」库存不够，采购预计 " + lastDate + " 到货");
                    if (earliest == null || start.isAfter(earliest)) {
                        earliest = start;
                    }
                } else if (supply.overdueArrivals()) {
                    notes.add("原料「" + need.materialName() + "」库存不够，在走的采购单已过预计到货日还没到货，"
                            + "不知道哪天到，没按到货排；请跟供应商确认到货日");
                } else if (supply.unknownArrivals()) {
                    notes.add("原料「" + need.materialName() + "」库存不够，在走的采购单有的没填预计到货日（或单位对不上），"
                            + "不知道哪天到，没按到货排；请补到货日");
                } else if (list.isEmpty()) {
                    notes.add("原料「" + need.materialName() + "」库存不够，也没有在走的采购单，没按到货排；请安排采购");
                } else {
                    notes.add("原料「" + need.materialName() + "」库存加上在走的采购单也不够，没按到货排；请补采购");
                }
            }
            out.put(key, new Ready(earliest, earliest == null ? null : String.join("；", reasons), notes));
        }
        return out;
    }
}
