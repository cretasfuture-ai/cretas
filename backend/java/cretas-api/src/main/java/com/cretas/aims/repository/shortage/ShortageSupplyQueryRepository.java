package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCaseLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 缺口刷新要读的「快来了」的量: 质检中的本厂库存、已下单未到的采购量。只读, 都按工厂取。
 *
 * <p>放在缺料自己的仓库里, 不动库存 / 采购的共享仓库 (那两块另有所有者)。
 */
@org.springframework.stereotype.Repository
public interface ShortageSupplyQueryRepository extends Repository<ShortageCaseLine, Long> {

    /**
     * 本厂、公司自有、<b>首检中</b>的剩余量 (收货 / 产出后等质检放行, 放行后才会变成可用)。
     *
     * <p>#1590 遗留 M3: 被扣留的质检中 (冻结 / CCP 偏离扣留 / 冷链转待检 / 仓库页改成质检中) 不算 ——
     * 那是本来就在库里、因为可疑被拦下的货, 不是正在到来的供应; 算进来会让缺料实例以为缺口有着落
     * (阶段停在「等供应」、负责人转给质检), 实际上那批货多半要报废。判据: 记了扣留前状态
     * ({@code holdPriorStatus}); 本列上线前冻结的老数据没有这一列, 认冻结工具写在备注里的审计标记。
     * 状态口径 (只此一处): 只数 INSPECTING; 不良品 / 过期 / 报废 / 已用完是永远不会变成可用的货, 一律不算;
     * 可用 / 鲜品 / 冻品 等已在库可用的量由库存检查算进「已放行」, 不在这里。
     */
    @Query("SELECT COALESCE(SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity), 0) FROM MaterialBatch m "
            + "WHERE m.factoryId = :factoryId AND m.materialTypeId = :materialTypeId "
            + "AND m.status = com.cretas.aims.entity.enums.MaterialBatchStatus.INSPECTING "
            + "AND m.holdPriorStatus IS NULL "
            + "AND (m.notes IS NULL OR m.notes NOT LIKE '%[FREEZE by user=%') "
            + "AND (m.ownership IS NULL OR m.ownership = com.cretas.aims.entity.enums.InventoryOwnership.COMPANY_OWNED) "
            + "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0")
    BigDecimal sumInspectingQuantity(@Param("factoryId") String factoryId,
                                     @Param("materialTypeId") String materialTypeId);

    /**
     * 已下单 (提交之后、收完之前) 的采购行: [单位, 未收量]。草稿不算 —— 「只推进到已下单」才叫在途。
     */
    @Query("SELECT i.unit, (i.quantity - COALESCE(i.receivedQuantity, 0)) FROM PurchaseOrderItem i, PurchaseOrder o "
            + "WHERE o.id = i.purchaseOrderId AND o.factoryId = :factoryId AND i.materialTypeId = :materialTypeId "
            + "AND o.deletedAt IS NULL AND i.deletedAt IS NULL "
            + "AND o.status IN (com.cretas.aims.entity.enums.PurchaseOrderStatus.SUBMITTED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.WORKFLOW_RUNNING, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PENDING_FINANCE_REVIEW, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.FINANCE_APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PARTIAL_RECEIVED) "
            + "AND (i.quantity - COALESCE(i.receivedQuantity, 0)) > 0")
    List<Object[]> findOpenOrderRemaining(@Param("factoryId") String factoryId,
                                          @Param("materialTypeId") String materialTypeId);
}
