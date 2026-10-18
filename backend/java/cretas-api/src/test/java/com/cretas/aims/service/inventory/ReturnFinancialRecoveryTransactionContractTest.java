package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Hibernate transactions, row locks and ledger rows; no inventory writer is installed. */
class ReturnFinancialRecoveryTransactionContractTest {
    private LocalContainerEntityManagerFactoryBean factoryBean;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager txManager;
    private ReturnOrderFinancialFollowUpService service;
    private final AtomicInteger postings = new AtomicInteger();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:recovery_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                ReturnOrder.class.getName(), ReturnOrderItem.class.getName(), ArApTransaction.class.getName()));
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var props = new Properties(); props.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        props.setProperty("hibernate.show_sql", "false"); factoryBean.setJpaProperties(props);
        factoryBean.afterPropertiesSet(); factory = factoryBean.getObject();
        txManager = new JpaTransactionManager(factory); em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var returns = mock(ReturnOrderRepository.class);
        when(returns.findById("RO-A")).thenAnswer(inv -> Optional.ofNullable(em.find(ReturnOrder.class, "RO-A")));
        var ledger = mock(ArApTransactionRepository.class);
        when(ledger.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                eq("A"), eq("RETURN_ORDER"), eq(List.of("RO-A")), eq(ArApTransactionType.AR_ADJUSTMENT)))
                .thenAnswer(inv -> em.createQuery("from ArApTransaction t where t.factoryId=:factory and t.sourceType=:source "
                        + "and t.sourceId=:id and t.transactionType=:type", ArApTransaction.class)
                        .setParameter("factory", "A").setParameter("source", "RETURN_ORDER").setParameter("id", "RO-A")
                        .setParameter("type", ArApTransactionType.AR_ADJUSTMENT).getResultList());
        var ar = mock(ArApService.class);
        when(ar.recordAdjustment(eq("A"), eq(CounterpartyType.CUSTOMER), eq("C-A"), any(), anyLong(), anyString(),
                eq("RETURN_ORDER"), eq("RO-A"))).thenAnswer(inv -> {
            postings.incrementAndGet(); var row = new ArApTransaction(); row.setId(UUID.randomUUID().toString());
            row.setFactoryId("A"); row.setTransactionNumber("AR-TEST-"+postings.get());
            row.setTransactionType(ArApTransactionType.AR_ADJUSTMENT); row.setCounterpartyType(CounterpartyType.CUSTOMER);
            row.setCounterpartyId("C-A"); row.setAmount(inv.getArgument(3)); row.setBalanceAfter(new BigDecimal("150"));
            row.setTransactionDate(LocalDate.now()); row.setOperatedBy(inv.getArgument(4));
            row.setSourceType("RETURN_ORDER"); row.setSourceId("RO-A"); row.setApprovalStatus(ArApApprovalStatus.PENDING);
            em.persist(row); em.flush(); return row;
        });
        service = new ReturnOrderFinancialFollowUpService(returns, ledger, mock(VoucherRepository.class), ar);
        ReflectionTestUtils.setField(service, "entityManager", em);
        tx().executeWithoutResult(status -> {
            var order = new ReturnOrder(); order.setId("RO-A"); order.setFactoryId("A"); order.setReturnNumber("RT-A");
            order.setReturnType(ReturnType.SALES_RETURN); order.setStatus(ReturnOrderStatus.COMPLETED);
            order.setCounterpartyId("C-A"); order.setTotalAmount(new BigDecimal("150")); order.setWithGoods(true);
            order.setReturnDate(LocalDate.now()); order.setCreatedBy(7L); em.persist(order);
        });
    }

    @AfterEach void close() { if (factoryBean != null) factoryBean.destroy(); }
    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    @Test void staleParentVersionIsRefreshedBeforeRecoveryAndOnlyOnePendingIsCreated() {
        tx().executeWithoutResult(a -> {
            var old = em.find(ReturnOrder.class, "RO-A"); long before = old.getVersion();
            var other = tx(); other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(b -> em.find(ReturnOrder.class, "RO-A").setVflag(VoucherFlag.CREATED));
            var pending = service.resubmit("A", "RO-A", 7L, "已核对缺失调整");
            assertThat(old.getVersion()).isGreaterThan(before); assertThat(old.getVflag()).isEqualTo(VoucherFlag.CREATED);
            assertThat(service.resubmit("A", "RO-A", 7L, "重复重提").getId()).isEqualTo(pending.getId());
        });
        assertThat(postings).hasValue(1);
    }

    @Test void concurrentRecoveryTransactionsReuseTheSameCommittedPendingRow() throws Exception {
        var start = new CountDownLatch(2); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> action = () -> tx().execute(status -> {
                em.find(ReturnOrder.class, "RO-A"); start.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return service.resubmit("A", "RO-A", 7L, "并发核对").getId();
            });
            var first = pool.submit(action); var second = pool.submit(action);
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(postings).hasValue(1);
            Long count = tx().execute(status -> em.createQuery("select count(t) from ArApTransaction t", Long.class).getSingleResult());
            assertThat(count).isEqualTo(1L);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
