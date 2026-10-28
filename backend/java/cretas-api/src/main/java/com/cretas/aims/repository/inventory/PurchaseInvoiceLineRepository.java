package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoiceLine;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 采购发票行 + 三单匹配所需的收货行读取（S5-05/06/07）。全部按工厂收口。 */
@Repository
public interface PurchaseInvoiceLineRepository extends JpaRepository<PurchaseInvoiceLine, Long> {

    List<PurchaseInvoiceLine> findByFactoryIdAndPurchaseOrderIdOrderByIdAsc(String factoryId, String purchaseOrderId);

    List<PurchaseInvoiceLine> findByFactoryIdAndInvoiceIdInOrderByIdAsc(String factoryId, Collection<String> invoiceIds);

    /** 本采购单下全部已确认收货单（有效收货）。 */
    @Query("SELECT r FROM PurchaseReceiveRecord r WHERE r.factoryId = :factoryId "
            + "AND r.purchaseOrderId = :purchaseOrderId "
            + "AND r.status = com.cretas.aims.entity.enums.PurchaseReceiveStatus.CONFIRMED")
    List<PurchaseReceiveRecord> findConfirmedReceipts(@Param("factoryId") String factoryId,
                                                      @Param("purchaseOrderId") String purchaseOrderId);

    /** 已确认收货单的明细行。 */
    @Query("SELECT i FROM PurchaseReceiveItem i WHERE i.receiveRecordId IN :receiveRecordIds ORDER BY i.id")
    List<PurchaseReceiveItem> findReceiveItems(@Param("receiveRecordIds") Collection<String> receiveRecordIds);
}
