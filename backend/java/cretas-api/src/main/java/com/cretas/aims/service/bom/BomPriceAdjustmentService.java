package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface BomPriceAdjustmentService {

    List<BomPriceAdjustmentProposal> generateFromReceive(String factoryId, PurchaseReceiveRecord receiveRecord);

    List<BomPriceAdjustmentProposal> generateForMaterial(
            String factoryId,
            String materialTypeId,
            String materialName,
            BigDecimal latestPreTaxPrice,
            BomPriceAdjustmentProposal.SourceType sourceType,
            String sourceReceiveRecordId,
            Long sourceReceiveItemId);

    /**
     * 批准一条调价建议。{@code expectedUnitPrice} 是审批人看到的建议价: 传了就必须仍等于建议行上的价,
     * 否则 409 {@code BOM_PRICE_PROPOSAL_STALE}; 不传不比 (向后兼容)。
     */
    BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment,
                                       BigDecimal expectedUnitPrice);

    /** 不比对审批人看到的价 (向后兼容)。⛔ 不写成 default 方法: 代理上调 default 方法不带事务, 内部再调是自调用。 */
    BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment);

    Page<BomPriceAdjustmentProposal> list(
            String factoryId, BomPriceAdjustmentProposal.Status status, Pageable pageable);
}
