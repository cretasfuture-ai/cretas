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
}
