package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 回料池「这次领走的回料是哪几个生产批次产的」—— 缺口 03 (Steve 2026-11-03: 回料从哪批来、去了哪批、用了多少都进追溯)。
 *
 * <h3>为什么需要它</h3>
 * 回料常驻在池化锚 {@code CLK-SEMI-<计划前8>-<产品前8>} 上: 同计划同产品的多次回料产出累加在一行,
 * 而且回料是工序的<b>纯库存产出</b> (SAVED_SFI), 自己没有生产批次。{@link SemiSourceBatchResolver} 对池化锚
 * 按设计不猜 (⛔ 不挑一个塞进去), 于是回料领用一条边都写不出来 (2026-11-03 F006 实跑: 下游批追溯里没有回料来源)。
 *
 * <h3>怎么归属 (只用现成数据, 不新增存储)</h3>
 * <ol>
 *   <li>池子的<b>入账</b>: 该锚下全部 SAVED_SFI 报工行, 按行 id 排序; 每行的量 = 行内 {@code outputQuantity};</li>
 *   <li>每个入账的<b>产出批次</b>: 同一个多产出报工组 ({@code <base>#0..n}, 同计划同工序) 里已物化出生产批次的兄弟行
 *       —— 回料是这道工序检/加工出来的, 产出它的就是这道工序在本组物化的批次; 组内有多个物化批次时取最早的那个;</li>
 *   <li>领用按<b>先进先出</b>: 已被领走 {@code consumedBefore} 之后的 {@code qty} 落在哪几笔入账上, 就按重叠量拆成几段。</li>
 * </ol>
 * ⛔ 找不到产出批次的入账 (组里没有物化兄弟 / 不是多产出组) 不猜: 那一段不出边, 调用方按 AMBIGUOUS 记。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReworkPoolProducerAttribution {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SemiFinishedInventoryRepository sfiRepository;
    private final ProcessSheetRowRepository rowRepository;

    /** 一段归属: 这么多量来自这个生产批次。 */
    public record Slice(Long producerBatchId, BigDecimal quantity) {
    }

    /** 回料池的锚是回料行吗 (只对回料池做归属, 普通半成品不走这里)。 */
    public boolean isReworkPool(String factoryId, String intermediateBatchNo) {
        return load(factoryId, intermediateBatchNo).map(s -> Boolean.TRUE.equals(s.getIsRework())).orElse(false);
    }

    /**
     * @param consumedBefore 这次扣减<b>之前</b>池子已被领走的量 (来源单位)
     * @param quantity       这次领走的量 (来源单位, 实际扣减量)
     * @return 能归属到生产批次的各段; 全部归属不了返回空表
     */
    public List<Slice> attribute(String factoryId, String intermediateBatchNo,
                                 BigDecimal consumedBefore, BigDecimal quantity) {
        if (factoryId == null || intermediateBatchNo == null || quantity == null || quantity.signum() <= 0) {
            return List.of();
        }
        BigDecimal start = consumedBefore == null || consumedBefore.signum() < 0 ? BigDecimal.ZERO : consumedBefore;
        BigDecimal end = start.add(quantity);
        List<ProcessSheetRow> intakeRows = rowRepository
                .findByFactoryIdAndBatchNumberAndRowStatus(factoryId, intermediateBatchNo.trim(),
                        ProcessSheetRow.STATUS_SAVED_SFI)
                .stream().sorted(Comparator.comparing(ProcessSheetRow::getId)).toList();
        List<Slice> slices = new ArrayList<>();
        BigDecimal cursor = BigDecimal.ZERO;
        for (ProcessSheetRow intake : intakeRows) {
            BigDecimal in = outputQuantityOf(intake);
            if (in == null || in.signum() <= 0) {
                continue; // 读不出量就不占区间 (⛔ 不当 0 也不猜), 后面的入账区间会前移
            }
            BigDecimal from = cursor;
            BigDecimal to = cursor.add(in);
            cursor = to;
            BigDecimal overlap = to.min(end).subtract(from.max(start));
            if (overlap.signum() <= 0) {
                continue;
            }
            Long producer = producerBatchOf(factoryId, intake);
            if (producer != null) {
                slices.add(new Slice(producer, overlap));
            }
        }
        return slices;
    }

    private Optional<SemiFinishedInventory> load(String factoryId, String batchNo) {
        if (factoryId == null || batchNo == null || batchNo.isBlank()) {
            return Optional.empty();
        }
        return sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, batchNo.trim());
    }

    private static BigDecimal outputQuantityOf(ProcessSheetRow row) {
        try {
            JsonNode q = MAPPER.readTree(row.getRowPayload()).path("outputQuantity");
            return q.isNumber() ? q.decimalValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 多产出组 {@code <base>#i}: 同计划同工序里已物化出生产批次的兄弟行里最早的那个。 */
    private Long producerBatchOf(String factoryId, ProcessSheetRow intake) {
        String clientRowId = intake.getClientRowId();
        int hash = clientRowId == null ? -1 : clientRowId.lastIndexOf('#');
        if (hash <= 0) {
            return null; // 不是多产出组: 没有兄弟批次可指
        }
        String prefix = clientRowId.substring(0, hash + 1);
        return rowRepository.findByFactoryIdAndPlanIdAndProcessCode(factoryId, intake.getPlanId(), intake.getProcessCode())
                .stream()
                .filter(r -> r.getClientRowId() != null && r.getClientRowId().startsWith(prefix))
                .filter(r -> r.getBatchId() != null)
                .min(Comparator.comparing(ProcessSheetRow::getId))
                .map(ProcessSheetRow::getBatchId)
                .orElse(null);
    }
}
