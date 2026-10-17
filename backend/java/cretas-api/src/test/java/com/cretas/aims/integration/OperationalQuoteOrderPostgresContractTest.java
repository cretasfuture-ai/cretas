package com.cretas.aims.integration;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.sales.OperationalQuoteRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.sales.OperationalQuoteOrderGuard;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="CRETAS_WORKFLOW_PG_VERIFY",matches="true")
class OperationalQuoteOrderPostgresContractTest {
    static String url,user,password,schema,scopedUrl;
    static SessionFactory factory;
    static org.flywaydb.core.Flyway migration;
    @BeforeAll static void setup() throws Exception {
        url=DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        user=System.getenv("CRETAS_WORKFLOW_PG_USER");password=System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD","");
        schema="quote_order_verify_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        scopedUrl=url+"?currentSchema="+schema;
        var cfg=new Configuration().addAnnotatedClass(OperationalQuote.class);
        cfg.setProperty("hibernate.connection.url",scopedUrl);cfg.setProperty("hibernate.connection.username",user);
        cfg.setProperty("hibernate.connection.password",password);cfg.setProperty("hibernate.hbm2ddl.auto","create-drop");
        cfg.setProperty("hibernate.default_schema",schema);cfg.setProperty("hibernate.show_sql","false");
        factory=cfg.buildSessionFactory();
        try(var c=connection();var s=c.createStatement()) {
            s.execute("CREATE TABLE sales_order_items (id BIGINT PRIMARY KEY, unit_price NUMERIC(15,4));");
            s.execute("INSERT INTO sales_order_items VALUES (1,54.8673)");
        }
        migration=org.flywaydb.core.Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/flyway").baselineOnMigrate(true).baselineVersion("20261103.64")
                .target("20261103.65").outOfOrder(false).load();
        assertEquals(1,migration.migrate().migrationsExecuted);
        factory.inTransaction(s->{var q=new OperationalQuote();q.setId("Q");q.setFactoryId("F");q.setQuoteNo("QT-VERIFY");
            q.setCustomerId("C");q.setProductTypeId("P");q.setStatus("APPROVED");q.setUnit("个");q.setUnitPrice(new BigDecimal("62"));s.persist(q);});
    }
    @AfterAll static void cleanup() throws Exception {
        if(factory!=null)factory.close();
        if(schema!=null)try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    @BeforeEach void reset() throws Exception { try(var c=connection();var s=c.createStatement()){s.executeUpdate("UPDATE operational_quotes SET status='APPROVED'");} }
    @Test void realFlywayAddsANullableSnapshotWithoutChangingLegacyRowsAndValidates() throws Exception {
        migration.validate(); assertEquals(0,migration.migrate().migrationsExecuted);
        try(var c=connection();var s=c.createStatement();var rows=s.executeQuery("SELECT unit_price,quoted_gross_unit_price FROM sales_order_items WHERE id=1")) {
            assertTrue(rows.next()); assertEquals(new BigDecimal("54.8673"),rows.getBigDecimal(1)); assertNull(rows.getBigDecimal(2)); assertFalse(rows.next());
        }
    }
    @Test void staleManagedApprovalIsRefreshedFromRealPostgresBeforeAcceptance() throws Exception {
        try(var session=factory.openSession()) {
            var tx=session.beginTransaction();var cached=session.find(OperationalQuote.class,"Q");assertEquals("APPROVED",cached.getStatus());
            try(var c=connection();var s=c.createStatement()){s.executeUpdate("UPDATE operational_quotes SET status='REJECTED' WHERE id='Q'");}
            assertThrows(BusinessException.class,()->guard(session).validateCreate("F",request()));
            assertEquals("REJECTED",cached.getStatus());tx.rollback();
        }
    }
    @Test void validatedQuoteCannotBeRevisedUntilTheOrderTransactionCompletes() throws Exception {
        try(var session=factory.openSession();var c=connection();var s=c.createStatement()) {
            var tx=session.beginTransaction();guard(session).validateCreate("F",request());
            s.execute("SET lock_timeout='200ms'");
            SQLException error=assertThrows(SQLException.class,()->s.executeUpdate("UPDATE operational_quotes SET status='REJECTED' WHERE id='Q'"));
            assertEquals("55P03",error.getSQLState());tx.commit();
            assertEquals(1,s.executeUpdate("UPDATE operational_quotes SET status='REJECTED' WHERE id='Q'"));
        }
    }
    static Connection connection() throws SQLException {return DriverManager.getConnection(scopedUrl,user,password);}
    static OperationalQuoteOrderGuard guard(Session session) {
        var repository=mock(OperationalQuoteRepository.class);var units=mock(UnitContractService.class);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("Q","F")).thenAnswer(i->Optional.ofNullable(session.find(OperationalQuote.class,"Q")));
        when(units.normalize(eq("F"),anyString())).thenAnswer(i->new UnitNormalizationResult(i.getArgument(1),i.getArgument(1),null));
        var guard=new OperationalQuoteOrderGuard(repository,units,mock(DataScopeResolver.class));
        ReflectionTestUtils.setField(guard,"entityManager",session);return guard;
    }
    static CreateSalesOrderRequest request() {
        var r=new CreateSalesOrderRequest();r.setQuoteId("Q");r.setCustomerId("C");
        var i=new CreateSalesOrderRequest.SalesOrderItemDTO();i.setProductTypeId("P");i.setUnit("个");i.setQuantity(BigDecimal.ONE);
        i.setUnitPrice(new BigDecimal("54.8673"));i.setTaxRate(new BigDecimal("13"));r.setItems(List.of(i));return r;
    }
}
