package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceivingTaskResponse;
import com.cretas.aims.security.BusinessRowScope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Task badges and the receiving panel share source-document ownership, never the warehouse operator. */
@Service
@RequiredArgsConstructor
public class ReceivingTaskVisibility {
    private final BusinessRowScope rowScope;
    private final NamedParameterJdbcTemplate jdbc;

    public List<PurchaseReceivingTaskResponse> filter(String factoryId, List<PurchaseReceivingTaskResponse> tasks) {
        List<Long> creators = rowScope.recordCreatorIds(factoryId);
        if (creators == null) return tasks;
        if (creators.isEmpty() || tasks.isEmpty()) return List.of();
        Set<String> purchases = visible(factoryId, creators, tasks, "PURCHASE", "purchase_orders");
        Set<String> supplied = visible(factoryId, creators, tasks,
                CustomerSuppliedMaterialReceivingTaskResponse.SOURCE, "sales_orders");
        Set<String> arrivals = visible(factoryId, creators, tasks,
                CustomerMaterialArrivalNoticeService.SOURCE_TYPE, "customer_material_arrival_notices");
        return tasks.stream().filter(task -> {
            if ("PURCHASE".equals(task.getSourceType())) return purchases.contains(task.getSourceId());
            if (CustomerSuppliedMaterialReceivingTaskResponse.SOURCE.equals(task.getSourceType()))
                return supplied.contains(task.getSourceId());
            if (CustomerMaterialArrivalNoticeService.SOURCE_TYPE.equals(task.getSourceType()))
                return arrivals.contains(task.getSourceId());
            return false;
        }).toList();
    }

    private Set<String> visible(String factoryId, List<Long> creators, List<PurchaseReceivingTaskResponse> tasks,
                                String source, String table) {
        List<String> ids = tasks.stream().filter(task -> source.equals(task.getSourceType()))
                .map(PurchaseReceivingTaskResponse::getSourceId).filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        var parameters = new MapSqlParameterSource().addValue("factory", factoryId)
                .addValue("creators", creators).addValue("ids", ids);
        return new HashSet<>(jdbc.queryForList("SELECT id FROM " + table
                + " WHERE factory_id=:factory AND created_by IN (:creators) AND id IN (:ids) AND deleted_at IS NULL",
                parameters, String.class));
    }
}
