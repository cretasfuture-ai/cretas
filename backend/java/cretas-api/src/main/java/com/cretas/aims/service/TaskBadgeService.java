package com.cretas.aims.service;

import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Real counts for the sidebar task badges (S6-09). Each count is one COUNT query over the caller's
 * authorized rows, factory scoped; it never depends on how many rows a list page returned.
 */
@Service
@RequiredArgsConstructor
public class TaskBadgeService {

    /** Orders that no longer need anyone to act on them. */
    static final Set<SalesOrderStatus> TERMINAL_SALES_STATUSES = Set.of(
            SalesOrderStatus.COMPLETED, SalesOrderStatus.CANCELLED, SalesOrderStatus.SHORT_CLOSED);

    private final SalesOrderRepository salesOrders;
    private final CustomerMaterialArrivalNoticeRepository arrivalNotices;

    @Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /** Same row scope as the sales order list: SELF, SELF_AND_BELOW / DEPT_AND_BELOW chain, otherwise all. */
    @DataScope("created_by")
    @Transactional(readOnly = true)
    public long countActionableSalesOrders(String factoryId) {
        DataScopeContext ctx = DataScopeContext.current();
        if (ctx == null || ctx.getUserId() == null || ctx.getScope() == null) {
            // Only a factory-bypass platform identity (which the data scope aspect lets through without a
            // context) may count the whole factory; anyone else without a scope gets "unknown", never all rows.
            boolean bypass = AuthenticatedActor.fromCurrentRequest().map(AuthenticatedActor::hasFactoryBypass).orElse(false);
            if (!bypass) throw new IllegalStateException("no data scope for this caller; badge count refused");
            return salesOrders.countActionable(factoryId, TERMINAL_SALES_STATUSES);
        }
        com.cretas.aims.entity.enums.DataScope scope = ctx.getScope();
        if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
            return salesOrders.countActionableCreatedBy(factoryId, ctx.getUserId(), TERMINAL_SALES_STATUSES);
        }
        if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
            List<Long> chain = dataScopeResolver != null ? dataScopeResolver.resolveCreatedByChain(ctx) : List.of(ctx.getUserId());
            if (chain == null || chain.isEmpty()) chain = List.of(ctx.getUserId());
            return salesOrders.countActionableCreatedByIn(factoryId, chain, TERMINAL_SALES_STATUSES);
        }
        if (scope == com.cretas.aims.entity.enums.DataScope.ALL) {
            return salesOrders.countActionable(factoryId, TERMINAL_SALES_STATUSES);
        }
        // A scope this count does not know how to narrow by: unknown, not "everything".
        throw new IllegalStateException("unsupported data scope " + scope + " for badge count");
    }

    @Transactional(readOnly = true)
    public long countPendingUnorderedInbound(String factoryId) {
        return arrivalNotices.countByFactoryIdAndStatus(factoryId, CustomerMaterialArrivalStatus.PENDING_APPROVAL);
    }
}
