package com.cretas.aims.contract;

import com.cretas.aims.config.datasource.PrimaryDataSourceConfig;
import com.cretas.aims.config.datasource.SmartBIPostgresDataSourceConfig;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守的行为：{@code com.cretas.aims.entity.smartbi.postgres} 下的实体只属于 smartbiPostgres 持久化单元，
 * 不进主持久化单元。
 *
 * <p>为什么要守：这 4 张 {@code smart_bi_*} 表只在 {@code smartbi_prod_db} 里有，主库 {@code cretas_prod_db} 没有。
 * 主单元把它们扫进来之后，{@code ddl-auto=validate} 就会在主库上报 missing table —— #643 量出的 6 张缺表里有 4 张是它们。
 * 原代码的注释明写「因为 ddl-auto=none 所以不会建」，也就是说这套接线显式依赖 none 才成立。
 *
 * <p>怎么守：不 mock、不复制配置——直接调 {@link PrimaryDataSourceConfig#primaryEntityManagerFactory} 与
 * {@link SmartBIPostgresDataSourceConfig#smartbiPostgresEntityManagerFactory} 这两个真实的 {@code @Bean} 方法，
 * 在 H2 上把两个单元各自真的 build 出来，读 Hibernate 元模型。这样「机制在、没接上」（形态 B）会直接红：
 * 若剔除逻辑没有挂到 bean 上，主单元元模型里就会出现 {@code SmartBiDynamicData}。
 *
 * <p>变异对照（2026-09-06 实测）：把 {@code PrimaryDataSourceConfig} 里的 {@code setPersistenceUnitPostProcessors}
 * 那一行去掉 ⇒ {@code primaryUnitDoesNotManageSmartBiPostgresEntities} 红，报出 5 个泄漏类名。
 */
class SmartBiPostgresEntitiesBelongToOnePersistenceUnitContractTest {

    private static final String PG_ENTITY_PACKAGE = "com.cretas.aims.entity.smartbi.postgres";

    private final List<LocalContainerEntityManagerFactoryBean> built = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (LocalContainerEntityManagerFactoryBean bean : built) {
            bean.destroy();
        }
    }

    @Test
    void primaryUnitDoesNotManageSmartBiPostgresEntities() {
        PrimaryDataSourceConfig config = new PrimaryDataSourceConfig();
        ReflectionTestUtils.setField(config, "hibernateDialect", "org.hibernate.dialect.H2Dialect");
        ReflectionTestUtils.setField(config, "ddlAuto", "none");
        ReflectionTestUtils.setField(config, "showSql", "false");

        LocalContainerEntityManagerFactoryBean bean =
                config.primaryEntityManagerFactory(builder(), h2("pu_split_primary"));
        Set<String> primaryEntities = build(bean);

        // 阳性对照：这确实是主单元——smartbi 的固定表实体与核心实体都在里面，规模对得上。
        assertThat(primaryEntities)
                .contains("com.cretas.aims.entity.smartbi.SmartBiSalesData",
                          "com.cretas.aims.entity.ProductionPlan");
        assertThat(primaryEntities.size())
                .as("主单元元模型规模（#643 时 Hibernate 期待 443 张表）")
                .isGreaterThan(400);

        // 被守的行为。
        List<String> leaked = primaryEntities.stream()
                .filter(name -> name.startsWith(PG_ENTITY_PACKAGE + "."))
                .sorted()
                .toList();
        assertThat(leaked)
                .as("主持久化单元不该管理 smartbi.postgres 实体（它们的表只在 smartbi 库）")
                .isEmpty();

        // managedClassNames 是剔除动作作用的那一层；元模型是它的结果。两层都看，免得只有一层被改。
        List<String> leakedManaged = bean.getPersistenceUnitInfo().getManagedClassNames().stream()
                .filter(name -> name.startsWith(PG_ENTITY_PACKAGE + "."))
                .sorted()
                .toList();
        assertThat(leakedManaged)
                .as("主单元 managedClassNames 里不该残留 smartbi.postgres 包的类")
                .isEmpty();
    }

    @Test
    void smartbiUnitManagesExactlyTheSmartBiPostgresEntities() {
        Set<String> declared = entityClassesUnder(PG_ENTITY_PACKAGE);
        assertThat(declared)
                .as("阳性对照：包里确实有 @Entity（扫错目录时这里会是空集）")
                .isNotEmpty();

        SmartBIPostgresDataSourceConfig config = new SmartBIPostgresDataSourceConfig();
        LocalContainerEntityManagerFactoryBean bean =
                config.smartbiPostgresEntityManagerFactory(builder(), h2("pu_split_smartbi"));
        Set<String> smartbiEntities = build(bean);

        assertThat(smartbiEntities)
                .as("smartbiPostgres 单元应恰好管理 entity.smartbi.postgres 下的全部 @Entity")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    /** 两个单元对这批实体的划分必须是「恰好一处」：并集全覆盖、交集为空。 */
    @Test
    void everySmartBiPostgresEntityLivesInExactlyOneUnit() {
        Set<String> declared = entityClassesUnder(PG_ENTITY_PACKAGE);
        assertThat(declared).isNotEmpty();

        PrimaryDataSourceConfig primary = new PrimaryDataSourceConfig();
        ReflectionTestUtils.setField(primary, "hibernateDialect", "org.hibernate.dialect.H2Dialect");
        ReflectionTestUtils.setField(primary, "ddlAuto", "none");
        ReflectionTestUtils.setField(primary, "showSql", "false");
        Set<String> primaryEntities = build(primary.primaryEntityManagerFactory(builder(), h2("pu_split_primary2")));

        Set<String> smartbiEntities = build(new SmartBIPostgresDataSourceConfig()
                .smartbiPostgresEntityManagerFactory(builder(), h2("pu_split_smartbi2")));

        for (String entity : declared) {
            boolean inPrimary = primaryEntities.contains(entity);
            boolean inSmartbi = smartbiEntities.contains(entity);
            assertThat(inPrimary ^ inSmartbi)
                    .as("%s 必须恰好在一个单元里（primary=%s, smartbi=%s）", entity, inPrimary, inSmartbi)
                    .isTrue();
        }
    }

    // ---- helpers ----

    private Set<String> build(LocalContainerEntityManagerFactoryBean bean) {
        built.add(bean);
        bean.afterPropertiesSet();
        EntityManagerFactory emf = bean.getObject();
        assertThat(emf).isNotNull();
        return emf.getMetamodel().getEntities().stream()
                .map(e -> e.getJavaType().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static EntityManagerFactoryBuilder builder() {
        return new EntityManagerFactoryBuilder(new HibernateJpaVendorAdapter(), Map.of(), null);
    }

    private static DataSource h2(String name) {
        // 私有内存库，不碰共享的 testdb；两个单元都用 H2 只为把元模型 build 出来，不为读写。
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
    }

    private static Set<String> entityClassesUnder(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents(basePackage).stream()
                .map(bd -> bd.getBeanClassName())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
