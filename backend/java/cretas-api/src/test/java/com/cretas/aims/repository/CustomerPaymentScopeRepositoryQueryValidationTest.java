package com.cretas.aims.repository;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses=PaymentRecordRepository.class,
        includeFilters=@Filter(type=FilterType.ASSIGNABLE_TYPE,classes=PaymentRecordRepository.class))
class CustomerPaymentScopeRepositoryQueryValidationTest {
    @Autowired EntityManager em;
    @Autowired PaymentRecordRepository invoices;
    Long self,other;
    private Long user(String name) {
        var u=new User();u.setFactoryId("F1");u.setUsername(name);u.setPasswordHash("unused");u.setIsActive(true);
        em.persist(u);em.flush();return u.getId();
    }
    private void order(String id,String factory,Long owner,boolean deleted) {
        var o=new SalesOrder();o.setId(id);o.setOrderNumber(id);o.setFactoryId(factory);o.setCustomerId("C1");
        o.setOrderDate(LocalDate.now());o.setCreatedBy(owner);if(deleted)o.setDeletedAt(LocalDateTime.now());em.persist(o);
    }
    private void invoice(int number,String factory,String customer,String order,Long requester,boolean deleted) {
        var i=new PaymentRecord();i.setId("I"+number);i.setPaymentNumber("I"+number);i.setFactoryId(factory);
        i.setCustomerId(customer);i.setSalesOrderId(order);i.setRecordedBy(requester);i.setStatus(PaymentRecordStatus.PENDING);
        i.setAmount(BigDecimal.valueOf(62));
        if(deleted)i.setDeletedAt(LocalDateTime.now());em.persist(i);em.flush();
        em.createNativeQuery("UPDATE payment_records SET created_at=:time WHERE id=:id")
                .setParameter("time",LocalDateTime.of(2026,10,16,0,0).plusSeconds(number))
                .setParameter("id",i.getId()).executeUpdate();
    }
    @Test void joinsSameFactorySourceOrderOwnerBeforePagingAndCounting() {
        for(String id:List.of("F1","F2")){var f=new Factory();f.setId(id);f.setName(id);em.persist(f);}
        self=user("payment-self");other=user("payment-other");
        for(String id:List.of("C1","C2")){var c=new Customer();c.setId(id);c.setFactoryId("F1");c.setName(id);c.setCode(id);c.setCustomerCode(id);c.setCreatedBy(self);em.persist(c);}
        order("OWN","F1",self,false);order("OTHER","F1",other,false);order("FOREIGN","F2",self,false);
        order("DELETED","F1",self,true);order("UNRELATED","F1",other,false);
        invoice(1,"F1","C1","OWN",null,false); // Legacy recorder can be unknown.
        invoice(2,"F1","C1","OWN",other,false);
        invoice(3,"F1","C1","OTHER",self,false); // Recorder cannot confer ownership.
        invoice(4,"F1","C1","FOREIGN",self,false); // Same ID reference, wrong source factory.
        invoice(5,"F1","C1","DELETED",self,false);
        invoice(6,"F1","C1","MISSING",self,false);
        invoice(7,"F1","C1","OWN",self,true);
        invoice(8,"F1","C2","OWN",self,false);
        invoice(9,"F2","C1","FOREIGN",self,false);
        invoice(10,"F1","C1","UNRELATED",self,false);
        em.flush();em.clear();
        var p=invoices.findCustomerPaymentsInOrderScope("F1","C1",List.of(self),PageRequest.of(0,1));
        assertThat(p.getTotalElements()).isEqualTo(2);assertThat(p.getTotalPages()).isEqualTo(2);
        assertThat(p).extracting(PaymentRecord::getId).containsExactly("I2");
        assertThat(invoices.findCustomerPaymentsInOrderScope("F1","C1",List.of(self),PageRequest.of(1,1)))
                .extracting(PaymentRecord::getId).containsExactly("I1");
        assertThat(invoices.findCustomerPaymentsInOrderScope("F1","C1",List.of(self,other),PageRequest.of(0,20)))
                .extracting(PaymentRecord::getId).containsExactly("I10","I3","I2","I1");
        assertThat(invoices.findCustomerPaymentsInOrderScope("F1","C1",List.of(),PageRequest.of(0,20))).isEmpty();
        assertThat(invoices.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",PageRequest.of(0,20)).getTotalElements()).isEqualTo(7);
    }
}
