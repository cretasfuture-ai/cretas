package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.security.BusinessRowScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReceivingTaskVisibilityContractTest {
    private final BusinessRowScope scope = mock(BusinessRowScope.class);
    private ReceivingTaskVisibility service;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:receiving-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        service = new ReceivingTaskVisibility(scope, new NamedParameterJdbcTemplate(ds));
        for (String table : List.of("purchase_orders", "sales_orders", "customer_material_arrival_notices")) {
            jdbc.execute("CREATE TABLE " + table + " (id VARCHAR(64), factory_id VARCHAR(64), created_by BIGINT, deleted_at TIMESTAMP)");
            jdbc.update("INSERT INTO " + table + " VALUES ('own','F',11,NULL),('below','F',22,NULL),('hidden','F',33,NULL),"
                    + "('foreign','OTHER',11,NULL),('deleted','F',11,CURRENT_TIMESTAMP),('unknown','F',NULL,NULL)");
        }
    }

    @Test void eachSourceUsesItsRealDocumentCreatorAndFactoryAndExcludesDeletedOrUnknown() {
        when(scope.recordCreatorIds("F")).thenReturn(List.of(11L));
        for (String source : List.of("PURCHASE", "SALES_ORDER_CUSTOMER_SUPPLIED", "CUSTOMER_MATERIAL_ARRIVAL")) {
            var tasks = List.of("own", "below", "hidden", "foreign", "deleted", "unknown", "missing").stream()
                    .map(id -> task(source,id)).toList();
            assertThat(service.filter("F", tasks)).as(source)
                    .extracting(PurchaseReceivingTaskResponse::getSourceId).containsExactly("own");
        }
    }

    @Test void resolvedSubordinateOrDepartmentOwnersAreIncludedAndUnsupportedSourcesFailClosed() {
        when(scope.recordCreatorIds("F")).thenReturn(List.of(11L,22L));
        assertThat(service.filter("F", List.of(task("PURCHASE","own"),task("PURCHASE","below"),
                task("PURCHASE","hidden"),task("UNKNOWN","own"))))
                .extracting(PurchaseReceivingTaskResponse::getSourceId).containsExactly("own","below");
    }

    @Test void emptyScopeReturnsNoTasksAndAllRetainsExistingTasks() {
        var tasks=List.of(task("PURCHASE","own"));
        when(scope.recordCreatorIds("F")).thenReturn(List.of());
        assertThat(service.filter("F",tasks)).isEmpty();
        when(scope.recordCreatorIds("F")).thenReturn(null);
        assertThat(service.filter("F",tasks)).isSameAs(tasks);
    }

    private PurchaseReceivingTaskResponse task(String source,String id) {
        return PurchaseReceivingTaskResponse.builder().sourceType(source).sourceId(id).taskId(id).build();
    }
}
