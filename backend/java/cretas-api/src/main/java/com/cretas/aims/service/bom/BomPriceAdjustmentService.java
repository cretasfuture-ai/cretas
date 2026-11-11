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

    /**
     * 驳回一条待审批的调价建议 (D-S5-09): 配方行价一分不动, 建议置 REJECTED 并记下驳回人、时间与原因。
     * 原因必填 (空白 400 {@code BOM_PRICE_PROPOSAL_REJECT_REASON_REQUIRED}); 非 PENDING 409。
     * 驳回人由调用方从登录身份取, ⛔ 不接受请求体里的人。
     */
    BomPriceAdjustmentProposal reject(String factoryId, Long proposalId, Long rejectorId, String reason);

    /** 详情: 只看本厂的建议, 别厂 / 不存在 404。 */
    BomPriceAdjustmentProposal get(String factoryId, Long proposalId);

    Page<BomPriceAdjustmentProposal> list(
            String factoryId, BomPriceAdjustmentProposal.Status status, Pageable pageable);
}
