package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.*;
import com.cretas.aims.entity.cron.ScheduledTask;
import jakarta.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real migrated schema + Hibernate validate + parsed Repository methods; PG evidence requires observed PG. */
class PurchaseGrantTimerBindingRepositoryQueryValidationTest {
    @Test void migration72To72Point1ParsesQueriesAndOldMappingsStillColdStart() throws Exception {
        String pg=System.getenv("CRETAS_WORKFLOW_PG_URL"); boolean postgres=pg!=null && !pg.isBlank();
        if(postgres && (!pg.startsWith("jdbc:postgresql://127.0.0.1:") || !pg.endsWith("/cretas_workflow_verify_test")))
            throw new IllegalStateException("unsafe timer migration database");
        var ds=new DriverManagerDataSource(postgres?pg:"jdbc:h2:mem:timer_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                postgres?System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER","cretas_ci"):"sa",
                postgres?System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD",""):"");
        String schema="timer_gate_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=ds.getConnection();var statement=connection.createStatement()) {
            System.out.println("TIMER_MIGRATION_DATABASE_VERSION="+connection.getMetaData().getDatabaseProductVersion());
            statement.execute("CREATE SCHEMA "+schema);
            try {
                // Produce the real previous entity shape, then upgrade with the actual Flyway file.
                var old=factory(ds,schema,"create",false);old.destroy();
                Path migration=Path.of("src/main/resources/db/flyway/V20261103_72_1__create_purchase_grant_timer_bindings.sql");
                Path location=Files.createTempDirectory(Path.of("target"),"timer-migration-");
                Files.copy(migration,location.resolve(migration.getFileName()));
                Flyway flyway=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                        .locations("filesystem:"+location.toAbsolutePath()).baselineVersion("20261103.72").baselineOnMigrate(true).load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("20261103.72.1");
                var current=factory(ds,schema,"validate",true);
                try(var em=current.getObject().createEntityManager()) {
                    var repository=new JpaRepositoryFactory(em).getRepository(PurchaseGrantTimerBindingRepository.class);
                    em.getTransaction().begin();
                    // Native scalar prerequisite rows intentionally omit unrelated String/jsonb fields.
                    em.createNativeQuery("INSERT INTO "+schema+".ai_purchase_task_grants(id,factory_id,user_id,tool_name,descriptor_version,supplier_id,material_type_id,quantity_cap,quantity_unit,unit_price,amount_cap,currency,tax_rate,input_digest,approved_input_snapshot,expires_at,version,created_at,updated_at) VALUES('preview-1','F006',42,'purchase_order_create','2.0.0','S','M',1,'kg',1,1,'CNY',0,'digest','snapshot',CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").executeUpdate();
                    ScheduledTask task=new ScheduledTask();task.setFactoryId("F006");task.setTaskCode("timer-jpa");task.setHandlerBeanName("purchaseGrantTimerTaskHandler");task.setCronExpression("0 0 0 1 1 *");task.setEnabled(true);em.persist(task);em.flush();
                    PurchaseGrantTimerBinding b=new PurchaseGrantTimerBinding();b.setFactoryId("F006");b.setGrantId("preview-1");b.setUserId(42L);b.setScheduledTaskId(task.getId());b.setExpectedGrantVersion(1L);b.setInputDigest("digest");b.setScopeDigest("scope");b.setInputSnapshot("snapshot");b.setCommandDigest("command");b.setExecuteAt(LocalDateTime.now().plusSeconds(2));b.setExpiresAt(LocalDateTime.now().plusMinutes(1));b.setCronExpression(task.getCronExpression());b.setTaskRevision(task.getVersion());b.setState("PENDING");em.persist(b);em.flush();
                    assertThat(repository.lockTask(task.getId(),"FOREIGN")).isEmpty();
                    assertThat(repository.lockTask(task.getId(),"F006")).get().extracting(PurchaseGrantTimerBinding::getUserId).isEqualTo(42L);
                    assertThat(repository.findByGrantIdAndFactoryIdAndUserId("preview-1","F006",43L)).isEmpty();
                    assertThat(repository.findByGrantIdAndFactoryIdAndUserId("preview-1","F006",42L)).isPresent();
                    assertThat(repository.findByStateIn(List.of("PENDING","UNKNOWN"))).hasSize(1);
                    em.getTransaction().commit();
                } finally {current.destroy();}
                // The additive table does not prevent previous mappings from starting with validate.
                var rollback=factory(ds,schema,"validate",false);rollback.destroy();
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            } finally {statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static LocalContainerEntityManagerFactoryBean factory(DriverManagerDataSource ds,String schema,String ddl,boolean binding) {
        var bean=new LocalContainerEntityManagerFactoryBean();bean.setDataSource(ds);bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setPackagesToScan("com.cretas.aims.entity.ai");
        bean.setPersistenceUnitPostProcessors(unit->{unit.getManagedClassNames().clear();unit.addManagedClassName(AiPurchaseTaskGrant.class.getName());unit.addManagedClassName(ScheduledTask.class.getName());if(binding)unit.addManagedClassName(PurchaseGrantTimerBinding.class.getName());});
        bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto",ddl,"hibernate.default_schema",schema));
        bean.afterPropertiesSet();return bean;
    }
}
