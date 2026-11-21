package com.cretas.aims.service.inventory;

import com.cretas.aims.security.BusinessRowScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商 / 客户账本汇总 (原型缺口 G4).
 *
 * <p>口径 (算不出来的返回 null, 不返回 0 冒充):
 * <ul>
 *   <li>采购额 / 合同额: 已提交且未取消、未驳回的订单 totalAmount 之和 (草稿不算)。</li>
 *   <li>待收货额 / 待发货额: 执行中订单行 (订购-已收|已发) × 单价; 任一行缺单价则整项 null。</li>
 *   <li>待开票额: 采购 = 发票状态「未收到」的订单金额; 销售 = 已发过货的订单 总额-已开票额(不为负)。</li>
 *   <li>待付款 / 待收款: AP / AR 台账净额 (挂账+已批准调整-付款-冲减); 已付款 / 已收款: 付款 / 收款流水绝对值之和。</li>
 * </ul>
 * 财务台账目前按全厂口径汇总。受归属限制的账号暂不返回该汇总值。
 */
@Service
public class CounterpartyLedgerSummaryService {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private BusinessRowScope businessRowScope;

    private static final String PO = "com.cretas.aims.entity.enums.PurchaseOrderStatus.";
    private static final String SO = "com.cretas.aims.entity.enums.SalesOrderStatus.";
    private static final String TT = "com.cretas.aims.entity.enums.ArApTransactionType.";

    private static final String PO_EXECUTING = "(" + PO + "FINANCE_APPROVED," + PO + "PARTIAL_RECEIVED)";
    private static final String PO_COMMITTED = "(" + PO + "SUBMITTED," + PO + "WORKFLOW_RUNNING," + PO + "APPROVED,"
            + PO + "PENDING_FINANCE_REVIEW," + PO + "FINANCE_APPROVED," + PO + "PARTIAL_RECEIVED," + PO + "COMPLETED,"
            + PO + "CLOSED)";
    private static final String SO_EXECUTING = "(" + SO + "CONFIRMED," + SO + "FINANCE_APPROVED," + SO + "PROCESSING,"
            + SO + "PARTIAL_DELIVERED)";
    private static final String SO_COMMITTED = "(" + SO + "CONFIRMED," + SO + "PENDING_FINANCE_REVIEW,"
            + SO + "FINANCE_APPROVED," + SO + "PROCESSING," + SO + "PARTIAL_DELIVERED," + SO + "COMPLETED,"
            + SO + "SHORT_CLOSED)";
    private static final String SO_DELIVERED = "(" + SO + "PARTIAL_DELIVERED," + SO + "COMPLETED," + SO + "SHORT_CLOSED)";

    private static final String APPROVED = "t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED";
    private static final String AR_NET = "SUM(CASE WHEN t.transactionType = " + TT + "AR_INVOICE THEN t.amount "
            + "WHEN t.transactionType IN (" + TT + "AR_ADJUSTMENT," + TT + "AR_REFUND) AND " + APPROVED + " THEN t.amount "
            + "WHEN t.transactionType IN (" + TT + "AR_PAYMENT," + TT + "AR_CREDIT_NOTE) THEN -ABS(t.amount) ELSE 0 END)";
    private static final String AP_NET = "SUM(CASE WHEN t.transactionType = " + TT + "AP_INVOICE THEN t.amount "
            + "WHEN t.transactionType = " + TT + "AP_ADJUSTMENT AND " + APPROVED + " THEN t.amount "
            + "WHEN t.transactionType IN (" + TT + "AP_PAYMENT," + TT + "AP_CREDIT_NOTE) THEN -ABS(t.amount) ELSE 0 END)";

    @Transactional(readOnly = true)
    public Map<String, Object> supplierSummary(String factoryId, String supplierId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object[] po = em.createQuery("SELECT COUNT(o), COALESCE(SUM(o.totalAmount),0) FROM PurchaseOrder o "
                + "WHERE o.factoryId=:f AND o.supplierId=:p AND o.status IN " + PO_COMMITTED, Object[].class)
                .setParameter("f", factoryId).setParameter("p", supplierId).getSingleResult();
        m.put("committedOrderCount", ((Number) po[0]).longValue());
        m.put("purchaseAmount", dec(po[1]));
        m.put("pendingReceiptAmount", pendingAmount(
                "SELECT COALESCE(SUM((i.quantity - COALESCE(i.receivedQuantity,0)) * COALESCE(i.quantityToPriceFactor,1) * i.unitPrice),0), "
                        + "SUM(CASE WHEN i.unitPrice IS NULL THEN 1 ELSE 0 END) "
                        + "FROM PurchaseOrderItem i JOIN i.purchaseOrder o WHERE o.factoryId=:f AND o.supplierId=:p "
                        + "AND o.status IN " + PO_EXECUTING + " AND i.quantity > COALESCE(i.receivedQuantity,0)",
                factoryId, supplierId));
        m.put("pendingInvoiceAmount", dec(em.createQuery("SELECT COALESCE(SUM(o.totalAmount),0) FROM PurchaseOrder o "
                + "WHERE o.factoryId=:f AND o.supplierId=:p AND o.status IN " + PO_COMMITTED
                + " AND o.invoiceStatus = com.cretas.aims.entity.enums.PurchaseInvoiceStatus.NOT_RECEIVED", BigDecimal.class)
                .setParameter("f", factoryId).setParameter("p", supplierId).getSingleResult()));
        ledger(m, AP_NET, "AP_PAYMENT", "SUPPLIER", factoryId, supplierId, "pendingPayable", "paidAmount");
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> customerSummary(String factoryId, String customerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object[] so = em.createQuery("SELECT COUNT(o), COALESCE(SUM(o.totalAmount),0), MAX(o.orderDate) FROM SalesOrder o "
                + "WHERE o.factoryId=:f AND o.customerId=:p AND o.status IN " + SO_COMMITTED, Object[].class)
                .setParameter("f", factoryId).setParameter("p", customerId).getSingleResult();
        long cnt = ((Number) so[0]).longValue();
        BigDecimal total = dec(so[1]);
        m.put("committedOrderCount", cnt);
        m.put("contractAmount", total);
        m.put("averageOrderValue", cnt == 0 ? null : total.divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP));
        m.put("lastOrderDate", so[2] == null ? null : so[2].toString());
        m.put("pendingShipmentAmount", pendingAmount(
                "SELECT COALESCE(SUM((i.quantity - COALESCE(i.deliveredQuantity,0)) * i.unitPrice),0), "
                        + "SUM(CASE WHEN i.unitPrice IS NULL THEN 1 ELSE 0 END) "
                        + "FROM SalesOrderItem i JOIN i.salesOrder o WHERE o.factoryId=:f AND o.customerId=:p "
                        + "AND o.status IN " + SO_EXECUTING + " AND i.quantity > COALESCE(i.deliveredQuantity,0)",
                factoryId, customerId));
        m.put("pendingInvoiceAmount", dec(em.createQuery("SELECT COALESCE(SUM(o.totalAmount - COALESCE(o.invoicedAmount,0)),0) "
                + "FROM SalesOrder o WHERE o.factoryId=:f AND o.customerId=:p AND o.status IN " + SO_DELIVERED
                + " AND o.totalAmount > COALESCE(o.invoicedAmount,0)", BigDecimal.class)
                .setParameter("f", factoryId).setParameter("p", customerId).getSingleResult()));
        ledger(m, AR_NET, "AR_PAYMENT", "CUSTOMER", factoryId, customerId, "pendingCollection", "receivedAmount");
        return m;
    }

    /** 客户购买历史: 每个订单行一条, 按订单日倒序。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerPurchaseHistory(String factoryId, String customerId) {
        List<Object[]> rows = em.createQuery("SELECT o.orderNumber, o.orderDate, o.status, i.productName, i.quantity, i.unit, "
                + "i.unitPrice, i.deliveredQuantity FROM SalesOrderItem i JOIN i.salesOrder o "
                + "WHERE o.factoryId=:f AND o.customerId=:p AND o.status IN " + SO_COMMITTED
                + " ORDER BY o.orderDate DESC, o.orderNumber DESC", Object[].class)
                .setParameter("f", factoryId).setParameter("p", customerId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("orderNumber", r[0]);
            h.put("orderDate", r[1] == null ? null : ((LocalDate) r[1]).toString());
            h.put("status", r[2] == null ? null : r[2].toString());
            h.put("productName", r[3]);
            h.put("quantity", r[4]);
            h.put("unit", r[5]);
            h.put("unitPrice", r[6]);
            h.put("amount", (r[4] == null || r[6] == null) ? null : ((BigDecimal) r[4]).multiply((BigDecimal) r[6]));
            h.put("deliveredQuantity", r[7]);
            out.add(h);
        }
        return out;
    }

    private void ledger(Map<String, Object> m, String netExpr, String paymentType, String cpType,
                        String factoryId, String cpId, String pendingKey, String paidKey) {
        if (BusinessRowScope.recordCreators(businessRowScope, factoryId) != null) {
            m.put(pendingKey, null);
            m.put(paidKey, null);
            return;
        }
        String scope = " FROM ArApTransaction t WHERE t.factoryId=:f AND t.counterpartyType = "
                + "com.cretas.aims.entity.enums.CounterpartyType." + cpType + " AND t.counterpartyId=:p";
        m.put(pendingKey, dec(em.createQuery("SELECT COALESCE(" + netExpr + ",0)" + scope, BigDecimal.class)
                .setParameter("f", factoryId).setParameter("p", cpId).getSingleResult()));
        m.put(paidKey, dec(em.createQuery("SELECT COALESCE(SUM(ABS(t.amount)),0)" + scope
                + " AND t.transactionType = " + TT + paymentType, BigDecimal.class)
                .setParameter("f", factoryId).setParameter("p", cpId).getSingleResult()));
    }

    /** 有任一行缺单价 → 算不出, 返回 null。 */
    private BigDecimal pendingAmount(String jpql, String factoryId, String cpId) {
        Object[] r = em.createQuery(jpql, Object[].class).setParameter("f", factoryId).setParameter("p", cpId)
                .getSingleResult();
        if (r[1] != null && ((Number) r[1]).longValue() > 0) {
            return null;
        }
        return dec(r[0]);
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : (o instanceof BigDecimal b ? b : new BigDecimal(o.toString()));
    }
}
