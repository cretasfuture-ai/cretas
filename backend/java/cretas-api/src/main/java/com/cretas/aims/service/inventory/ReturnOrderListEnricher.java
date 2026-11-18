package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 退货单列表补 counterpartyName / sourceOrderNumber (G8)。
 * 详情接口已经会填这两个 @Transient 字段, 列表以前不填, 页面只能显示「未记录」。
 * 这里按页一次批量查 (4 条 IN 查询, 不逐行 N+1); 所有查询带 factory_id, 查不到的保持 null (不猜)。
 */
@Service
public class ReturnOrderListEnricher {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public void enrich(String factoryId, List<ReturnOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Set<String> customerIds = new HashSet<>();
        Set<String> supplierIds = new HashSet<>();
        Set<String> salesIds = new HashSet<>();
        Set<String> purchaseIds = new HashSet<>();
        for (ReturnOrder o : orders) {
            if (o.getCounterpartyId() != null) {
                (o.getReturnType() == ReturnType.PURCHASE_RETURN ? supplierIds : customerIds).add(o.getCounterpartyId());
            }
            if (o.getSourceOrderId() != null) {
                (o.getReturnType() == ReturnType.PURCHASE_RETURN ? purchaseIds : salesIds).add(o.getSourceOrderId());
            }
        }
        Map<String, String> customers = names("SELECT e.id, e.name FROM Customer e WHERE e.factoryId = :f AND e.id IN :ids",
                factoryId, customerIds);
        Map<String, String> suppliers = names("SELECT e.id, e.name FROM Supplier e WHERE e.factoryId = :f AND e.id IN :ids",
                factoryId, supplierIds);
        Map<String, String> salesOrders = names(
                "SELECT e.id, e.orderNumber FROM SalesOrder e WHERE e.factoryId = :f AND e.id IN :ids", factoryId, salesIds);
        Map<String, String> purchaseOrders = names(
                "SELECT e.id, e.orderNumber FROM PurchaseOrder e WHERE e.factoryId = :f AND e.id IN :ids", factoryId, purchaseIds);

        for (ReturnOrder o : orders) {
            boolean purchase = o.getReturnType() == ReturnType.PURCHASE_RETURN;
            if (o.getCounterpartyId() != null) {
                o.setCounterpartyName((purchase ? suppliers : customers).get(o.getCounterpartyId()));
            }
            if (o.getSourceOrderId() != null) {
                o.setSourceOrderNumber((purchase ? purchaseOrders : salesOrders).get(o.getSourceOrderId()));
            }
        }
    }

    private Map<String, String> names(String jpql, String factoryId, Set<String> ids) {
        Map<String, String> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        List<Object[]> rows = em.createQuery(jpql, Object[].class)
                .setParameter("f", factoryId).setParameter("ids", ids).getResultList();
        for (Object[] r : rows) {
            map.put((String) r[0], (String) r[1]);
        }
        return map;
    }
}
