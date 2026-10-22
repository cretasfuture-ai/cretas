package com.cretas.aims.service.intent;

import com.cretas.aims.controller.PurchaseGrantTimerBindingController;
import com.cretas.aims.entity.ai.PurchaseGrantTimerBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PurchaseGrantTimerContractTest {
    @Test void strictRealControllerRejectsAllCallerIdentityBusinessAndHandlerFields() throws Exception {
        var timers=mock(PurchaseGrantTimerExecutionService.class);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var mvc=MockMvcBuilders.standaloneSetup(new PurchaseGrantTimerBindingController(timers))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        for(String field:new String[]{"serverConfirmed","userId","role","factoryId","grantId","handler","quantity","supplierId","materialTypeId","action","toolName"}) {
            mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-7/timer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"executeAt\":\"2099-01-01T01:02:03Z\",\""+field+"\":\"replacement\"}"))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(timers);
    }
    @Test void exactBodyReachesFixedBindingServiceWithUnchangedVersionAndTime() throws Exception {
        var timers=mock(PurchaseGrantTimerExecutionService.class);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var mvc=MockMvcBuilders.standaloneSetup(new PurchaseGrantTimerBindingController(timers))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-7/timer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"executeAt\":\"2099-01-01T01:02:03Z\"}"))
                .andExpect(status().isOk());
        verify(timers).bind("F006","preview-7",1L,LocalDateTime.ofInstant(java.time.Instant.parse("2099-01-01T01:02:03Z"),java.time.ZoneId.systemDefault()));
    }
    @Test void unsavedOrUnclaimedEntityCannotEstablishTimerIdentity() {
        var em=mock(EntityManager.class); var row=new PurchaseGrantTimerBinding();row.setState("RUNNING");
        assertThatThrownBy(()->PurchaseGrantTimerActor.fromPersisted(row,em)).isInstanceOf(SecurityException.class);
        when(em.contains(row)).thenReturn(true);row.setState("PENDING");
        assertThatThrownBy(()->PurchaseGrantTimerActor.fromPersisted(row,em)).isInstanceOf(SecurityException.class);
        verify(em,never()).find(any(),any());
    }
    @Test void explicitReconcileBodyRejectsOverridesAndOnlyInvokesReadbackSeam() throws Exception {
        var timers=mock(PurchaseGrantTimerExecutionService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PurchaseGrantTimerBindingController(timers))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        for(String field:new String[]{"quantity","action","userId","factoryId","grantId","expectedVersion","executeAt","serverConfirmed"}) {
            mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-7/timer/reconcile")
                    .contentType(MediaType.APPLICATION_JSON).content("{\""+field+"\":\"override\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(timers);
        mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-7/timer/reconcile")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        verify(timers).reconcileStatus("F006","preview-7");
        verifyNoMoreInteractions(timers);
    }
}
