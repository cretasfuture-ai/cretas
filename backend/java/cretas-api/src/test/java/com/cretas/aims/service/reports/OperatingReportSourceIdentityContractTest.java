package com.cretas.aims.service.reports;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonServiceCircuitBreaker;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatingReportSourceIdentityContractTest {
    private PythonSmartBIClient client(MockWebServer server,String secret){
        PythonSmartBIConfig config=new PythonSmartBIConfig();config.setUrl(server.url("/").toString());config.setMaxRetries(0);
        PythonServiceCircuitBreaker breaker=mock(PythonServiceCircuitBreaker.class);when(breaker.isCallPermitted()).thenReturn(true);
        return new PythonSmartBIClient(config,new OkHttpClient(),new ObjectMapper(),breaker,secret);
    }
    @Test void grantBoundFactoryUsesOnlyFixedReadEndpointAndServiceHeaders()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":true,\"data\":{\"summary\":{\"orderCount\":1}}}"));
            FactoryFixture fixture=new FactoryFixture();
            var context=OperatingReportContext.scheduled(fixture.factory(),"synthetic-read");
            var response=client(server,"synthetic-secret-not-a-credential").getOperatingSalesPreset(context.factoryId(),null,context.userRole(),"salesperson-performance",Map.of("startDate","2026-01-01","endDate","2026-01-01"));
            assertNotNull(response.get("summary"));
            var request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);
            assertEquals("GET",request.getMethod());assertTrue(request.getPath().startsWith("/api/smartbi/SYNTH-A/sales-preset/salesperson-performance?"));
            assertEquals("SYNTH-A",request.getHeader("X-Factory-Id"));assertEquals("finance_manager",request.getHeader("X-User-Role"));assertNull(request.getHeader("X-User-Id"));
            assertEquals("synthetic-secret-not-a-credential",request.getHeader("X-Internal-Secret"));
            PythonSmartBIClient client=client(server,"synthetic-secret-not-a-credential");
            assertThrows(IOException.class,()->client.getOperatingSalesPreset(null,null,"finance_manager","salesperson-performance",Map.of()));
            assertThrows(IOException.class,()->client.getOperatingSalesPreset("SYNTH-A",null,"finance_manager","../../outside",Map.of()));
            assertThrows(IOException.class,()->client.getOperatingSalesPreset("SYNTH-A",null,"finance_manager","salesperson-performance",Map.of("factoryId","SYNTH-B")));
            assertEquals(1,server.getRequestCount());
        }
    }
    @Test void emptySecretAndDeniedPayloadFailClosedAndRedirectIsNotFollowed()throws Exception{
        try(MockWebServer server=new MockWebServer();MockWebServer foreign=new MockWebServer()){
            assertThrows(IOException.class,()->client(server,"").getOperatingSalesPreset("SYNTH-A",null,"finance_manager","salesperson-performance",Map.of()));
            assertEquals(0,server.getRequestCount());
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"detail\":\"synthetic denied\"}"));
            assertThrows(IOException.class,()->client(server,"synthetic-wrong-secret").getOperatingSalesPreset("SYNTH-A",null,"finance_manager","salesperson-performance",Map.of()));
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",foreign.url("/elsewhere")));
            assertThrows(IOException.class,()->client(server,"synthetic-secret").getOperatingSalesPreset("SYNTH-A",null,"finance_manager","salesperson-performance",Map.of()));
            assertEquals(0,foreign.getRequestCount());
        }
    }
    @Test void scheduledCollectionCarriesAuditCorrelationAndRestoresCallerOnSuccessAndFailure()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            for(int i=0;i<4;i++)server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":true,\"data\":{}}"));
            var margin=mock(OperatingReportGrossMarginQuery.class);
            when(margin.fetch(anyString(),any(),any())).thenReturn(Map.of("rank",List.of(),"summary",Map.of()));
            var aging=mock(ArApService.class);when(aging.getAgingAnalysis(anyString(),any())).thenReturn(List.of());
            var income=mock(IncomeStatementService.class);when(income.generate(anyString(),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(new IncomeStatementDTO());
            var materials=mock(MaterialBatchService.class);when(materials.listStockSummary(anyString())).thenReturn(List.of());
            var batches=mock(MaterialBatchRepository.class);when(batches.findByFactoryId(anyString(),any(Pageable.class))).thenReturn(Page.empty());
            var collector=new OperatingReportCollector(client(server,"synthetic-secret"),margin,aging,income,materials,batches,new ObjectMapper());
            var context=OperatingReportContext.scheduled(new FactoryFixture().factory(),null);
            assertEquals(context.audit("READ").runId(),context.audit("READ").correlationId());
            String previous=MDC.get("correlationId");
            try {
                MDC.put("correlationId","synthetic-caller");
                LocalDate day=LocalDate.of(2026,1,1);
                collector.collect(context,"daily",day,day);
                assertEquals("synthetic-caller",MDC.get("correlationId"));
                for(int i=0;i<4;i++){
                    var request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);
                    assertEquals(context.audit("READ").correlationId(),request.getHeader("X-Correlation-ID"));
                    assertEquals("SYNTH-A",request.getHeader("X-Factory-Id"));assertNull(request.getHeader("X-User-Id"));
                }
                server.enqueue(new MockResponse().setResponseCode(401));
                assertThrows(IOException.class,()->collector.collect(context,"daily",day,day));
                assertEquals("synthetic-caller",MDC.get("correlationId"));
            } finally {
                if(previous==null)MDC.remove("correlationId");else MDC.put("correlationId",previous);
            }
        }
    }
    private static class FactoryFixture {
        com.cretas.aims.entity.Factory factory(){var factory=new com.cretas.aims.entity.Factory();factory.setId("SYNTH-A");factory.setIsActive(true);factory.setType(com.cretas.aims.entity.enums.FactoryType.FACTORY);return factory;}
    }
}
