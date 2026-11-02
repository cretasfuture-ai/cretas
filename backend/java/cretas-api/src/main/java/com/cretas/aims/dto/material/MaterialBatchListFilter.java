package com.cretas.aims.dto.material;

import java.time.LocalDate;

/**
 * 批次列表在 {@code PageRequest} (页码 / 排序 / 关键词 / 状态) 之外的筛选条件。
 * 全部在数据库里、分页之前生效; null = 不限。
 *
 * @param materialTypeId 原料类型
 * @param receiptFrom    入库日期下限 (含)
 * @param receiptTo      入库日期上限 (含)
 */
public record MaterialBatchListFilter(String materialTypeId, LocalDate receiptFrom, LocalDate receiptTo) {

    public static final MaterialBatchListFilter NONE = new MaterialBatchListFilter(null, null, null);

    public boolean isEmpty() {
        return (materialTypeId == null || materialTypeId.isBlank()) && receiptFrom == null && receiptTo == null;
    }
}
