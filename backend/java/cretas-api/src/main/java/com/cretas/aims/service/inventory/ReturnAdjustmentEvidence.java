package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.finance.ArApTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable validation view for the source return's approved AR adjustment. */
public record ReturnAdjustmentEvidence(String state, BigDecimal expectedAmount,
                                       List<ArApTransaction> rows) {

    public static String state(ReturnOrder order, List<ArApTransaction> rows) {
        if (order == null || order.getTotalAmount() == null || order.getTotalAmount().signum() < 0) {
            return "INCONSISTENT";
        }
        if (order.getTotalAmount().signum() == 0 && (rows == null || rows.isEmpty())) {
            return "NOT_REQUIRED";
        }
        long pending = 0;
        long approved = 0;
        for (ArApTransaction row : rows == null ? List.<ArApTransaction>of() : rows) {
            if (!Objects.equals(order.getFactoryId(), row.getFactoryId())
                    || !"RETURN_ORDER".equals(row.getSourceType())
                    || !Objects.equals(order.getId(), row.getSourceId())
                    || row.getTransactionType() != ArApTransactionType.AR_ADJUSTMENT
                    || row.getCounterpartyType() != CounterpartyType.CUSTOMER
                    || !Objects.equals(order.getCounterpartyId(), row.getCounterpartyId())
                    || row.getAmount() == null
                    || row.getAmount().compareTo(order.getReceivableAmount().negate()) != 0
                    || row.getApprovalStatus() == null) {
                return "INCONSISTENT";
            }
            if (row.getApprovalStatus() == ArApApprovalStatus.PENDING) pending++;
            if (row.getApprovalStatus() == ArApApprovalStatus.APPROVED) approved++;
        }
        if (approved > 1 || pending > 1 || (approved > 0 && pending > 0)) return "INCONSISTENT";
        if (approved == 1) return "APPROVED";
        if (pending == 1) return "PENDING";
        if (rows != null && !rows.isEmpty()) return "REJECTED";
        return order.getStatus() == ReturnOrderStatus.COMPLETED ? "MISSING" : "NOT_DUE";
    }

    public static ReturnAdjustmentEvidence of(ReturnOrder order, List<ArApTransaction> rows) {
        return new ReturnAdjustmentEvidence(state(order, rows),
                order == null || order.getTotalAmount() == null ? null : order.getReceivableAmount().negate(),
                rows == null ? List.of() : List.copyOf(rows));
    }
}
