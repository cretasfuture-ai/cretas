package com.cretas.aims.service.reports;

import com.cretas.aims.controller.AIController;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class OperatingReportEntryProjectionContractTest {
    @Test void realListDetailAndCacheEntriesFinanceWarehouseFinancePreserveOnlyAllowedBody()throws Exception{
        var mapper=new ObjectMapper().findAndRegisterModules();var codec=new OperatingReportCodec(mapper);var projection=new OperatingReportProjection(codec);
        var permissions=mock(PermissionService.class);var mobile=mock(MobileService.class);var users=mock(com.cretas.aims.repository.UserRepository.class);var reader=new OperatingReportReadAccess(mobile,permissions,users);
        var repository=mock(AIAnalysisResultRepository.class);var service=new AIEnterpriseService();var controller=new AIController();
        var generator=mock(OperatingReportGenerator.class);when(generator.isFactory("SYNTH-A")).thenReturn(true);
        ReflectionTestUtils.setField(service,"operatingReports",generator);
        ReflectionTestUtils.setField(service,"analysisResultRepository",repository);ReflectionTestUtils.setField(service,"operatingProjection",projection);
        ReflectionTestUtils.setField(service,"operatingCodec",codec);ReflectionTestUtils.setField(service,"operatingReadAccess",reader);
        ReflectionTestUtils.setField(controller,"aiEnterpriseService",service);ReflectionTestUtils.setField(controller,"mobileService",mobile);ReflectionTestUtils.setField(controller,"operatingReadAccess",reader);
        var snapshot=new OperatingReportSnapshot(OperatingReportSnapshot.SCHEMA,OperatingReportSnapshot.Status.SUCCESS,"daily","2026-01-01","2026-01-01","synthetic",List.of(),
                List.of(new OperatingReportSnapshot.Section("profit","成本毛利",true,"synthetic query",List.of(new OperatingReportSnapshot.Metric("synthetic.profit","毛利",new BigDecimal("55321"),"元",null,BigDecimal.ONE,"synthetic.sql")))),
                List.of(new OperatingReportSnapshot.Insight(OperatingReportSnapshot.InsightKind.REVIEW_RECORDED_VALUE,"synthetic.profit",OperatingReportSnapshot.Action.REVIEW_PROFIT)),null);
        var record=AIAnalysisResult.builder().id(42L).factoryId("SYNTH-A").reportType("daily").analysisText(codec.encode(snapshot)).expiresAt(LocalDateTime.now().plusDays(30)).build();record.setCreatedAt(LocalDateTime.now());
        when(repository.findById(42L)).thenReturn(Optional.of(record));when(repository.findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(eq("SYNTH-A"),any())).thenReturn(List.of(record));
        var actor=new AtomicReference<User>();when(mobile.getUserFromToken(anyString())).thenAnswer(call->{
            var dto=new com.cretas.aims.dto.user.UserDTO();dto.setId(actor.get().getId());dto.setFactoryId(actor.get().getFactoryId());return dto;
        });
        when(users.findById(321L)).thenAnswer(call->Optional.of(actor.get()));
        when(permissions.hasPermission(any(User.class),eq("procurement:price:view"))).thenAnswer(call->"finance_manager".equals(((User)call.getArgument(0)).getRoleCode()));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        for(String role:List.of("finance_manager","warehouse_manager","finance_manager")){
            User user=new User();user.setId(321L);user.setFactoryId("SYNTH-A");user.setRoleCode(role);actor.set(user);
            String detail=mvc.perform(get("/api/mobile/SYNTH-A/ai/reports/42").header("Authorization","Bearer SYNTHETIC_TOKEN")).andReturn().getResponse().getContentAsString();
            String list=mvc.perform(get("/api/mobile/SYNTH-A/ai/reports").header("Authorization","Bearer SYNTHETIC_TOKEN")).andReturn().getResponse().getContentAsString();
            boolean allowed=role.equals("finance_manager");
            assertEquals(allowed,detail.contains("55321"));assertEquals(allowed,list.contains("55321"));assertFalse(detail.contains("synthetic.sql"));assertFalse(detail.contains("principalType"));
            MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer SYNTHETIC_TOKEN");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                var cache=(com.cretas.aims.dto.MobileDTO.AICostAnalysisResponse)ReflectionTestUtils.invokeMethod(service,"buildResponseFromCache",record,"SYNTH-A");
                assertEquals(allowed,cache.getReportAnalysis()!=null);assertFalse(cache.getAnalysis().contains("55321"));
            } finally {RequestContextHolder.resetRequestAttributes();}
        }
        User foreign=new User();foreign.setId(321L);foreign.setRoleCode("finance_manager");foreign.setFactoryId("SYNTH-B");actor.set(foreign);
        assertFalse(mvc.perform(get("/api/mobile/SYNTH-A/ai/reports/42").header("Authorization","Bearer SYNTHETIC_TOKEN")).andReturn().getResponse().getContentAsString().contains("55321"));
        assertFalse(reader.current("SYNTH-A"));
    }
    @Test void knownRestaurantRetainsLegacyListDetailCacheAndManualReportContracts()throws Exception{
        var mapper=new ObjectMapper().findAndRegisterModules();var codec=new OperatingReportCodec(mapper);
        var factories=mock(com.cretas.aims.repository.FactoryRepository.class);
        var restaurant=new com.cretas.aims.entity.Factory();restaurant.setId("SYNTH-R");restaurant.setType(com.cretas.aims.entity.enums.FactoryType.RESTAURANT);
        when(factories.findById("SYNTH-R")).thenReturn(Optional.of(restaurant));
        var generator=new OperatingReportGenerator(factories,mock(PermissionService.class),mock(OperatingReportCollector.class),new OperatingReportAssembler(),codec,mock(OperatingReportStore.class),mock(com.cretas.aims.ai.client.PythonLLMClient.class),mock(com.cretas.aims.repository.config.AIReportPromptConfigRepository.class));
        var service=spy(new AIEnterpriseService());var repository=mock(AIAnalysisResultRepository.class);var reader=mock(OperatingReportReadAccess.class);
        ReflectionTestUtils.setField(service,"analysisResultRepository",repository);ReflectionTestUtils.setField(service,"operatingReports",generator);
        ReflectionTestUtils.setField(service,"operatingCodec",codec);ReflectionTestUtils.setField(service,"operatingProjection",new OperatingReportProjection(codec));
        ReflectionTestUtils.setField(service,"operatingReadAccess",reader);
        doReturn(com.cretas.aims.dto.MobileDTO.AIQuotaInfo.builder().total(100).used(0).remaining(100).build()).when(service).getQuotaInfo("SYNTH-R");
        String legacy="# Existing restaurant cost report 55321\n"+"query text ".repeat(30);
        var record=AIAnalysisResult.builder().id(43L).factoryId("SYNTH-R").reportType("weekly").analysisText(legacy).periodStart(LocalDateTime.of(2026,1,1,0,0)).periodEnd(LocalDateTime.of(2026,1,7,23,59,59)).expiresAt(LocalDateTime.now().plusDays(30)).build();record.setCreatedAt(LocalDateTime.now());
        when(repository.findById(43L)).thenReturn(Optional.of(record));when(repository.findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(eq("SYNTH-R"),any())).thenReturn(List.of(record));
        var request=com.cretas.aims.dto.MobileDTO.AIReportListRequest.builder().startDate(LocalDateTime.of(2026,2,1,0,0)).build();
        var list=service.getReportList("SYNTH-R",request,false);assertEquals(1,list.getTotal());
        assertEquals(legacy.substring(0,200)+"...",list.getReports().get(0).getSummaryText());assertNull(list.getReports().get(0).getFinancialSummaryText());
        var detail=service.getReportDetail("SYNTH-R",43L,false);assertEquals(legacy,detail.getAnalysis());assertNull(detail.getReportAnalysis());assertTrue(detail.getCacheHit());assertNotNull(detail.getQuota());
        var cache=(com.cretas.aims.dto.MobileDTO.AICostAnalysisResponse)ReflectionTestUtils.invokeMethod(service,"buildResponseFromCache",record,"SYNTH-R");assertEquals(legacy,cache.getAnalysis());
        var mobile=mock(MobileService.class);var actor=new com.cretas.aims.dto.user.UserDTO();actor.setId(321L);actor.setFactoryId("SYNTH-R");when(mobile.getUserFromToken("SYNTHETIC_TOKEN")).thenReturn(actor);
        var controller=new AIController();ReflectionTestUtils.setField(controller,"aiEnterpriseService",service);ReflectionTestUtils.setField(controller,"operatingReadAccess",reader);ReflectionTestUtils.setField(controller,"mobileService",mobile);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var body=mapper.readTree(mvc.perform(get("/api/mobile/SYNTH-R/ai/reports/43")).andReturn().getResponse().getContentAsString());assertEquals(legacy,body.path("data").path("analysis").asText());
        var listBody=mapper.readTree(mvc.perform(get("/api/mobile/SYNTH-R/ai/reports")).andReturn().getResponse().getContentAsString());assertEquals(legacy.substring(0,200)+"...",listBody.path("data").path("reports").get(0).path("summaryText").asText());
        var generation=new com.cretas.aims.dto.AIRequestDTO.ReportGenerationRequest();generation.setReportType("weekly");generation.setStartDate(java.time.LocalDate.of(2026,1,1));generation.setEndDate(java.time.LocalDate.of(2026,1,7));
        doReturn(detail).when(service).analyzeTimeRangeCost(eq("SYNTH-R"),eq(321L),any(),any(),eq("weekly"),isNull(),any());
        var http=new MockHttpServletRequest();http.addHeader("Authorization","Bearer SYNTHETIC_TOKEN");assertEquals(legacy,controller.generateReport("SYNTH-R",generation,http).getData().getAnalysis());
        verify(service).analyzeTimeRangeCost(eq("SYNTH-R"),eq(321L),any(),any(),eq("weekly"),isNull(),eq(http));verifyNoInteractions(reader);
        when(factories.findById("SYNTH-R")).thenReturn(Optional.empty());
        assertFalse(generator.usesLegacyReportContract("SYNTH-R"),"Unknown tenant must not gain the legacy raw-body bypass");
        assertFalse(service.getReportDetail("SYNTH-R",43L,false).getAnalysis().contains("55321"));
    }
}
