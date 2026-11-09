package com.cretas.aims.service.production;

import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结单时按逐道录入行扣掉的常驻半成品 / 成品投料明细 (N1, 2026-11-09)。
 *
 * <p>结单扣这些料时 ({@code ProductionPlanServiceImpl#deductProcessSheetStockFeeds}) 逐笔记下
 * 「哪一行、扣了哪个批次、扣了多少 (来源原生单位)」, 存在结单上 ({@code production_settlements.stock_feed_detail})。
 * 整单撤回照着它原样还回, 并把这些行重新标成待扣 —— 再结单时它们才会且只会再扣一次。
 *
 * <p>⛔ 撤回时不从行上的 kg 现算: 结单到撤回之间每盒克重可能改过, 现算还回的盒数就和当时扣的对不上。
 *
 * <p>{@code null} 列 = 这个功能上线前的结单 (没有明细); 新结单一律写 (没有投料就是 {@code []}),
 * 所以撤回能分清「没扣过」和「扣过但没记」。
 */
public final class SettlementStockFeedDetail {

    /** 常驻半成品投料 ({@code WipInventoryService#consumeClerkSemiStrict})。 */
    public static final String KIND_SEMI = "SEMI_FINISHED";
    /** 成品投料 ({@code FinishedGoodsFeedService#consumeForFeedStrict})。 */
    public static final String KIND_FINISHED = "FINISHED_GOODS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 一笔扣减。
     *
     * @param kind        {@link #KIND_SEMI} / {@link #KIND_FINISHED}
     * @param batchNumber 被扣的半成品批次号 / 成品批次号
     * @param feedKg      行上记的投料量 (kg), 只做核对展示
     * @param quantity    实际扣掉的量 (来源原生单位), 撤回按它还
     * @param rowId       哪一行逐道录入扣的
     */
    public record Entry(String kind, String batchNumber, BigDecimal feedKg, BigDecimal quantity, Long rowId) {
    }

    private SettlementStockFeedDetail() {
    }

    public static String write(List<Entry> entries) {
        try {
            return MAPPER.writeValueAsString(entries == null ? List.of() : entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结单投料扣减明细无法写入结单", e);
        }
    }

    /**
     * @return 明细; {@code detail == null} 时返回 {@code null} (旧结单没有明细, 调用方自己决定能不能撤)
     */
    public static List<Entry> parse(String detail) {
        if (detail == null) {
            return null;
        }
        if (detail.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(detail, new TypeReference<List<Entry>>() { });
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "结单的投料扣减明细无法解析, 不能撤回")
                    .withCode("SETTLEMENT_STOCK_FEED_DETAIL_INVALID")
                    .withHint("请联系管理员核对这张结单的投料扣减明细");
        }
    }
}
