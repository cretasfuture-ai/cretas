package com.cretas.aims.service.intent.catalog;

import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Factory-scoped sales ranking facade used by the AI tool. */
@Service
public class ProductSalesRankingService {
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final DataScopeResolver dataScopeResolver;

    public ProductSalesRankingService(SalesOrderItemRepository salesOrderItemRepository,
                                      DataScopeResolver dataScopeResolver) {
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.dataScopeResolver = dataScopeResolver;
    }

    @com.cretas.aims.annotation.DataScope("created_by")
    public List<SalesOrderItemRepository.SalesRankingRow> rank(String factoryId,
                                                               LocalDate startDate,
                                                               LocalDate endDate) {
        var actor = AuthenticatedActor.fromCurrentExecution().orElseThrow(
                () -> new BusinessException(401, "缺少销售排名数据访问身份"));
        if (!actor.hasFactoryBypass() && (actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId()))) {
            throw new BusinessException(403, "销售排名工厂与当前身份不一致");
        }
        DataScopeContext scope = null;
        if (!actor.hasFactoryBypass()) {
            scope = DataScopeContext.current();
            if (scope == null) scope = dataScopeResolver.resolve(actor.id());
            if (scope == null || !actor.id().equals(scope.getUserId())
                    || !factoryId.equals(scope.getFactoryId()) || scope.getScope() == null) {
                throw new BusinessException(403, "无法解析销售排名数据范围");
            }
            // CUSTOM has no supported creator predicate here; never widen it to ALL.
            if (scope.getScope() == DataScope.CUSTOM) return List.of();
        }
        boolean filtered = scope != null && scope.getScope() != DataScope.ALL;
        List<Long> owners = List.of(-1L);
        if (filtered) {
            owners = scope.getScope() == DataScope.SELF
                    ? List.of(scope.getUserId())
                    : dataScopeResolver.resolveCreatedByChain(scope);
            if (owners == null || owners.isEmpty()) owners = List.of(scope.getUserId());
        }
        var buckets = salesOrderItemRepository.rankSalesByProduct(factoryId, startDate, endDate,
                OrderUsageWhitelists.SO_ALL, filtered, owners);
        Map<String, java.util.List<SalesOrderItemRepository.SalesRankingRow>> products = new java.util.LinkedHashMap<>();
        for (var row : buckets) products.computeIfAbsent(row.getProductTypeId(), ignored -> new java.util.ArrayList<>()).add(row);
        java.util.List<SalesOrderItemRepository.SalesRankingRow> result = new java.util.ArrayList<>();
        products.forEach((id, rows) -> {
            Map<String, java.math.BigDecimal> quantities = new java.util.LinkedHashMap<>();
            java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
            for (var row : rows) {
                quantities.merge(row.getUnit() == null ? "UNKNOWN" : row.getUnit(), row.getQuantity(), java.math.BigDecimal::add);
                amount = amount.add(row.getAmount());
            }
            var total = amount;
            var units = java.util.Collections.unmodifiableMap(quantities);
            result.add(new SalesOrderItemRepository.SalesRankingRow() {
                public String getProductTypeId() { return id; }
                public String getProductName() { return rows.get(0).getProductName(); }
                public java.math.BigDecimal getAmount() { return total; }
                public String getUnit() { return units.size() == 1 ? units.keySet().iterator().next() : null; }
                public java.math.BigDecimal getQuantity() { return units.size() == 1 ? units.values().iterator().next() : null; }
                public Map<String, java.math.BigDecimal> getQuantitiesByUnit() { return units; }
            });
        });
        result.sort(java.util.Comparator.comparing(SalesOrderItemRepository.SalesRankingRow::getAmount).reversed()
                .thenComparing(SalesOrderItemRepository.SalesRankingRow::getProductTypeId));
        return result;
    }
}
