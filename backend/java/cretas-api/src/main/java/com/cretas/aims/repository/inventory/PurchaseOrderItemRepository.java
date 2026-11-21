package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    /** Candidate reference prices for a purchase order line, in effective-date priority. */
    @Query("""
            SELECT i.priceListId AS priceListId, pl.name AS priceListName, i.unit AS unit, i.standardPrice AS standardPrice
            FROM PriceListItem i JOIN i.priceList pl
            WHERE pl.factoryId = :factoryId AND pl.priceType = 'PURCHASE_PRICE' AND pl.customerId IS NULL
              AND pl.isActive = true AND pl.effectiveFrom <= :asOf
              AND (pl.effectiveTo IS NULL OR pl.effectiveTo >= :asOf)
              AND i.materialTypeId = :materialTypeId AND i.standardPrice >= 0
            ORDER BY pl.effectiveFrom DESC, pl.createdAt DESC, pl.id ASC, i.id ASC
            """)
    List<PurchasePriceReference> findPurchasePriceReferences(@Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId, @Param("asOf") LocalDate asOf);

    interface PurchasePriceReference {
        String getPriceListId();
        String getPriceListName();
        String getUnit();
        BigDecimal getStandardPrice();
    }

    List<PurchaseOrderItem> findByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseOrderItem> findByMaterialTypeId(String materialTypeId);

    /**
     * 按 ID + PO 隶属关系查 item（行项目范围验证安全查法）.
     *
     * <p>确保 lineId 真属于 poId（防止跨 PO 访问）。结合上游对 PO 的
     * factoryId 校验，形成完整的租户 → 订单 → 行项目三层隔离。
     */
    Optional<PurchaseOrderItem> findByIdAndPurchaseOrderId(Long id, String purchaseOrderId);

    /**
     * D-9 G1 批量加载：一次查询多个 PO 的所有行（避免 N+1）。
     * 用于出纳视图 listApprovedForPaymentWithDetails() 批量 hydrate items。
     */
    @Query("SELECT i FROM PurchaseOrderItem i WHERE i.purchaseOrderId IN :purchaseOrderIds AND i.deletedAt IS NULL")
    List<PurchaseOrderItem> findByPurchaseOrderIdIn(@Param("purchaseOrderIds") List<String> purchaseOrderIds);
}
