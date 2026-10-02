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
    private static class FactoryFixture {
        com.cretas.aims.entity.Factory factory(){var factory=new com.cretas.aims.entity.Factory();factory.setId("SYNTH-A");factory.setIsActive(true);factory.setType(com.cretas.aims.entity.enums.FactoryType.FACTORY);return factory;}
    }
}
