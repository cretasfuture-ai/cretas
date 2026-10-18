package com.cretas.aims.service.finance;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.BusinessRowScope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Resolves the authoritative owner of voucher source rows before paging or aggregation. */
@Component
@RequiredArgsConstructor
public class VoucherRowScopeQuery {
    private static final String EMPTY_ID = "__NO_VISIBLE_VOUCHER__";
    private final JdbcTemplate jdbc;

    public void requireVoucherAccess(String factoryId, String voucherId, List<Long> creators) {
        if (creators == null) return;
        if (!isVoucherVisible(factoryId, voucherId, creators))
            throw new BusinessException(403, "该凭证不在当前账号的数据范围内");
    }

    /** Scalar detail/write check; it must not materialize the entire factory ledger. */
    public boolean isVoucherVisible(String factoryId, String voucherId, List<Long> creators) {
        if (creators == null) return true;
        if (creators.isEmpty()) return false;
        String ownerIn = "(" + String.join(",", java.util.Collections.nCopies(creators.size(), "?")) + ")";
        String sql = "SELECT EXISTS (SELECT 1 FROM vouchers v WHERE v.id=? AND v.factory_id=? AND v.deleted_at IS NULL AND ("
                + ownerPredicate("v", ownerIn)
                + " OR (v.source_business_type='VOUCHER_REVERSAL' AND v.original_voucher_id IS NOT NULL "
                + "AND v.source_business_id=v.original_voucher_id AND EXISTS (SELECT 1 FROM vouchers original "
                + "WHERE original.id=v.original_voucher_id AND original.factory_id=v.factory_id "
                + "AND original.deleted_at IS NULL AND " + ownerPredicate("original", ownerIn) + "))))";
        List<Object> args = new ArrayList<>();
        args.add(voucherId);
        args.add(factoryId);
        for (int i = 0; i < 14; i++) args.addAll(creators);
        Boolean visible = jdbc.queryForObject(sql, Boolean.class, args.toArray());
        return Boolean.TRUE.equals(visible);
    }

    /** Validates a source before a generated voucher can be created in a SELF scope. */
    public void requireSourceAccess(String factoryId, String sourceType, String sourceId, List<Long> creators) {
        if (creators == null) return;
        if (sourceType == null || sourceId == null || sourceType.isBlank() || sourceId.isBlank())
            throw new BusinessException(403, "凭证来源不完整，访问已停止");
        String sql;
        if ("SALES_ORDER".equals(sourceType)) sql = "SELECT created_by FROM sales_orders WHERE id=? AND factory_id=? AND deleted_at IS NULL";
        else if ("PURCHASE_ORDER".equals(sourceType)) sql = "SELECT created_by FROM purchase_orders WHERE id=? AND factory_id=? AND deleted_at IS NULL";
        else if ("RETURN_ORDER".equals(sourceType)) sql = "SELECT "
                + FinanceOwnershipSql.returnOrderOwner("ro", "so3", "po3")
                + " FROM return_orders ro "
                + "LEFT JOIN sales_orders so3 ON ro.return_type='SALES_RETURN' AND so3.id=ro.source_order_id "
                + "AND so3.factory_id=ro.factory_id AND so3.deleted_at IS NULL "
                + "LEFT JOIN purchase_orders po3 ON ro.return_type='PURCHASE_RETURN' AND po3.id=ro.source_order_id "
                + "AND po3.factory_id=ro.factory_id AND po3.deleted_at IS NULL "
                + "WHERE ro.id=? AND ro.factory_id=? AND ro.deleted_at IS NULL";
        else if ("PRODUCTION_PLAN".equals(sourceType)) sql = "SELECT created_by FROM production_plans WHERE id=? AND factory_id=? AND deleted_at IS NULL";
        else if ("INTERNAL_TRANSFER".equals(sourceType)) sql = "SELECT requested_by FROM internal_transfers WHERE id=? AND (source_factory_id=? OR target_factory_id=?) AND deleted_at IS NULL";
        else if ("CASH_RECEIPT".equals(sourceType) || "CASH_PAYMENT".equals(sourceType)) {
            sql = "SELECT " + FinanceOwnershipSql.arApOwner("t") + " FROM ar_ap_transactions t WHERE t.id=? AND t.factory_id=? AND t.deleted_at IS NULL";
        } else {
            throw new BusinessException(403, "该凭证来源不支持当前数据范围");
        }
        List<Object> args = new ArrayList<>();
        args.add(sourceId); args.add(factoryId);
        if ("INTERNAL_TRANSFER".equals(sourceType)) args.add(factoryId);
        List<Long> owners = jdbc.query(sql, (rs, rowNum) -> rs.getObject(1, Long.class), args.toArray());
        if (owners.size() != 1 || owners.get(0) == null || !creators.contains(owners.get(0)))
            throw new BusinessException(403, "凭证来源不在当前账号的数据范围内");
    }

    public List<String> normalize(List<String> ids) {
        return ids == null || ids.isEmpty() ? List.of(EMPTY_ID) : ids;
    }

    private String ownerPredicate(String voucherAlias, String ownerIn) {
        return "("
                + "(" + voucherAlias + ".source_business_type='SALES_ORDER' AND EXISTS (SELECT 1 FROM sales_orders so WHERE so.id=" + voucherAlias + ".source_business_id AND so.factory_id=" + voucherAlias + ".factory_id AND so.deleted_at IS NULL AND so.created_by IN " + ownerIn + "))"
                + " OR (" + voucherAlias + ".source_business_type='PURCHASE_ORDER' AND EXISTS (SELECT 1 FROM purchase_orders po WHERE po.id=" + voucherAlias + ".source_business_id AND po.factory_id=" + voucherAlias + ".factory_id AND po.deleted_at IS NULL AND po.created_by IN " + ownerIn + "))"
                + " OR (" + voucherAlias + ".source_business_type='RETURN_ORDER' AND EXISTS (SELECT 1 FROM return_orders ro "
                + "LEFT JOIN sales_orders so3 ON ro.return_type='SALES_RETURN' AND so3.id=ro.source_order_id AND so3.factory_id=ro.factory_id AND so3.deleted_at IS NULL "
                + "LEFT JOIN purchase_orders po3 ON ro.return_type='PURCHASE_RETURN' AND po3.id=ro.source_order_id AND po3.factory_id=ro.factory_id AND po3.deleted_at IS NULL "
                + "WHERE ro.id=" + voucherAlias + ".source_business_id AND ro.factory_id=" + voucherAlias + ".factory_id AND ro.deleted_at IS NULL AND "
                + FinanceOwnershipSql.returnOrderOwner("ro", "so3", "po3") + " IN " + ownerIn + "))"
                + " OR (" + voucherAlias + ".source_business_type='INTERNAL_TRANSFER' AND EXISTS (SELECT 1 FROM internal_transfers it WHERE it.id=" + voucherAlias + ".source_business_id AND (it.source_factory_id=" + voucherAlias + ".factory_id OR it.target_factory_id=" + voucherAlias + ".factory_id) AND it.deleted_at IS NULL AND it.requested_by IN " + ownerIn + "))"
                + " OR (" + voucherAlias + ".source_business_type='PRODUCTION_PLAN' AND EXISTS (SELECT 1 FROM production_plans pp WHERE pp.id=" + voucherAlias + ".source_business_id AND pp.factory_id=" + voucherAlias + ".factory_id AND pp.deleted_at IS NULL AND pp.created_by IN " + ownerIn + "))"
                + " OR (" + voucherAlias + ".source_business_type IN ('CASH_RECEIPT','CASH_PAYMENT') AND EXISTS (SELECT 1 FROM ar_ap_transactions t WHERE t.id=" + voucherAlias + ".source_business_id AND t.factory_id=" + voucherAlias + ".factory_id AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.arApOwner("t") + " IN " + ownerIn + "))"
                + " OR (NULLIF(BTRIM(" + voucherAlias + ".source_business_type),'') IS NULL AND NULLIF(BTRIM(" + voucherAlias + ".source_business_id),'') IS NULL AND " + voucherAlias + ".created_by IN " + ownerIn + ")"
                + ")";
    }
}
