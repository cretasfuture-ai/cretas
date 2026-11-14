package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.PriceListItemRepository;
import com.cretas.aims.repository.inventory.PriceListRepository;
import com.cretas.aims.service.inventory.impl.PriceListServiceImpl;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户价目表最小闭环 — 真 PostgreSQL + 真 Hibernate/Spring Data 仓库:
 * 维护专属价 → 开单取价(resolveForOrder) → 价格来源; 含阴性对照(别的客户不带出、别的工厂看不到、采购价目不外露)
 * 以及迁移 V20261114_93 的列与可重复执行。
 */
class CustomerPriceListPostgresContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261114_93__sales_order_item_price_source.sql");

    @Test
    void dedicatedPriceFlowsIntoOrderResolutionWithSourceAndStaysIsolated() throws Exception {
        String baseUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "disposable PostgreSQL required");
        baseUrl = DisposablePostgresTargetGuard.requireSafeUrl(baseUrl);
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "cretas_workflow_verify_cpl_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, password))
                .execute("CREATE SCHEMA \"" + schema + "\"");
        String schemaUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&stringtype=unspecified";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, user, password));
        SessionFactory sessionFactory = null;
        StandardServiceRegistry registry = null;
        try {
            jdbc.execute("create table price_lists(id varchar(191) primary key, factory_id varchar(191) not null,"
                    + " name varchar(200) not null, customer_id varchar(191), price_type varchar(32) not null,"
                    + " effective_from date not null, effective_to date, is_active boolean not null default true,"
                    + " created_by bigint, remark text, created_at timestamp not null default now(),"
                    + " updated_at timestamp not null default now(), deleted_at timestamp)");
            jdbc.execute("create table price_list_items(id bigserial primary key, price_list_id varchar(191) not null,"
                    + " material_type_id varchar(191), product_type_id varchar(191), item_name varchar(200),"
                    + " unit varchar(20), standard_price numeric(15,4) not null, min_price numeric(15,4),"
                    + " max_price numeric(15,4), remark text, created_at timestamp not null default now(),"
                    + " updated_at timestamp not null default now(), deleted_at timestamp)");
            jdbc.execute("create table sales_order_items(id bigserial primary key)");
            String migration = Files.readString(MIGRATION);
            jdbc.execute(migration);
            jdbc.execute(migration); // 可重复执行
            assertEquals(2, jdbc.queryForObject("select count(*) from information_schema.columns"
                    + " where table_schema=current_schema() and table_name='sales_order_items'"
                    + " and column_name in ('price_source','price_ref')", Integer.class));

            registry = new StandardServiceRegistryBuilder()
                    .applySetting(AvailableSettings.JAKARTA_JDBC_URL, schemaUrl)
                    .applySetting(AvailableSettings.JAKARTA_JDBC_USER, user)
                    .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                    .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                    .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                    .build();
            MetadataSources sources = new MetadataSources(registry);
            PersistenceManagedTypes types = new PersistenceManagedTypesScanner(new DefaultResourceLoader())
                    .scan("com.cretas.aims.entity");
            types.getManagedClassNames().forEach(sources::addAnnotatedClassName);
            sessionFactory = sources.buildMetadata().buildSessionFactory();

            EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(sessionFactory);
            JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
            PriceListRepository lists = factory.getRepository(PriceListRepository.class);
            PriceListItemRepository items = factory.getRepository(PriceListItemRepository.class);
            CustomerRepository customers = mock(CustomerRepository.class);
            ProductTypeRepository products = mock(ProductTypeRepository.class);
            when(customers.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
            when(customers.findByIdAndFactoryId("C-A", "F1")).thenReturn(Optional.of(new Customer()));
            when(customers.findByIdAndFactoryId("C-B", "F1")).thenReturn(Optional.of(new Customer()));
            when(products.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
            ProductType p1 = new ProductType();
            p1.setName("牛肉丸");
            p1.setUnit("kg");
            when(products.findByIdAndFactoryId("P1", "F1")).thenReturn(Optional.of(p1));

            var tx = new TransactionTemplate(new JpaTransactionManager(sessionFactory));
            CustomerPriceListService service = new CustomerPriceListService(lists, items, customers, products);
            PriceListService resolver = new PriceListServiceImpl(lists, items);
            LocalDate today = LocalDate.now();

            // 采购价目 (同客户同产品) 不得出现在销售价目页, 也不得被开单取价命中
            jdbc.update("insert into price_lists(id,factory_id,name,customer_id,price_type,effective_from)"
                    + " values ('PL-PUR','F1','采购价','C-A','PURCHASE_PRICE',?)", today);
            jdbc.update("insert into price_list_items(price_list_id,product_type_id,item_name,standard_price)"
                    + " values ('PL-PUR','P1','牛肉丸',3.0000)");

            // 1) 维护: 新增 → 再次保存同产品是改价而不是重复行
            var created = tx.execute(s -> service.upsert("F1", "C-A", 7L, "P1", new BigDecimal("12.5000")));
            var updated = tx.execute(s -> service.upsert("F1", "C-A", 7L, "P1", new BigDecimal("13.2000")));
            assertEquals(created.getItemId(), updated.getItemId());
            var rows = tx.execute(s -> service.list("F1", "C-A"));
            assertEquals(1, rows.size(), "采购价目行不能混进来, 同产品只一行");
            assertEquals(0, new BigDecimal("13.2000").compareTo(rows.get(0).getStandardPrice()));
            assertTrue(rows.get(0).isEffective());
            assertEquals("牛肉丸", rows.get(0).getItemName());

            // 2) 开单取价: 客户 A 带出专属价, 来源 CUSTOMER + 价目表名
            var hitA = tx.execute(s -> resolver.resolveForOrder("F1", "C-A", "P1", today));
            assertTrue(hitA.isPresent());
            assertEquals("CUSTOMER", hitA.get().source());
            assertEquals(0, new BigDecimal("13.2000").compareTo(hitA.get().price()));
            assertEquals(CustomerPriceListService.DEDICATED_LIST_NAME, hitA.get().priceListName());

            // 阴性对照: 客户 B 不带出 A 的专属价 (采购价 3.0 也不能漏出)
            assertTrue(tx.execute(s -> resolver.resolveForOrder("F1", "C-B", "P1", today)).isEmpty());
            assertTrue(tx.execute(s -> service.list("F1", "C-B")).isEmpty());
            // 阴性对照: 别的工厂用同一个客户 ID 取不到
            assertTrue(tx.execute(s -> resolver.resolveForOrder("F2", "C-A", "P1", today)).isEmpty());
            assertThrows(ResourceNotFoundException.class, () -> tx.execute(s -> service.list("F2", "C-A")));
            assertThrows(RuntimeException.class,
                    () -> tx.execute(s -> service.upsert("F2", "C-A", 7L, "P1", BigDecimal.ONE)));

            // 3) 统一价兜底: 客户 B 命中 GLOBAL; A 仍是专属价 (专属优先)
            jdbc.update("insert into price_lists(id,factory_id,name,customer_id,price_type,effective_from)"
                    + " values ('PL-GLB','F1','统一价','', 'SELLING_PRICE',?)", today);
            jdbc.update("update price_lists set customer_id=null where id='PL-GLB'");
            jdbc.update("insert into price_list_items(price_list_id,product_type_id,item_name,standard_price)"
                    + " values ('PL-GLB','P1','牛肉丸',15.0000)");
            var hitB = tx.execute(s -> resolver.resolveForOrder("F1", "C-B", "P1", today));
            assertEquals("GLOBAL", hitB.orElseThrow().source());
            assertEquals("CUSTOMER", tx.execute(s -> resolver.resolveForOrder("F1", "C-A", "P1", today))
                    .orElseThrow().source());

            // 4) 删除: 不能用别的客户删, 本客户删后取价回落到统一价
            Long itemId = created.getItemId();
            assertThrows(ResourceNotFoundException.class,
                    () -> tx.execute(s -> { service.delete("F1", "C-B", itemId); return null; }));
            tx.execute(s -> { service.delete("F1", "C-A", itemId); return null; });
            assertTrue(tx.execute(s -> service.list("F1", "C-A")).isEmpty());
            assertEquals("GLOBAL", tx.execute(s -> resolver.resolveForOrder("F1", "C-A", "P1", today))
                    .orElseThrow().source());

            // 5) 产品不属于本工厂 → 拒绝
            assertThrows(RuntimeException.class,
                    () -> tx.execute(s -> service.upsert("F1", "C-A", 7L, "P-OTHER", BigDecimal.ONE)));

            // 6) 审查中2: 客户只有一张【已过期】专属表时, 改价不能去改过期行 (开单取不到), 要落到新的生效表
            jdbc.update("insert into price_lists(id,factory_id,name,customer_id,price_type,effective_from,effective_to)"
                    + " values ('PL-OLD','F1','旧专属价','C-B','SELLING_PRICE',?,?)", today.minusDays(30), today.minusDays(1));
            jdbc.update("insert into price_list_items(price_list_id,product_type_id,item_name,standard_price)"
                    + " values ('PL-OLD','P1','牛肉丸',5.0000)");
            tx.execute(s -> service.upsert("F1", "C-B", 7L, "P1", new BigDecimal("9.0000")));
            assertEquals(0, new BigDecimal("5.0000").compareTo(jdbc.queryForObject(
                    "select standard_price from price_list_items where price_list_id='PL-OLD'", BigDecimal.class)),
                    "过期表里的行不得被改写");
            var afterExpired = tx.execute(s -> resolver.resolveForOrder("F1", "C-B", "P1", today)).orElseThrow();
            assertEquals("CUSTOMER", afterExpired.source(), "保存后开单必须真的带出这个价, 而不是落到统一价");
            assertEquals(0, new BigDecimal("9.0000").compareTo(afterExpired.price()));
        } finally {
            if (sessionFactory != null) sessionFactory.close();
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
            new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, password))
                    .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }
}
