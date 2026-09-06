package com.cretas.aims.config.datasource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Primary DataSource Configuration (MySQL)
 *
 * Handles all existing business data:
 * - User authentication
 * - Factory management
 * - Traceability data
 * - Original SmartBI data (fixed schema)
 *
 * Excludes: repository.smartbi.postgres package (handled by SmartBIPostgresDataSourceConfig)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-25
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = {
        "com.cretas.aims.repository",
        // 一加物流 logistics MVP bounded context (repositories live outside com.cretas.aims.repository)
        "com.cretas.aims.logistics.repository"
    },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.cretas\\.aims\\.repository\\.smartbi\\.postgres\\..*"
    ),
    entityManagerFactoryRef = "primaryEntityManagerFactory",
    transactionManagerRef = "primaryTransactionManager"
)
public class PrimaryDataSourceConfig {

    @Value("${spring.jpa.properties.hibernate.dialect:org.hibernate.dialect.PostgreSQLDialect}")
    private String hibernateDialect;

    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    @Value("${spring.jpa.show-sql:false}")
    private String showSql;

    /**
     * Primary DataSource Properties
     */
    @Primary
    @Bean(name = "primaryDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Primary DataSource
     * Note: auto-commit must be disabled for proper transaction management with PostgreSQL
     */
    @Primary
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // Disable auto-commit for proper transaction management with PostgreSQL
        ds.setAutoCommit(false);
        return ds;
    }

    /**
     * Primary EntityManagerFactory (MySQL)
     */
    @Primary
    @Bean(name = "primaryEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("primaryDataSource") DataSource dataSource) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", hibernateDialect);
        properties.put("hibernate.hbm2ddl.auto", ddlAuto);
        properties.put("hibernate.show_sql", showSql);
        properties.put("hibernate.format_sql", "false");

        // packages() 是递归扫描（根包 "com.cretas.aims.entity" 已经覆盖下面所有子包），
        // 没有「要本包不要子包」的写法，所以 entity.smartbi.postgres 下的实体也会被扫进来。
        // 它们属于 SmartBIPostgresDataSourceConfig 的 smartbiPostgres 单元（表只在 smartbi 库），
        // 这里 build 完之后用 PersistenceUnitPostProcessor 把它们从主单元剔掉 —— 与上面
        // @EnableJpaRepositories 对 repository.smartbi.postgres 的 excludeFilters 对称。
        // 不剔的后果：主单元 ddl-auto=validate 会去主库找 smart_bi_dynamic_data 等 4 张表
        // （#643 量出的 6 张缺表里有 4 张是它们）。守闸：SmartBiPostgresEntitiesBelongToOnePersistenceUnitContractTest。
        LocalContainerEntityManagerFactoryBean emf = builder
            .dataSource(dataSource)
            .packages(
                // Root entity package (entities not in subfolders)
                "com.cretas.aims.entity",
                // Core entity packages (no subpackage conflicts)
                "com.cretas.aims.entity.aps",
                "com.cretas.aims.entity.bom",
                "com.cretas.aims.entity.cache",
                "com.cretas.aims.entity.calibration",
                "com.cretas.aims.entity.common",
                "com.cretas.aims.entity.config",
                "com.cretas.aims.entity.conversation",
                "com.cretas.aims.entity.dahua",
                "com.cretas.aims.entity.decoration",
                "com.cretas.aims.entity.enums",
                "com.cretas.aims.entity.intent",
                "com.cretas.aims.entity.iot",
                "com.cretas.aims.entity.isapi",
                "com.cretas.aims.entity.learning",
                "com.cretas.aims.entity.ml",
                "com.cretas.aims.entity.rules",
                "com.cretas.aims.entity.scale",
                "com.cretas.aims.entity.tool",
                "com.cretas.aims.entity.voice",
                // SmartBI 固定表实体（smartbi.postgres 子包在下面被剔除）
                "com.cretas.aims.entity.smartbi",
                // 一加物流 logistics MVP bounded context (entities live outside com.cretas.aims.entity)
                "com.cretas.aims.logistics.entity"
            )
            .persistenceUnit("primary")
            .properties(properties)
            .build();
        emf.setPersistenceUnitPostProcessors(pui ->
            pui.getManagedClassNames().removeIf(
                className -> className.startsWith(SMARTBI_POSTGRES_ENTITY_PACKAGE_PREFIX)));
        return emf;
    }

    /**
     * 只属于 smartbiPostgres 持久化单元的实体包前缀（带尾点，避免误剔 "…smartbi.postgresX" 这种同前缀包）。
     */
    static final String SMARTBI_POSTGRES_ENTITY_PACKAGE_PREFIX = "com.cretas.aims.entity.smartbi.postgres.";

    /**
     * Primary TransactionManager
     */
    @Primary
    @Bean(name = "primaryTransactionManager")
    public PlatformTransactionManager primaryTransactionManager(
            @Qualifier("primaryEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
