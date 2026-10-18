package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.event.ReturnOrderCreatedEvent;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.inventory.ReturnOrderRowLock;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 JPA/TX 回归：凭证代理抛错后，第一段事务回滚，第二段仍能把 vflag 落成 FAILED。
 *
 * <p>这个测试故意只装配退货单及其行实体，避免启动全量应用上下文；VoucherService
 * 用 Spring 事务代理包住 mock，使 REQUIRED 代理真实地把当前事务标记为 rollback-only。
 */
class ReturnOrderVoucherTransactionContractTest {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "RO-TX-1";

    private LocalContainerEntityManagerFactoryBean entityManagerFactoryBean;
    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DataSource dataSource = newH2DataSource();
        entityManagerFactoryBean = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactoryBean.setDataSource(dataSource);
        entityManagerFactoryBean.setManagedTypes(
                org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                        ReturnOrder.class.getName(),
                        com.cretas.aims.entity.inventory.ReturnOrderItem.class.getName()));
        entityManagerFactoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.setProperty("hibernate.show_sql", "false");
        entityManagerFactoryBean.setJpaProperties(properties);
        entityManagerFactoryBean.afterPropertiesSet();

        entityManagerFactory = entityManagerFactoryBean.getObject();
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        persistSeedOrder();
    }

    @AfterEach
    void tearDown() {
        if (entityManager != null && entityManager.isOpen()) {
            entityManager.close();
        }
        if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
        }
    }

    @Test
    @DisplayName("凭证 REQUIRED 失败后，FAILED 在新的真实事务中持久化")
    void voucherFailurePersistsFailedFlagAfterRollback() {
        ReturnOrderRepository repository = mock(ReturnOrderRepository.class);
        when(repository.findById(ORDER_ID)).thenAnswer(invocation ->
                Optional.ofNullable(entityManager.find(ReturnOrder.class, ORDER_ID)));

        VoucherService rawVoucherService = mock(VoucherService.class, Answers.RETURNS_DEFAULTS);
        when(rawVoucherService.createFromBusiness(FACTORY, "RETURN_ORDER", ORDER_ID))
                .thenThrow(new IllegalStateException("synthetic voucher failure"));
        when(rawVoucherService.findBySourceBusiness("RETURN_ORDER", ORDER_ID))
                .thenReturn(Optional.empty());
        VoucherService transactionalVoucherService = requiredTransactionalProxy(rawVoucherService);

        ReturnOrderVoucherListener listener = new ReturnOrderVoucherListener(
                repository, transactionalVoucherService);
        // This test proves JPA transaction rollback, while PostgreSQL advisory ordering
        // is exercised by the actual loopback API acceptance runner.
        EntityManager lockAwareEm = org.mockito.Mockito.spy(entityManager);
        org.hibernate.query.NativeQuery<?> lockQuery = org.mockito.Mockito.mock(org.hibernate.query.NativeQuery.class, org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.doReturn(lockQuery).when(lockAwareEm).createNativeQuery(
                org.mockito.ArgumentMatchers.startsWith("SELECT pg_advisory_xact_lock"));
        ReflectionTestUtils.setField(listener, "entityManager", lockAwareEm);
        ReflectionTestUtils.setField(listener, "transactionManager", transactionManager);

        listener.onReturnOrderCreated(new ReturnOrderCreatedEvent(
                this, FACTORY, ORDER_ID, "RTN-TX-1", ReturnType.SALES_RETURN.name(),
                "CUSTOMER-TX-1", null, BigDecimal.ONE));

        ReturnOrder reloaded = new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(ReturnOrder.class, ORDER_ID));
        assertEquals(ReturnOrderStatus.DRAFT, reloaded.getStatus(),
                "凭证失败不得改变退货单状态");
        assertEquals(VoucherFlag.FAILED, reloaded.getVflag(),
                "外层失败回滚后，FAILED 必须由第二个事务落库");
    }

    @Test
    @DisplayName("原子 native 行锁后 refresh 读到并发提交的最新 version/vflag")
    void rowLockRefreshClosesFollowOnLockVersionWindow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(txA -> {
            ReturnOrder oldReturn = entityManager.find(ReturnOrder.class, ORDER_ID);
            assertEquals(VoucherFlag.UNCREATED, oldReturn.getVflag());

            TransactionTemplate txB = new TransactionTemplate(transactionManager);
            txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txB.executeWithoutResult(status -> {
                ReturnOrder concurrent = entityManager.find(ReturnOrder.class, ORDER_ID);
                concurrent.setVflag(VoucherFlag.CREATED);
            });

            assertTrue(ReturnOrderRowLock.lockAndRefresh(entityManager, oldReturn, FACTORY));
            assertEquals(VoucherFlag.CREATED, oldReturn.getVflag(),
                    "native FOR UPDATE 后的无锁 refresh 必须读到 TxB 的提交");
            oldReturn.setStatus(ReturnOrderStatus.SUBMITTED);
        });

        ReturnOrder finalOrder = new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(ReturnOrder.class, ORDER_ID));
        assertEquals(ReturnOrderStatus.SUBMITTED, finalOrder.getStatus());
        assertEquals(VoucherFlag.CREATED, finalOrder.getVflag());
    }

    private void persistSeedOrder() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ReturnOrder order = new ReturnOrder();
            order.setId(ORDER_ID);
            order.setFactoryId(FACTORY);
            order.setReturnNumber("RTN-TX-1");
            order.setReturnType(ReturnType.SALES_RETURN);
            order.setStatus(ReturnOrderStatus.DRAFT);
            order.setCounterpartyId("CUSTOMER-TX-1");
            order.setReturnDate(LocalDate.of(2026, 10, 16));
            order.setWithGoods(Boolean.TRUE);
            order.setCreatedBy(1L);
            order.setVflag(VoucherFlag.UNCREATED);
            entityManager.persist(order);
        });
    }

    private DataSource newH2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:return_order_voucher_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private VoucherService requiredTransactionalProxy(VoucherService target) {
        NameMatchTransactionAttributeSource attributes = new NameMatchTransactionAttributeSource();
        RuleBasedTransactionAttribute required = new RuleBasedTransactionAttribute();
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        attributes.addTransactionalMethod("createFromBusiness", required);

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, attributes));
        return (VoucherService) proxyFactory.getProxy();
    }
}
