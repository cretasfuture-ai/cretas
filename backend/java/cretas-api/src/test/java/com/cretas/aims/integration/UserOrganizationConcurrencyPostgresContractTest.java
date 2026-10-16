package com.cretas.aims.integration;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CRETAS_WORKFLOW_PG_VERIFY",matches="true")
class UserOrganizationConcurrencyPostgresContractTest {
    @Test void concurrentReciprocalManagersSerializeAndSecondCycleIsRejected() throws Exception {
        String url=System.getenv("CRETAS_WORKFLOW_PG_URL");DisposablePostgresTargetGuard.requireSafeUrl(url);
        String schema="organization_verify_"+UUID.randomUUID().toString().replace("-","");
        String secondApp="org_second_"+schema;String user=System.getenv("CRETAS_WORKFLOW_PG_USER"),secret=System.getenv("CRETAS_WORKFLOW_PG_PASSWORD");
        var reachedSave=new CountDownLatch(1);var commitFirst=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try(Connection admin=DriverManager.getConnection(url,user,secret);Statement statement=admin.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            statement.execute("CREATE TABLE "+schema+".users(id bigint PRIMARY KEY,factory_id varchar,is_active boolean,reports_to bigint,deleted_at timestamp)");
            statement.execute("INSERT INTO "+schema+".users VALUES(1,'F1',true,NULL),(2,'F1',true,NULL)");
            try {
                var first=target(url,schema,"org_first_"+schema,user,secret,reachedSave,commitFirst);
                var second=target(url,schema,secondApp,user,secret,null,null);
                Future<?> a=executor.submit(()->first.tx.execute(s->{first.service.updateUser("F1",1L,request(2));return null;}));
                assertThat(reachedSave.await(5,TimeUnit.SECONDS)).isTrue();
                Future<?> b=executor.submit(()->second.tx.execute(s->{second.service.updateUser("F1",2L,request(1));return null;}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    try(PreparedStatement q=admin.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND wait_event='advisory')")) {q.setString(1,secondApp);try(ResultSet rs=q.executeQuery()){rs.next();waiting=rs.getBoolean(1);}}
                    if(waiting||b.isDone())break;Thread.sleep(20);
                }
                assertThat(waiting).as("second real PostgreSQL transaction waits on the factory reporting lock").isTrue();
                assertThat(b.isDone()).isFalse();commitFirst.countDown();a.get(5,TimeUnit.SECONDS);
                assertThatThrownBy(()->b.get(5,TimeUnit.SECONDS)).rootCause().isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
                try(ResultSet rs=statement.executeQuery("SELECT id,reports_to FROM "+schema+".users ORDER BY id")) {rs.next();assertThat(rs.getLong(2)).isEqualTo(2);rs.next();assertThat(rs.getObject(2)).isNull();}
            } finally {commitFirst.countDown();executor.shutdown();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        } finally {commitFirst.countDown();executor.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void wholeSubtreeReparentingKeepsEveryDescendantWithinTenLevels(boolean activeLeaf) throws Exception {
        String url=System.getenv("CRETAS_WORKFLOW_PG_URL");DisposablePostgresTargetGuard.requireSafeUrl(url);
        String schema="organization_depth_"+UUID.randomUUID().toString().replace("-","");
        String user=System.getenv("CRETAS_WORKFLOW_PG_USER"),secret=System.getenv("CRETAS_WORKFLOW_PG_PASSWORD");
        try(Connection admin=DriverManager.getConnection(url,user,secret);Statement statement=admin.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("CREATE TABLE "+schema+".users(id bigint PRIMARY KEY,factory_id varchar,is_active boolean,reports_to bigint,deleted_at timestamp)");
                statement.execute("INSERT INTO "+schema+".users SELECT n,'F1',true,CASE WHEN n=1 THEN NULL ELSE n-1 END FROM generate_series(1,11) n");
                statement.execute("INSERT INTO "+schema+".users VALUES(12,'F1',true,NULL)");
                if(!activeLeaf)statement.execute("UPDATE "+schema+".users SET is_active=false WHERE id=11");
                var target=target(url,schema,"org_depth_"+schema,user,secret,null,null);
                assertThatThrownBy(()->target.tx.execute(s->{target.service.updateUser("F1",1L,request(12));return null;}))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
                try(ResultSet rs=statement.executeQuery("SELECT reports_to FROM "+schema+".users WHERE id=1")){rs.next();assertThat(rs.getObject(1)).isNull();}
                if(!activeLeaf){
                    target.tx.execute(s->{target.service.activateUser("F1",11L);return null;});
                    try(ResultSet rs=statement.executeQuery("SELECT is_active FROM "+schema+".users WHERE id=11")){rs.next();assertThat(rs.getBoolean(1)).isTrue();}
                }
                target.tx.execute(s->{target.service.updateUser("F1",2L,request(12));return null;});
                try(ResultSet rs=statement.executeQuery("SELECT reports_to FROM "+schema+".users WHERE id=2")){rs.next();assertThat(rs.getLong(1)).isEqualTo(12);}
            } finally {statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    @Test void softDeletedManagerCannotBeAssignedDespiteActiveFlag() throws Exception {
        String url=System.getenv("CRETAS_WORKFLOW_PG_URL");
        DisposablePostgresTargetGuard.requireSafeUrl(url);
        String schema="org_deleted_"+UUID.randomUUID().toString().replace("-","");
        String login=System.getenv("CRETAS_WORKFLOW_PG_USER"),secret=System.getenv("CRETAS_WORKFLOW_PG_PASSWORD");
        try(var connection=DriverManager.getConnection(url,login,secret);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("CREATE TABLE "+schema+".users(id bigint PRIMARY KEY,factory_id varchar,is_active boolean,reports_to bigint,deleted_at timestamp)");
                statement.execute("INSERT INTO "+schema+".users VALUES(1,'F1',true,NULL,NULL),(2,'F1',true,NULL,CURRENT_TIMESTAMP)");
                var target=target(url,schema,"soft_deleted_manager",login,secret,null,null);
                assertThatThrownBy(()->target.tx.execute(s->{target.service.updateUser("F1",1L,request(2));return null;}))
                        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(404));
                try(var rows=statement.executeQuery("SELECT reports_to FROM "+schema+".users WHERE id=1")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getObject(1)).isNull();
                }
            } finally { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    private record Target(UserServiceImpl service,TransactionTemplate tx) {}
    private static Target target(String url,String schema,String app,String login,String secret,CountDownLatch reached,CountDownLatch release) {
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+"&ApplicationName="+app,login,secret);
        var jdbc=new JdbcTemplate(ds);var users=mock(UserRepository.class);
        when(users.findById(anyLong())).thenAnswer(i->{long id=i.getArgument(0);return jdbc.query("SELECT id,factory_id,is_active,reports_to FROM users WHERE id=?",(rs,n)->{var u=new User();u.setId(rs.getLong(1));u.setFactoryId(rs.getString(2));u.setIsActive(rs.getBoolean(3));u.setReportsTo(rs.getObject(4,Long.class));u.setRoleCode("sales_manager");return u;},id).stream().findFirst();});
        when(users.save(any(User.class))).thenAnswer(i->{User u=i.getArgument(0);if(reached!=null){reached.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}jdbc.update("UPDATE users SET reports_to=?,is_active=? WHERE factory_id=? AND id=?",u.getReportsTo(),u.getIsActive(),u.getFactoryId(),u.getId());return u;});
        var service=new UserServiceImpl(users,mock(FactoryRepository.class),new UserMapper(),mock(org.springframework.security.crypto.password.PasswordEncoder.class),mock(com.cretas.aims.utils.ExcelUtil.class));
        ReflectionTestUtils.setField(service,"organizationJdbc",jdbc);
        return new Target(service,new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
    private static CreateUserRequest request(long manager){var r=new CreateUserRequest();r.setRoleCode(com.cretas.aims.entity.enums.FactoryUserRole.sales_manager);r.setReportsTo(manager);return r;}
}
