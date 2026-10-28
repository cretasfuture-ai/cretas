package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The task badge COUNT queries parse and run under a real JPA context (S6-09). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.inventory")
@DisplayName("Task badge count repositories real JPA startup gate")
class TaskBadgeCountRepositoryQueryValidationTest {

    @Autowired SalesOrderRepository salesOrders;
    @Autowired CustomerMaterialArrivalNoticeRepository notices;

    @Test
    void countQueriesRunAndReturnZeroOnAnEmptyTenant() {
        Set<SalesOrderStatus> terminal = Set.of(SalesOrderStatus.COMPLETED, SalesOrderStatus.CANCELLED);
        assertThat(salesOrders.countActionable("F-NONE", terminal)).isZero();
        assertThat(salesOrders.countActionableCreatedBy("F-NONE", 1L, terminal)).isZero();
        assertThat(salesOrders.countActionableCreatedByIn("F-NONE", List.of(1L, 2L), terminal)).isZero();
        assertThat(notices.countByFactoryIdAndStatus("F-NONE", CustomerMaterialArrivalStatus.PENDING_APPROVAL)).isZero();
    }
}
