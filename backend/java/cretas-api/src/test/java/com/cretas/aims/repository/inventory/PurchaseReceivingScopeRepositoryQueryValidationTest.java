package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:receiving_scope_jpa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages="com.cretas.aims.entity")
@EnableJpaRepositories(basePackages="com.cretas.aims.repository")
class PurchaseReceivingScopeRepositoryQueryValidationTest {
    @Autowired PurchaseOrderRepository repository;

    @Test void scopedStatusQueryParsesAndExecutesWithPageAndCount() {
        var page=repository.findByFactoryIdAndStatusAndCreatedByInOrderByCreatedAtDesc(
                "RECEIVING-SCOPE-EMPTY",PurchaseOrderStatus.FINANCE_APPROVED,List.of(11L,22L),PageRequest.of(0,1));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(repository.findByIdAndFactoryIdAndCreatedByIn("missing","RECEIVING-SCOPE-EMPTY",List.of(11L))).isEmpty();
        assertThat(repository.findByFactoryIdAndOrderNumberAndCreatedByIn("RECEIVING-SCOPE-EMPTY","missing",List.of(11L))).isEmpty();
    }
}
