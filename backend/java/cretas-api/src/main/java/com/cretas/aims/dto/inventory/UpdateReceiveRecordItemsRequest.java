package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * M-15 (2026-08-20): 修改一张 DRAFT 收货单的行项目数量 —— 分批收货。
 *
 * <p>背景：草稿一旦创建，此前唯一的调整方式是删了重建，而删除入口又不存在，
 * 于是"建错→改不了→删不掉"三条各自独立的缺陷叠成死锁（见台账 M-15）。
 * 本请求只覆盖<b>既有</b>行的数量与可追溯字段，<b>不支持</b>新增/删除行 ——
 * 如需收不同物料组合，请先 {@link com.cretas.aims.service.inventory.PurchaseService#cancelReceive}
 * 作废本草稿再重新创建。
 *
 * <p>复用 {@link CreateReceiveRecordRequest.ReceiveItemDTO} 作为行结构，
 * 与创建时的 payload 形状一致，前端可共享同一份行数据模型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateReceiveRecordItemsRequest {

    @Valid
    @NotEmpty(message = "入库行项目不能为空")
    private List<CreateReceiveRecordRequest.ReceiveItemDTO> items;
}
