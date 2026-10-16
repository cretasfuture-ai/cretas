package com.cretas.aims.integration;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.inventory.impl.PurchaseExceptionServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real service calls and real PostgreSQL row locks for RETURN_OVER decisions.
 * Only a disposable schema is created; the application/UI database is never used.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PurchaseExceptionConcurrencyPostgresContractTest {
    private static final String FACTORY = "EXCEPTION-CONCURRENCY-F1";
    private static final String EXCEPTION_ID = "EXC-PG-1";
    private static final String PO_ID = "PO-PG-1";
    private static final String MATERIAL_ID = "MAT-PG-1";

    @Mock PurchaseExceptionRepository exceptionRepository;
    @Mock ReturnOrderService returnOrderService;
    @Mock PurchaseOrderItemRepository itemRepository;
    @Mock PurchaseOrderRepository orderRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;

    private static DataSource adminDataSource;
    private static DataSource targetDataSource;
    private static JdbcTemplate jdbc;
    private static String schema;
    private static TransactionTemplate transactionTemplate;
    private PurchaseExceptionServiceImpl service;
    private PurchaseExceptionServiceImpl decisionProxy;
    private final AtomicInteger saveCalls = new AtomicInteger();
    private final AtomicInteger childInsertCount = new AtomicInteger();
    private volatile String createdReturnOrderId;
    private volatile FailureMode failureMode = FailureMode.NONE;

    private enum FailureMode { NONE, CHILD, ASSOCIATION }

    @BeforeAll
    static void createDisposableSchema() {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        String user = required("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        adminDataSource = new DriverManagerDataSource(url, user, password);
        schema = "exception_verify_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA \"" + schema + "\"");
        String targetUrl = withSchema(url, schema);
        targetDataSource = new DriverManagerDataSource(targetUrl, user, password);
        jdbc = new JdbcTemplate(targetDataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(targetDataSource));
        jdbc.execute("CREATE TABLE purchase_exceptions ("
                + "id varchar(191) primary key, factory_id varchar(191) not null, exception_number varchar(50) not null,"
                + "receive_record_id varchar(191) not null, purchase_order_id varchar(191) not null, supplier_id varchar(191),"
                + "material_type_id varchar(191), material_name varchar(200), exception_type varchar(32) not null,"
                + "po_quantity numeric(15,4) not null, received_quantity numeric(15,4) not null, exception_qty numeric(15,4) not null,"
                + "unit varchar(20), decision varchar(32), decision_by bigint, decision_at timestamp, decision_notes text,"
                + "status varchar(32) not null, created_by bigint not null, owner_user_id bigint, owner_name varchar(100),"
                + "return_order_id varchar(191), version bigint not null, deleted_at timestamp)");
        jdbc.execute("CREATE TABLE return_orders (id varchar(191) primary key)");
    }

    @AfterAll
    static void dropDisposableSchema() {
        if (adminDataSource != null && schema != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    @BeforeEach
    void reset() {
        failureMode = FailureMode.NONE;
        saveCalls.set(0);
        childInsertCount.set(0);
        createdReturnOrderId = null;
        jdbc.update("DELETE FROM return_orders");
        jdbc.update("DELETE FROM purchase_exceptions");
        jdbc.update("INSERT INTO purchase_exceptions (id,factory_id,exception_number,receive_record_id,purchase_order_id,"
                + "supplier_id,material_type_id,material_name,exception_type,po_quantity,received_quantity,exception_qty,unit,"
                + "status,created_by,owner_user_id,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                EXCEPTION_ID, FACTORY, "EX-PG-1", "REC-PG-1", PO_ID, "SUP-PG-1", MATERIAL_ID, "material",
                "OVER_RECEIVE", 10, 12, 2, "kg", "PENDING", 7, 7, 0);
        configureRepository();
        configureSource();
        service = new PurchaseExceptionServiceImpl(exceptionRepository, returnOrderService,
                itemRepository, orderRepository);
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(targetDataSource),
                new AnnotationTransactionAttributeSource()));
        decisionProxy = (PurchaseExceptionServiceImpl) proxyFactory.getProxy();
        ReflectionTestUtils.setField(service, "self", decisionProxy);
    }

    @Test
    void concurrentDifferentActorsProduceOneResolvedExceptionAndOneReturn() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> first = pool.submit(() -> decide(start, 101L));
        Future<Boolean> second = pool.submit(() -> decide(start, 202L));
        start.countDown();
        boolean firstSuccess = first.get(20, TimeUnit.SECONDS);
        boolean secondSuccess = second.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertNotEquals(firstSuccess, secondSuccess, "pessimistic lock must allow exactly one decision");
        assertEquals(1, jdbc.queryForObject("select count(*) from return_orders", Integer.class));
        assertEquals("RESOLVED", jdbc.queryForObject("select status from purchase_exceptions where id=?", String.class, EXCEPTION_ID));
        assertEquals(createdReturnOrderId,
                jdbc.queryForObject("select return_order_id from purchase_exceptions where id=?", String.class, EXCEPTION_ID),
                "exception must reference the exact return row created by the child operation");
    }

    @Test
    void childFailureRollsBackDecisionAndReturnInsert() {
        failureMode = FailureMode.CHILD;
        RuntimeException failure = assertThrows(RuntimeException.class, () -> decisionProxy.decideException(
                EXCEPTION_ID, FACTORY, ExceptionDecision.RETURN_OVER, "child failure", 7L, null));
        assertEquals("child write failed", failure.getMessage());
        assertEquals(1, childInsertCount.get(), "failure must occur after the child write, not at authorization");

        assertEquals(0, jdbc.queryForObject("select count(*) from return_orders", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("select status from purchase_exceptions where id=?", String.class, EXCEPTION_ID));
        assertNull(jdbc.queryForObject("select return_order_id from purchase_exceptions where id=?", String.class, EXCEPTION_ID));
    }

    @Test
    void associationFailureRollsBackAlreadyCreatedReturn() {
        failureMode = FailureMode.ASSOCIATION;
        RuntimeException failure = assertThrows(RuntimeException.class, () -> decisionProxy.decideException(
                EXCEPTION_ID, FACTORY, ExceptionDecision.RETURN_OVER, "association failure", 7L, null));
        assertEquals("association persistence failed", failure.getMessage());
        assertEquals(1, childInsertCount.get(), "association failure must occur after the child write");

        assertEquals(0, jdbc.queryForObject("select count(*) from return_orders", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("select status from purchase_exceptions where id=?", String.class, EXCEPTION_ID));
        assertNull(jdbc.queryForObject("select return_order_id from purchase_exceptions where id=?", String.class, EXCEPTION_ID));
    }

    private boolean decide(CountDownLatch start, long actor) throws Exception {
        start.await(20, TimeUnit.SECONDS);
        try {
            decisionProxy.decideException(EXCEPTION_ID, FACTORY, ExceptionDecision.RETURN_OVER,
                    "concurrent decision", actor, "procurement_manager");
            return true;
        } catch (BusinessException expectedConflict) {
            if (!Integer.valueOf(409).equals(expectedConflict.getCode())) {
                throw expectedConflict;
            }
            return false;
        }
    }

    private void configureRepository() {
        when(exceptionRepository.findByIdAndFactoryIdForUpdate(eq(EXCEPTION_ID), eq(FACTORY)))
                .thenAnswer(inv -> jdbc.query("select * from purchase_exceptions where id=? and factory_id=? and deleted_at is null for update",
                        (ResultSetExtractor<Optional<PurchaseException>>) rs ->
                                Optional.ofNullable(rs.next() ? readException(rs) : null), EXCEPTION_ID, FACTORY));
        when(exceptionRepository.save(any(PurchaseException.class))).thenAnswer(inv -> {
            PurchaseException e = inv.getArgument(0);
            if (failureMode == FailureMode.ASSOCIATION && saveCalls.incrementAndGet() == 2) {
                throw new RuntimeException("association persistence failed");
            }
            if (failureMode != FailureMode.ASSOCIATION) saveCalls.incrementAndGet();
            jdbc.update("update purchase_exceptions set status=?,decision=?,decision_by=?,decision_at=?,decision_notes=?,return_order_id=?,version=version+1 where id=?",
                    e.getStatus(), e.getDecision() == null ? null : e.getDecision().name(), e.getDecisionBy(), e.getDecisionAt(),
                    e.getDecisionNotes(), e.getReturnOrderId(), e.getId());
            return e;
        });
    }

    private PurchaseException readException(ResultSet rs) throws java.sql.SQLException {
        PurchaseException e = new PurchaseException();
        e.setId(rs.getString("id")); e.setFactoryId(rs.getString("factory_id"));
        e.setExceptionNumber(rs.getString("exception_number")); e.setReceiveRecordId(rs.getString("receive_record_id"));
        e.setPurchaseOrderId(rs.getString("purchase_order_id")); e.setSupplierId(rs.getString("supplier_id"));
        e.setMaterialTypeId(rs.getString("material_type_id")); e.setMaterialName(rs.getString("material_name"));
        e.setExceptionType(ReceiveExceptionType.valueOf(rs.getString("exception_type")));
        e.setPoQuantity(rs.getBigDecimal("po_quantity")); e.setReceivedQuantity(rs.getBigDecimal("received_quantity"));
        e.setExceptionQty(rs.getBigDecimal("exception_qty")); e.setUnit(rs.getString("unit"));
        e.setStatus(rs.getString("status")); e.setCreatedBy(rs.getLong("created_by"));
        e.setOwnerUserId(rs.getLong("owner_user_id")); e.setVersion(rs.getLong("version"));
        e.setReturnOrderId(rs.getString("return_order_id"));
        return e;
    }

    private void configureSource() {
        PurchaseOrder order = new PurchaseOrder(); order.setId(PO_ID); order.setFactoryId(FACTORY);
        when(orderRepository.findByIdAndFactoryId(PO_ID, FACTORY)).thenReturn(Optional.of(order));
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterialTypeId(MATERIAL_ID); item.setUnit("kg"); item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE); item.setUnitPrice(BigDecimal.TEN); item.setTaxRate(BigDecimal.ZERO);
        item.setPurchasePackageUnitSnapshot("kg"); item.setInventoryBaseUnitSnapshot("kg");
        item.setPackageToBaseFactorSnapshot(BigDecimal.ONE);
        when(itemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(item));
        when(returnOrderService.createReturnOrder(eq(FACTORY), any(), anyLong())).thenAnswer(inv -> {
            childInsertCount.incrementAndGet();
            String returnOrderId = UUID.randomUUID().toString();
            createdReturnOrderId = returnOrderId;
            jdbc.update("insert into return_orders(id) values (?)", returnOrderId);
            if (failureMode == FailureMode.CHILD) throw new RuntimeException("child write failed");
            ReturnOrder orderResult = new ReturnOrder();
            orderResult.setId(returnOrderId); orderResult.setReturnNumber("RT-PG-1");
            return orderResult;
        });
    }

    private static String withSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
