package com.cretas.aims.service.finance;

/**
 * Shared SQL owner projection for finance rows.  The expression deliberately returns NULL
 * for a broken, cross-factory, or unknown source; callers must never fall back to operated_by
 * for such a row.  A source-less manual/on-account row is owned by operated_by.
 */
public final class FinanceOwnershipSql {
    private FinanceOwnershipSql() {}

    /**
     * Fixed aliases keep the expression a Java compile-time constant so it can be embedded in
     * Spring Data {@code @Query} annotations.  RETURN_ORDER ownership follows the source order
     * and rejects a transaction whose other order foreign key contradicts that source.
     */
    private static final String RETURN_ORDER_OWNER_RO =
            "(CASE "
            + "WHEN ro.return_type='SALES_RETURN' AND ro.source_order_id IS NOT NULL "
            + "AND t.purchase_order_id IS NULL "
            + "AND (t.sales_order_id IS NULL OR t.sales_order_id=ro.source_order_id) "
            + "THEN so3.created_by "
            + "WHEN ro.return_type='PURCHASE_RETURN' AND ro.source_order_id IS NOT NULL "
            + "AND t.sales_order_id IS NULL "
            + "AND (t.purchase_order_id IS NULL OR t.purchase_order_id=ro.source_order_id) "
            + "THEN po3.created_by ELSE NULL END)";

    /** Fixed aliases for the allocation-backed AP settlement source. */
    private static final String PAYABLE_SETTLEMENT_OWNER_T =
            "(SELECT CASE WHEN COUNT(*)=1 THEN MAX(CASE "
            + "WHEN payable4.source_type='PURCHASE_RECEIVE' AND payable4.sales_order_id IS NULL THEN "
            + "(SELECT po4.created_by FROM purchase_receive_records r4 "
            + "JOIN purchase_orders po4 ON po4.id=r4.purchase_order_id AND po4.factory_id=r4.factory_id "
            + "AND po4.deleted_at IS NULL WHERE r4.id=payable4.source_id "
            + "AND r4.factory_id=payable4.factory_id AND r4.deleted_at IS NULL "
            + "AND (payable4.purchase_order_id IS NULL OR payable4.purchase_order_id=r4.purchase_order_id)) "
            + "WHEN payable4.source_type IS NULL AND payable4.source_id IS NULL "
            + "AND payable4.sales_order_id IS NULL THEN CASE WHEN payable4.purchase_order_id IS NULL "
            + "THEN payable4.operated_by ELSE (SELECT po4.created_by FROM purchase_orders po4 "
            + "WHERE po4.id=payable4.purchase_order_id AND po4.factory_id=payable4.factory_id "
            + "AND po4.deleted_at IS NULL) END ELSE NULL END) ELSE NULL END "
            + "FROM ar_ap_payment_allocations pa "
            + "JOIN ar_ap_transactions payable4 ON payable4.id=pa.payable_transaction_id "
            + "AND payable4.factory_id=pa.factory_id AND payable4.deleted_at IS NULL "
            + "AND payable4.transaction_type='AP_INVOICE' "
            + "WHERE pa.payment_transaction_id=t.id AND pa.factory_id=t.factory_id "
            + "AND pa.deleted_at IS NULL AND t.transaction_type='AP_PAYMENT' AND t.sales_order_id IS NULL "
            + "AND (SELECT COUNT(*) FROM ar_ap_payment_allocations pa_all "
            + "WHERE pa_all.payment_transaction_id=t.id AND pa_all.factory_id=t.factory_id "
            + "AND pa_all.deleted_at IS NULL)=1 "
            + "AND (t.purchase_order_id IS NULL OR t.purchase_order_id=payable4.purchase_order_id))";

    /** Authoritative refund-request owner, shared by AR/AP and voucher projections. */
    public static final String REFUND_REQUEST_OWNER =
            "(SELECT refund_so.created_by FROM return_orders refund_ro "
            + "JOIN sales_orders refund_so ON refund_so.id=refund_ro.source_order_id "
            + "AND refund_so.factory_id=refund_ro.factory_id AND refund_so.deleted_at IS NULL "
            + "WHERE refund_ro.id=refund_request.return_order_id "
            + "AND refund_ro.factory_id=refund_request.factory_id AND refund_ro.deleted_at IS NULL "
            + "AND refund_ro.return_type='SALES_RETURN' AND refund_request.source_type='SALES' "
            + "AND refund_request.purchase_order_id IS NULL "
            + "AND refund_request.sales_order_id=refund_ro.source_order_id "
            + "AND refund_request.customer_id=refund_ro.counterparty_id)";

    /** Expression for the fixed native-query alias {@code t}. */
    public static final String AR_AP_OWNER_T =
            "(CASE "
            + "WHEN t.source_type='SALES_DELIVERY' THEN "
            + "(SELECT so2.created_by FROM sales_delivery_records d "
            + "JOIN sales_orders so2 ON so2.id=d.sales_order_id AND so2.factory_id=d.factory_id "
            + "AND so2.deleted_at IS NULL WHERE d.id=t.source_id AND d.factory_id=t.factory_id "
            + "AND (t.sales_order_id IS NULL OR t.sales_order_id=d.sales_order_id) "
            + "AND d.deleted_at IS NULL) "
            + "WHEN t.source_type='PURCHASE_RECEIVE' THEN "
            + "(SELECT po2.created_by FROM purchase_receive_records r "
            + "JOIN purchase_orders po2 ON po2.id=r.purchase_order_id AND po2.factory_id=r.factory_id "
            + "AND po2.deleted_at IS NULL WHERE r.id=t.source_id AND r.factory_id=t.factory_id "
            + "AND (t.purchase_order_id IS NULL OR t.purchase_order_id=r.purchase_order_id) "
            + "AND r.deleted_at IS NULL) "
            + "WHEN t.source_type='PAYABLE_SETTLEMENT' THEN " + PAYABLE_SETTLEMENT_OWNER_T + " "
            + "WHEN (t.source_type='RETURN_REFUND' AND t.transaction_type='AR_REFUND' "
            + "OR t.source_type='RETURN_REFUND_REVERSAL' AND t.transaction_type='AR_ADJUSTMENT') "
            + "AND t.purchase_order_id IS NULL AND t.counterparty_type='CUSTOMER' THEN "
            + "(SELECT " + REFUND_REQUEST_OWNER + " FROM payment_requests refund_request "
            + "WHERE refund_request.id=t.source_id AND refund_request.factory_id=t.factory_id "
            + "AND refund_request.deleted_at IS NULL AND t.sales_order_id=refund_request.sales_order_id "
            + "AND t.counterparty_id=refund_request.customer_id) "
            + "WHEN t.source_type='RETURN_ORDER' THEN "
            + "(SELECT " + RETURN_ORDER_OWNER_RO + " "
            + "FROM return_orders ro "
            + "LEFT JOIN sales_orders so3 ON ro.return_type='SALES_RETURN' "
            + "AND so3.id=ro.source_order_id AND so3.factory_id=ro.factory_id AND so3.deleted_at IS NULL "
            + "LEFT JOIN purchase_orders po3 ON ro.return_type='PURCHASE_RETURN' "
            + "AND po3.id=ro.source_order_id AND po3.factory_id=ro.factory_id AND po3.deleted_at IS NULL "
            + "WHERE ro.id=t.source_id AND ro.factory_id=t.factory_id AND ro.deleted_at IS NULL) "
            + "WHEN t.source_type IS NULL AND t.source_id IS NULL AND t.sales_order_id IS NOT NULL THEN "
            + "(SELECT so.created_by FROM sales_orders so WHERE so.id=t.sales_order_id "
            + "AND so.factory_id=t.factory_id AND so.deleted_at IS NULL) "
            + "WHEN t.source_type IS NULL AND t.source_id IS NULL AND t.purchase_order_id IS NOT NULL THEN "
            + "(SELECT po.created_by FROM purchase_orders po WHERE po.id=t.purchase_order_id "
            + "AND po.factory_id=t.factory_id AND po.deleted_at IS NULL) "
            + "WHEN t.source_type IS NULL AND t.source_id IS NULL THEN t.operated_by "
            + "ELSE NULL END)";

    /**
     * Authoritative RETURN_ORDER owner projection.  The return document creator is not the
     * business owner: ownership follows its SALES_RETURN/PURCHASE_RETURN source order.
     * Callers must join the two order aliases with the same factory and soft-delete predicates.
     */
    public static String returnOrderOwner(String returnAlias, String salesOrderAlias, String purchaseOrderAlias) {
        return returnOrderOwner(returnAlias, salesOrderAlias, purchaseOrderAlias, null);
    }

    /** Same projection with an optional AR/AP alias whose source-order link must also match. */
    public static String returnOrderOwner(String returnAlias, String salesOrderAlias,
                                          String purchaseOrderAlias, String transactionAlias) {
        validateAlias(returnAlias);
        validateAlias(salesOrderAlias);
        validateAlias(purchaseOrderAlias);
        if (transactionAlias != null) validateAlias(transactionAlias);
        String salesLink = transactionAlias == null ? "" : " AND (" + transactionAlias
                + ".sales_order_id IS NULL OR " + transactionAlias + ".sales_order_id="
                + returnAlias + ".source_order_id) AND " + transactionAlias + ".purchase_order_id IS NULL";
        String purchaseLink = transactionAlias == null ? "" : " AND (" + transactionAlias
                + ".purchase_order_id IS NULL OR " + transactionAlias + ".purchase_order_id="
                + returnAlias + ".source_order_id) AND " + transactionAlias + ".sales_order_id IS NULL";
        return "(CASE "
                + "WHEN " + returnAlias + ".return_type='SALES_RETURN' AND " + returnAlias + ".source_order_id IS NOT NULL "
                + salesLink + " "
                + "THEN " + salesOrderAlias + ".created_by "
                + "WHEN " + returnAlias + ".return_type='PURCHASE_RETURN' AND " + returnAlias + ".source_order_id IS NOT NULL "
                + purchaseLink + " "
                + "THEN " + purchaseOrderAlias + ".created_by ELSE NULL END)";
    }

    /** Returns the same owner projection for another validated SQL alias. */
    public static String arApOwner(String alias) {
        validateAlias(alias);
        return AR_AP_OWNER_T.replaceAll("(?<![A-Za-z0-9_])t\\.",
                java.util.regex.Matcher.quoteReplacement(alias + "."));
    }

    private static void validateAlias(String alias) {
        if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid SQL alias");
        }
    }
}
