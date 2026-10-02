package com.cretas.aims.service.reports;

import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatingReportFailurePersistenceContractTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class TestConfiguration {
        @Bean DataSource dataSource(){JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:synthetic_report_failure;DB_CLOSE_DELAY=-1");return data;}
        @Bean DataSourceTransactionManager transactionManager(DataSource data){return new DataSourceTransactionManager(data);}
        @Bean JdbcTemplate jdbc(DataSource data){return new JdbcTemplate(data);}
        @Bean OperatingReportCodec codec(){return new OperatingReportCodec(new ObjectMapper().findAndRegisterModules());}
        @Bean AIAnalysisResultRepository repository(JdbcTemplate jdbc){
            var repository=mock(AIAnalysisResultRepository.class);
            when(repository.saveAndFlush(any(AIAnalysisResult.class))).thenAnswer(call->{AIAnalysisResult record=call.getArgument(0);jdbc.update("INSERT INTO synthetic_reports(factory_id,payload) VALUES (?,?)",record.getFactoryId(),record.getAnalysisText());return record;});
            return repository;
        }
        @Bean OperatingReportStore store(AIAnalysisResultRepository repository,OperatingReportCodec codec){return new OperatingReportStore(repository,codec);}
    }
    @Test void failedRecordCommitsDespiteCallerRollbackAndDisplaysOnlySafeReason(){
        try(var context=new AnnotationConfigApplicationContext(TestConfiguration.class)){
            JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE synthetic_reports(factory_id VARCHAR(100),payload CLOB)");
            OperatingReportStore store=context.getBean(OperatingReportStore.class);
            TransactionTemplate outer=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            assertThrows(IllegalStateException.class,()->outer.execute(status->{
                store.failure("SYNTHETIC_REPORT_FAILURE","daily",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),OperatingReportSnapshot.Failure.SOURCE_UNAVAILABLE,true);
                throw new IllegalStateException("synthetic sensitive connection string must not be persisted");
            }));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_reports",Integer.class));
            String payload=jdbc.queryForObject("SELECT payload FROM synthetic_reports",String.class);
            OperatingReportCodec codec=context.getBean(OperatingReportCodec.class);
            var record=codec.decode(payload).orElseThrow();
            assertEquals(OperatingReportSnapshot.Status.FAILED,record.status());
            assertEquals(OperatingReportSnapshot.Failure.SOURCE_UNAVAILABLE,record.failure());
            assertFalse(payload.contains("sensitive connection"));
            var response=new OperatingReportProjection(codec).detail(AIAnalysisResult.builder().analysisText(payload).build(),false);
            assertEquals("FAILED",response.getGenerationStatus()); assertEquals("SOURCE_UNAVAILABLE",response.getFailureCode());
            assertTrue(response.getAnalysis().contains("生成失败"));
            assertEquals(record.failure().reason(),response.getErrorMessage());
        }
    }
}
