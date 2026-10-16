package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.enums.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository.inventory")
class CustomerDocumentScopeRepositoryQueryValidationTest {
    @Autowired EntityManager em;
    @Autowired SalesOrderRepository sales;
    @Autowired ReturnOrderRepository returns;
    Long self, other;

    @BeforeEach void fixture() {
        Factory f=new Factory(); f.setId("F-PERM-JPA"); f.setName("Scope query gate"); em.persist(f);
        self=user("self"); other=user("other");
        Customer c=new Customer(); c.setId("C-PERM-JPA"); c.setFactoryId(f.getId());
        c.setCode("C-PERM-JPA"); c.setCustomerCode("C-PERM-JPA"); c.setName("Scope query gate");
        c.setCreatedBy(self); em.persist(c); em.flush();
    }
    Long user(String name) {
        User u=new User(); u.setFactoryId("F-PERM-JPA"); u.setUsername("perm-jpa-"+name);
        u.setPasswordHash("unused"); u.setIsActive(true); em.persist(u); em.flush(); return u.getId();
    }
    @Test void customerSalesFiltersOwnersBeforePaginationAndCount() {
        for(int i=0;i<3;i++) {
            SalesOrder o=new SalesOrder(); o.setId("scope-so-"+i); o.setOrderNumber(o.getId());
            o.setFactoryId("F-PERM-JPA"); o.setCustomerId("C-PERM-JPA");
            o.setOrderDate(LocalDate.now()); o.setCreatedBy(i==2?other:self); em.persist(o);
        }
        em.flush(); em.clear();
        var p=sales.findByFactoryIdAndCustomerIdAndCreatedByInOrderByCreatedAtDesc(
                "F-PERM-JPA","C-PERM-JPA",List.of(self),PageRequest.of(0,1));
        assertThat(p.getTotalElements()).isEqualTo(2);
        assertThat(p.getContent()).extracting(SalesOrder::getCreatedBy).containsExactly(self);
        assertThat(sales.findByFactoryIdAndCustomerIdAndCreatedByInOrderByCreatedAtDesc(
                "FOREIGN","C-PERM-JPA",List.of(self),PageRequest.of(0,10))).isEmpty();
        assertThat(sales.findByFactoryIdAndCustomerIdAndCreatedByInOrderByCreatedAtDesc(
                "F-PERM-JPA","FOREIGN",List.of(self),PageRequest.of(0,10))).isEmpty();
    }
    @Test void customerReturnsFilterOwnerTypeCustomerAndFactory() {
        for(int i=0;i<3;i++) {
            ReturnOrder r=new ReturnOrder(); r.setId("scope-ro-"+i); r.setReturnNumber(r.getId());
            r.setFactoryId("F-PERM-JPA"); r.setCounterpartyId("C-PERM-JPA");
            r.setReturnDate(LocalDate.now()); r.setReturnType(i==2?ReturnType.PURCHASE_RETURN:ReturnType.SALES_RETURN);
            r.setCreatedBy(i==1?other:self); em.persist(r);
        }
        em.flush(); em.clear();
        var p=returns.findByFactoryIdAndCounterpartyIdAndReturnTypeAndCreatedByInOrderByCreatedAtDesc(
                "F-PERM-JPA","C-PERM-JPA",ReturnType.SALES_RETURN,List.of(self),PageRequest.of(0,1));
        assertThat(p.getTotalElements()).isEqualTo(1);
        assertThat(p.getContent()).extracting(ReturnOrder::getId).containsExactly("scope-ro-0");
        assertThat(returns.findByFactoryIdAndCounterpartyIdAndReturnTypeAndCreatedByInOrderByCreatedAtDesc(
                "FOREIGN","C-PERM-JPA",ReturnType.SALES_RETURN,List.of(self),PageRequest.of(0,10))).isEmpty();
        assertThat(returns.findByFactoryIdAndCounterpartyIdAndReturnTypeAndCreatedByInOrderByCreatedAtDesc(
                "F-PERM-JPA","FOREIGN",ReturnType.SALES_RETURN,List.of(self),PageRequest.of(0,10))).isEmpty();
    }
}
