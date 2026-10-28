package com.cretas.aims.dto.inventory;

import java.math.BigDecimal;

/**
 * 登记发票时的一行（S5-05/06/07）。
 *
 * @param purchaseOrderItemId 必填：开的是哪条采购单行
 * @param receiveItemId       可选：开的是哪条已确认收货行
 * @param materialTypeId      可选：发票上写的物料；填了就逐行核对是否与采购单行一致
 * @param quantity            必填：开票数量，单位 = 采购单行单位
 * @param unitPrice           可选：发票单价（仅记录）
 * @param amount              必填：本行含税金额
 */
public record PurchaseInvoiceLineRequest(
        Long purchaseOrderItemId,
        Long receiveItemId,
        String materialTypeId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount) {
}
