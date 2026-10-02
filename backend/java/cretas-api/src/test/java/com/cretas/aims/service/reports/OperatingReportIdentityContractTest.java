package com.cretas.aims.service.reports;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatingReportIdentityContractTest {
    private final FactoryRepository factories=mock(FactoryRepository.class);
    private final PermissionService permissions=mock(PermissionService.class);
    private final OperatingReportCollector collector=mock(OperatingReportCollector.class);
    private final OperatingReportStore store=mock(OperatingReportStore.class);
    private final PythonLLMClient model=mock(PythonLLMClient.class);
    private final OperatingReportCodec codec=new OperatingReportCodec(new ObjectMapper());
    private final OperatingReportGenerator generator=new OperatingReportGenerator(factories,permissions,collector,new OperatingReportAssembler(),codec,store,model,mock(AIReportPromptConfigRepository.class));
    private final LocalDate day=LocalDate.of(2026,1,1);
    private Factory factory(String id,FactoryType type){Factory factory=new Factory();factory.setId(id);factory.setType(type);factory.setIsActive(true);return factory;}
    private User user(String factory,String role){User user=new User();user.setId(123L);user.setFactoryId(factory);user.setRoleCode(role);return user;}

    @Test void manualNullWarehouseAndForeignActorsNeverReachFinancialQueryOrModel(){
        assertThrows(BusinessException.class,()->generator.manual("SYNTH-A",null,"daily",day,day));
        assertThrows(BusinessException.class,()->generator.manual("SYNTH-A",user("SYNTH-A","warehouse_manager"),"daily",day,day));
        User foreign=user("SYNTH-B","finance_manager");when(permissions.hasPermission(foreign,"procurement:price:view")).thenReturn(true);
        assertThrows(BusinessException.class,()->generator.manual("SYNTH-A",foreign,"daily",day,day));
        verifyNoInteractions(collector,model,store);
    }
    @Test void schedulerUsesFactoryGrantServiceAuditAndNoHumanIdentity()throws Exception{
        when(factories.findById("SYNTH-A")).thenReturn(Optional.of(factory("SYNTH-A",FactoryType.FACTORY)));
        when(collector.collect(any(),eq("daily"),eq(day),eq(day))).thenAnswer(invocation->{
            OperatingReportContext context=invocation.getArgument(0);
            assertEquals("SYNTH-A",context.factoryId());assertNull(context.userId());assertEquals("finance_manager",context.userRole());
            assertEquals("SERVICE",context.audit("SUCCESS").principalType());assertEquals("operating-report-scheduler",context.audit("SUCCESS").principalId());
            return new OperatingReportAssembler.Input("daily",day,day,"2026-01-01T20:00:00Z",List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of(),List.of(),Map.of());
        });
        when(model.chat(anyString(),anyString())).thenReturn("{\"insights\":[]}");
        when(store.save(eq("SYNTH-A"),any(),eq(true))).thenAnswer(invocation->{
            OperatingReportSnapshot snapshot=invocation.getArgument(1);
            assertEquals("SYNTH-A",snapshot.audit().factoryId());assertNotNull(snapshot.audit().runId());assertEquals("SUCCESS",snapshot.audit().outcome());
            AIAnalysisResult result=new AIAnalysisResult();result.setAnalysisText(codec.encode(snapshot));return result;
        });
        assertNotNull(generator.scheduled("SYNTH-A","daily",day,day));
        when(factories.findById("SYNTH-R")).thenReturn(Optional.of(factory("SYNTH-R",FactoryType.RESTAURANT)));
        assertThrows(BusinessException.class,()->generator.scheduled("SYNTH-R","daily",day,day));
        verify(collector,times(1)).collect(any(),anyString(),any(),any());
    }
    @Test void legitimateManualActorRetainsActualRoleAndSourceFailureHasServiceAudit()throws Exception{
        User actor=user("SYNTH-A","factory_super_admin");when(permissions.hasPermission(actor,"procurement:price:view")).thenReturn(true);
        OperatingReportContext manual=OperatingReportContext.manual("SYNTH-A",actor,permissions,"synthetic-correlation");
        assertEquals(123L,manual.userId());assertEquals("factory_super_admin",manual.userRole());assertEquals("USER",manual.audit("SUCCESS").principalType());
        when(factories.findById("SYNTH-A")).thenReturn(Optional.of(factory("SYNTH-A",FactoryType.FACTORY)));
        when(collector.collect(any(),anyString(),any(),any())).thenThrow(new IOException("Synthetic source failure; no production"));
        when(store.failure(anyString(),anyString(),any(),any(),any(),eq(true),any())).thenAnswer(invocation->{
            OperatingReportSnapshot.AuditReceipt audit=invocation.getArgument(6);
            assertEquals("SERVICE",audit.principalType());assertEquals("FAILED",audit.outcome());assertEquals("SYNTH-A",audit.factoryId());
            AIAnalysisResult result=new AIAnalysisResult();result.setAnalysisText(codec.encode(new OperatingReportSnapshot(OperatingReportSnapshot.SCHEMA,OperatingReportSnapshot.Status.FAILED,"daily",day.toString(),day.toString(),"synthetic",List.of(),List.of(),List.of(),OperatingReportSnapshot.Failure.SOURCE_UNAVAILABLE,audit)));return result;
        });
        assertThrows(IllegalStateException.class,()->generator.scheduled("SYNTH-A","daily",day,day));
        verifyNoInteractions(model);verify(store).failure(eq("SYNTH-A"),eq("daily"),eq(day),eq(day),eq(OperatingReportSnapshot.Failure.SOURCE_UNAVAILABLE),eq(true),any());
    }
}
