package com.cretas.aims.service.finance;

/** Database-side voucher ownership CTE.  The owner set is one JSON scalar parameter. */
public final class VoucherOwnershipSql {
    private VoucherOwnershipSql() {}

    private static final String OWNER_VALUES =
            "(SELECT CAST(value AS bigint) FROM jsonb_array_elements_text(CAST(:ownerIdsJson AS jsonb)) AS owner(value))";

    /**
     * Produces the scoped voucher id set without materialising voucher ids in Java.
     * Base rows deliberately exclude reversals; a reversal inherits the owner of its intact original.
     */
    public static final String OWNER_SCOPE_CTE =
            "WITH voucher_owner AS ("
            + "SELECT v.id, v.factory_id, so.created_by AS owner_id FROM vouchers v "
            + "JOIN sales_orders so ON so.id=v.source_business_id AND so.factory_id=v.factory_id AND so.deleted_at IS NULL "
            + "WHERE v.source_business_type='SALES_ORDER' AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, po.created_by FROM vouchers v "
            + "JOIN purchase_orders po ON po.id=v.source_business_id AND po.factory_id=v.factory_id AND po.deleted_at IS NULL "
            + "WHERE v.source_business_type='PURCHASE_ORDER' AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, CASE "
            + "WHEN ro.return_type='SALES_RETURN' AND ro.source_order_id IS NOT NULL THEN so3.created_by "
            + "WHEN ro.return_type='PURCHASE_RETURN' AND ro.source_order_id IS NOT NULL THEN po3.created_by ELSE NULL END "
            + "FROM vouchers v JOIN return_orders ro ON ro.id=v.source_business_id AND ro.factory_id=v.factory_id AND ro.deleted_at IS NULL "
            + "LEFT JOIN sales_orders so3 ON ro.return_type='SALES_RETURN' AND so3.id=ro.source_order_id AND so3.factory_id=ro.factory_id AND so3.deleted_at IS NULL "
            + "LEFT JOIN purchase_orders po3 ON ro.return_type='PURCHASE_RETURN' AND po3.id=ro.source_order_id AND po3.factory_id=ro.factory_id AND po3.deleted_at IS NULL "
            + "WHERE v.source_business_type='RETURN_ORDER' AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, it.requested_by FROM vouchers v "
            + "JOIN internal_transfers it ON it.id=v.source_business_id AND (it.source_factory_id=v.factory_id OR it.target_factory_id=v.factory_id) AND it.deleted_at IS NULL "
            + "WHERE v.source_business_type='INTERNAL_TRANSFER' AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, pp.created_by FROM vouchers v "
            + "JOIN production_plans pp ON pp.id=v.source_business_id AND pp.factory_id=v.factory_id AND pp.deleted_at IS NULL "
            + "WHERE v.source_business_type='PRODUCTION_PLAN' AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, " + FinanceOwnershipSql.AR_AP_OWNER_T + " FROM vouchers v "
            + "JOIN ar_ap_transactions t ON t.id=v.source_business_id AND t.factory_id=v.factory_id AND t.deleted_at IS NULL "
            + "WHERE v.source_business_type IN ('CASH_RECEIPT','CASH_PAYMENT') AND v.deleted_at IS NULL "
            + "UNION ALL SELECT v.id, v.factory_id, v.created_by FROM vouchers v "
            + "WHERE NULLIF(BTRIM(v.source_business_type),'') IS NULL AND NULLIF(BTRIM(v.source_business_id),'') IS NULL AND v.deleted_at IS NULL"
            + "), scoped_vouchers AS ("
            + "SELECT id, factory_id FROM voucher_owner WHERE owner_id IN " + OWNER_VALUES + " "
            + "UNION ALL SELECT reversal.id, reversal.factory_id FROM vouchers reversal "
            + "JOIN voucher_owner original ON original.id=reversal.original_voucher_id AND original.factory_id=reversal.factory_id "
            + "WHERE reversal.source_business_type='VOUCHER_REVERSAL' AND reversal.original_voucher_id IS NOT NULL "
            + "AND reversal.source_business_id=reversal.original_voucher_id AND reversal.deleted_at IS NULL "
            + "AND original.owner_id IN " + OWNER_VALUES + ") ";

    public static String ownerIdsJson(java.util.Collection<Long> owners) {
        if (owners == null || owners.isEmpty()) return "[]";
        return owners.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
